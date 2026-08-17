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
        val now = System.currentTimeMillis()
        val existing = credentialDao.getById(credential.id)
        val json = CredentialPayloadCodec.encode(credential)
        val key = masterPasswordManager.getSessionKey()
        val plaintextBytes = json.toByteArray(Charsets.UTF_8)
        val encrypted = cryptoManager.encrypt(plaintextBytes, key)
        plaintextBytes.fill(0)

        val entity = CredentialEntity(
            id = credential.id.ifEmpty { UUID.randomUUID().toString() },
            encryptedPayload = encrypted.ciphertext,
            iv = encrypted.iv,
            createdAt = existing?.createdAt ?: now,
            updatedAt = now
        )
        credentialDao.upsert(entity)
    }

    override suspend fun delete(id: String) {
        credentialDao.softDelete(id)
    }

    override suspend fun search(query: String): VaultSnapshot<CredentialSummary> {
        val snapshot = decryptAll(credentialDao.getAll().filter { !it.isDeleted })
        val lowerQuery = query.lowercase()

        val matches = snapshot.items.filter { credential ->
            credential.siteName.lowercase().contains(lowerQuery) ||
                credential.appName.lowercase().contains(lowerQuery) ||
                credential.username.lowercase().contains(lowerQuery) ||
                credential.category.lowercase().contains(lowerQuery) ||
                credential.tags.any { it.lowercase().contains(lowerQuery) }
        }

        // Undecryptable rows are carried through unfiltered: a row that cannot be read
        // cannot be excluded by a search term either, and hiding it here would recreate
        // the original bug in a narrower form.
        return VaultSnapshot(
            items = matches.map { it.toSummary() },
            undecryptableIds = snapshot.undecryptableIds,
            isLocked = snapshot.isLocked
        )
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
                CredentialPayloadCodec.decode(json, entity.id, entity.createdAt, entity.updatedAt)
            )
        } catch (e: Exception) {
            Decrypted.Failure(e.message ?: e::class.java.simpleName)
        }
    }

    private sealed interface Decrypted {
        data class Success(val credential: Credential) : Decrypted
        data class Failure(val detail: String?) : Decrypted
    }
}
