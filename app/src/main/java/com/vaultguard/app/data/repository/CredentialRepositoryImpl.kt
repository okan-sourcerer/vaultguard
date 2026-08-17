package com.vaultguard.app.data.repository

import com.vaultguard.app.data.local.db.dao.CredentialDao
import com.vaultguard.app.data.local.db.entity.CredentialEntity
import com.vaultguard.app.domain.model.Credential
import com.vaultguard.app.domain.model.CredentialSummary
import com.vaultguard.app.domain.model.toSummary
import com.vaultguard.app.domain.repository.CredentialLookup
import com.vaultguard.app.domain.repository.CredentialRepository
import com.vaultguard.app.domain.repository.VaultSnapshot
import com.vaultguard.app.security.CryptoManager
import com.vaultguard.app.security.EncryptedData
import com.vaultguard.app.security.MasterPasswordManager
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import timber.log.Timber
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class CredentialRepositoryImpl @Inject constructor(
    private val credentialDao: CredentialDao,
    private val cryptoManager: CryptoManager,
    private val masterPasswordManager: MasterPasswordManager
) : CredentialRepository {

    override fun getAllCredentials(): Flow<VaultSnapshot<Credential>> =
        credentialDao.getAllCredentials().map { entities -> decryptAll(entities) }

    override fun getAllSummaries(): Flow<VaultSnapshot<CredentialSummary>> =
        credentialDao.getAllCredentials().map { entities ->
            decryptAll(entities).map { it.toSummary() }
        }

    override suspend fun getById(id: String): CredentialLookup {
        if (!masterPasswordManager.isVaultUnlocked) return CredentialLookup.Locked

        val entity = credentialDao.getById(id) ?: return CredentialLookup.NotFound
        if (entity.isDeleted) return CredentialLookup.NotFound

        return when (val outcome = decrypt(entity)) {
            is Decrypted.Success -> CredentialLookup.Found(outcome.credential)
            is Decrypted.Failure -> CredentialLookup.Undecryptable(id, outcome.detail)
        }
    }

    override suspend fun save(credential: Credential) {
        val existing = credentialDao.getById(credential.id)
        // Forced forward past the previous value. Sync treats a row as needing a push when
        // updatedAt > syncedAt, so a backwards clock could otherwise leave an edited row
        // looking already-synced and it would never be uploaded.
        val now = monotonicNow(existing?.updatedAt)

        // Decrypted once and used for both timestamp decisions below. Null for a new entry,
        // and also for a row that cannot be read — in which case neither timestamp can be
        // substantiated and both advance.
        val previous = existing?.let { decrypt(it) as? Decrypted.Success }?.credential

        val json = CredentialPayloadCodec.encode(
            credential.copy(contentChangedAt = contentChangedAtFor(existing, previous, credential, now))
        )
        val key = masterPasswordManager.getSessionKey()
        val plaintextBytes = json.toByteArray(Charsets.UTF_8)
        val encrypted = cryptoManager.encrypt(plaintextBytes, key)
        plaintextBytes.fill(0)

        val entity = CredentialEntity(
            id = credential.id.ifEmpty { UUID.randomUUID().toString() },
            encryptedPayload = encrypted.ciphertext,
            iv = encrypted.iv,
            createdAt = existing?.createdAt ?: now,
            updatedAt = now,
            passwordChangedAt = passwordChangedAtFor(existing, previous, credential.password, now)
        )
        credentialDao.upsert(entity)
    }

    /**
     * Advances the password-change timestamp only when the password actually differs.
     *
     * Comparing means decrypting the stored row, which is why this is not simply
     * `if (isNewEntry) now else existing.passwordChangedAt`. Without the comparison,
     * pinning an entry or fixing a typo in its notes would reset its reported password
     * age (finding #29).
     */
    private fun passwordChangedAtFor(
        existing: CredentialEntity?,
        previous: Credential?,
        newPassword: String,
        now: Long
    ): Long {
        if (existing == null) return now
        // An unreadable row cannot be compared. Treat the password as changed rather than
        // carrying forward a timestamp we cannot substantiate.
        if (previous == null) return now

        return if (previous.password == newPassword) existing.passwordChangedAt else now
    }

    /**
     * Advances the content-change timestamp only when something about the credential
     * itself differs (finding #58).
     *
     * Pinning is excluded deliberately: it is a preference about where the entry appears
     * in a list, not a change to the credential, and it was the reason the detail screen
     * reported entries as updated on days nothing had been edited. The timestamps are
     * excluded because they are the answer, not an input to it.
     */
    private fun contentChangedAtFor(
        existing: CredentialEntity?,
        previous: Credential?,
        updated: Credential,
        now: Long
    ): Long {
        if (existing == null || previous == null) return now

        val unchanged = previous.siteName == updated.siteName &&
            previous.appName == updated.appName &&
            previous.url == updated.url &&
            previous.username == updated.username &&
            previous.password == updated.password &&
            previous.notes == updated.notes &&
            previous.category == updated.category &&
            previous.tags == updated.tags &&
            previous.linkedPackages == updated.linkedPackages &&
            previous.linkedDomains == updated.linkedDomains

        return if (unchanged) previous.contentChangedAt else now
    }

    override suspend fun delete(id: String) {
        credentialDao.softDelete(id)
    }

    override suspend fun undoDelete(id: String) {
        credentialDao.undoSoftDelete(id)
    }

    override suspend fun finaliseDelete(id: String) {
        credentialDao.hardDeleteIfNeverSynced(id)
    }

    /**
     * Decrypts a batch, keeping the failures rather than discarding them.
     *
     * A locked vault is reported as [VaultSnapshot.locked] rather than as N decryption
     * failures — it is an expected state, not damage, and conflating the two would make
     * the error banner fire every time the vault auto-locks.
     */
    private fun decryptAll(entities: List<CredentialEntity>): VaultSnapshot<Credential> {
        if (!masterPasswordManager.isVaultUnlocked) return VaultSnapshot.locked()

        val items = mutableListOf<Credential>()
        val failed = mutableListOf<String>()

        for (entity in entities) {
            when (val outcome = decrypt(entity)) {
                is Decrypted.Success -> items += outcome.credential
                is Decrypted.Failure -> failed += entity.id
            }
        }

        if (failed.isNotEmpty()) {
            // Ids only. The whole point is that we could not read the contents, and
            // logging ciphertext would be noise at best.
            Timber.e("Vault decryption failed for %d of %d rows: %s", failed.size, entities.size, failed)
        }

        return VaultSnapshot(items = items, undecryptableIds = failed)
    }

    private fun decrypt(entity: CredentialEntity): Decrypted {
        return try {
            val key = masterPasswordManager.getSessionKey()
            val decrypted = cryptoManager.decrypt(
                EncryptedData(entity.encryptedPayload, entity.iv),
                key
            )
            val json = String(decrypted, Charsets.UTF_8)
            decrypted.fill(0)
            Decrypted.Success(
                CredentialPayloadCodec.decode(
                    json, entity.id, entity.createdAt, entity.updatedAt, entity.passwordChangedAt
                )
            )
        } catch (e: Exception) {
            Decrypted.Failure(e.message ?: e::class.java.simpleName)
        }
    }

    /** Wall clock, but never earlier than [previous]. */
    private fun monotonicNow(previous: Long?): Long {
        val now = System.currentTimeMillis()
        return if (previous != null && previous >= now) previous + 1 else now
    }

    private sealed interface Decrypted {
        data class Success(val credential: Credential) : Decrypted
        data class Failure(val detail: String?) : Decrypted
    }
}
