package com.vaultguard.app.domain.usecase.backup

import android.content.Context
import android.net.Uri
import com.vaultguard.app.data.local.db.dao.CredentialDao
import com.vaultguard.app.data.local.db.entity.CredentialEntity
import com.vaultguard.app.data.repository.CredentialPayloadCodec
import com.vaultguard.app.di.CryptoDispatcher
import com.vaultguard.app.domain.model.Credential
import com.vaultguard.app.security.CryptoManager
import com.vaultguard.app.security.EncryptedData
import com.vaultguard.app.security.KeyDerivation
import com.vaultguard.app.security.MasterPasswordManager
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import timber.log.Timber
import javax.crypto.SecretKey
import javax.inject.Inject

data class ImportResult(
    val imported: Int,
    val skipped: Int,
    val replaced: Int,
    val formatVersion: Int
)

class WrongBackupPasswordException :
    Exception("Wrong backup password, or the file is damaged.")

/**
 * Restores an encrypted backup, re-encrypting every credential under the **local** vault
 * key.
 *
 * That re-encryption is the fix for finding #3. The old importer inserted each row's
 * ciphertext verbatim, so the import appeared to succeed and every credential was then
 * unreadable unless the receiving vault happened to hold the byte-identical key.
 *
 * Both formats are accepted. A v1 file's outer envelope and its inner payloads were sealed
 * under the same key — the export-time master key, which back then was also the payload
 * key — so knowing the backup password is enough to open both layers. v1 backups are
 * therefore genuinely restorable now, which they never were before.
 */
class ImportVaultUseCase @Inject constructor(
    @ApplicationContext private val context: Context,
    private val credentialDao: CredentialDao,
    private val cryptoManager: CryptoManager,
    private val keyDerivation: KeyDerivation,
    private val masterPasswordManager: MasterPasswordManager,
    @CryptoDispatcher private val cryptoDispatcher: CoroutineDispatcher
) {
    /**
     * @param merge true = keep existing entries and add only unseen ids;
     *              false = replace the vault with the backup's contents.
     */
    suspend operator fun invoke(uri: Uri, backupPassword: CharArray, merge: Boolean): ImportResult {
        check(masterPasswordManager.isVaultUnlocked) {
            "The vault must be unlocked before importing."
        }
        val vaultKey = masterPasswordManager.getSessionKey()

        val text = withContext(Dispatchers.IO) {
            context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                ?.toString(Charsets.UTF_8)
                ?: error("Cannot open $uri")
        }

        val envelope = VaultBackupFormat.readEnvelope(text)

        val credentials = withContext(cryptoDispatcher) {
            val backupKey = keyDerivation.deriveKey(
                backupPassword,
                envelope.kdf.salt,
                envelope.kdf.memoryKib,
                envelope.kdf.iterations,
                envelope.kdf.parallelism
            )

            val decrypted = try {
                cryptoManager.decrypt(EncryptedData(envelope.ciphertext, envelope.iv), backupKey)
            } catch (e: Exception) {
                throw WrongBackupPasswordException()
            }

            val json = String(decrypted, Charsets.UTF_8)
            decrypted.fill(0)

            when (envelope.version) {
                VaultBackupFormat.VERSION_2 -> VaultBackupFormat.readEntries(json)
                    .map { it.credential }

                VaultBackupFormat.VERSION_1 -> openLegacyRows(json, backupKey)

                else -> error("unreachable: readEnvelope rejects unknown versions")
            }
        }

        check(credentials.isNotEmpty()) {
            "The backup decrypted successfully but contains no entries."
        }

        return withContext(NonCancellable) { apply(credentials, vaultKey, merge, envelope.version) }
    }

    /**
     * A v1 row's payload is sealed under the same key as the envelope around it, so the
     * backup key opens both. Any row that resists is reported rather than carried over as
     * ciphertext nobody can read — which is precisely what the old importer did.
     */
    private fun openLegacyRows(json: String, backupKey: SecretKey): List<Credential> {
        val rows = VaultBackupFormat.readLegacyRows(json)
        val failed = mutableListOf<String>()

        val credentials = rows.mapNotNull { row ->
            try {
                val plaintext = cryptoManager.decrypt(
                    EncryptedData(row.encryptedPayload, row.iv), backupKey
                )
                val payload = String(plaintext, Charsets.UTF_8)
                plaintext.fill(0)
                CredentialPayloadCodec.decode(payload, row.id, row.createdAt, row.updatedAt)
            } catch (e: Exception) {
                failed += row.id
                null
            }
        }

        if (failed.isNotEmpty()) {
            throw WrongBackupPasswordException()
        }
        return credentials
    }

    private suspend fun apply(
        credentials: List<Credential>,
        vaultKey: SecretKey,
        merge: Boolean,
        formatVersion: Int
    ): ImportResult {
        val existingIds = credentialDao.getAll().associateBy { it.id }
        val now = System.currentTimeMillis()

        val toWrite = mutableListOf<CredentialEntity>()
        var skipped = 0
        var replaced = 0

        for (credential in credentials) {
            val existing = existingIds[credential.id]
            if (merge && existing != null && !existing.isDeleted) {
                skipped++
                continue
            }
            if (existing != null) replaced++

            // The point of the whole exercise: sealed under this device's vault key with a
            // fresh IV, not carried across as opaque bytes.
            val payload = CredentialPayloadCodec.encode(credential).toByteArray(Charsets.UTF_8)
            val sealed = cryptoManager.encrypt(payload, vaultKey)
            payload.fill(0)

            toWrite += CredentialEntity(
                id = credential.id.ifEmpty { java.util.UUID.randomUUID().toString() },
                encryptedPayload = sealed.ciphertext,
                iv = sealed.iv,
                createdAt = credential.createdAt.takeIf { it > 0 } ?: now,
                updatedAt = now,
                passwordChangedAt = credential.passwordChangedAt.takeIf { it > 0 }
                    ?: credential.createdAt.takeIf { it > 0 } ?: now
            )
        }

        // Replace mode retires anything the backup does not mention, but only after the
        // imported rows are known-good, and in the same transaction as writing them.
        val tombstones = if (merge) {
            emptyList()
        } else {
            val importedIds = credentials.map { it.id }.toSet()
            existingIds.values
                .filter { !it.isDeleted && it.id !in importedIds }
                .map { it.copy(isDeleted = true, updatedAt = now) }
        }

        credentialDao.upsertAll(toWrite + tombstones)

        Timber.i(
            "Imported %d credentials from a v%d backup (%d skipped, %d replaced, %d retired)",
            toWrite.size, formatVersion, skipped, replaced, tombstones.size
        )

        return ImportResult(
            imported = toWrite.size,
            skipped = skipped,
            replaced = replaced,
            formatVersion = formatVersion
        )
    }
}
