package com.vaultguard.app.domain.usecase

import com.vaultguard.app.data.local.db.dao.CredentialDao
import com.vaultguard.app.data.local.db.entity.CredentialEntity
import com.vaultguard.app.di.CryptoDispatcher
import com.vaultguard.app.security.CryptoManager
import com.vaultguard.app.security.EncryptedData
import com.vaultguard.app.security.MasterPasswordManager
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import timber.log.Timber
import javax.crypto.SecretKey
import javax.inject.Inject

/**
 * Unlocks the vault, converting a legacy master-key-encrypted vault to the vault-key
 * layout if it has not been converted yet.
 *
 * The decisive question is never "which password was typed" — the verification blob
 * answers that — but **which key the rows are actually encrypted under**. So this probes a
 * real row. That single test covers the normal case, a vault predating the indirection,
 * and a conversion interrupted halfway, without needing separate state to distinguish them.
 */
class UnlockVaultUseCase @Inject constructor(
    private val masterPasswordManager: MasterPasswordManager,
    private val credentialDao: CredentialDao,
    private val cryptoManager: CryptoManager,
    @CryptoDispatcher private val cryptoDispatcher: CoroutineDispatcher
) {

    sealed interface Result {
        data object Success : Result
        data object WrongPassword : Result

        /** The password is right but the rows open with neither key — real damage. */
        data class VaultUnreadable(val detail: String) : Result
    }

    suspend operator fun invoke(password: CharArray): Result {
        val masterKey = masterPasswordManager.deriveMasterKey(password) ?: return Result.WrongPassword
        if (!masterPasswordManager.verifyMasterKey(masterKey)) return Result.WrongPassword

        val storedVaultKey = masterPasswordManager.unwrapVaultKey(masterKey)
        val probe = credentialDao.getAll().firstOrNull { !it.isDeleted }

        // Normal path: the stored vault key opens the rows, or there are no rows to check.
        if (storedVaultKey != null && (probe == null || opens(storedVaultKey, probe))) {
            masterPasswordManager.adoptVaultKey(storedVaultKey)
            return Result.Success
        }

        // Rows still open with the master key directly. Either this vault predates the
        // indirection, or a previous conversion was interrupted after the wrapped key was
        // stored but before the rows were rewritten. Reusing storedVaultKey when present
        // is what makes the second case resume rather than start over.
        if (probe != null && opens(masterKey, probe)) {
            val vaultKey = storedVaultKey ?: masterPasswordManager.generateVaultKey()
            return convertToVaultKey(masterKey, vaultKey)
        }

        // Nothing stored and nothing to convert: a vault that was set up but never used.
        if (storedVaultKey == null && probe == null) {
            val vaultKey = masterPasswordManager.generateVaultKey()
            masterPasswordManager.storeVaultKey(vaultKey, masterKey)
            masterPasswordManager.adoptVaultKey(vaultKey)
            return Result.Success
        }

        Timber.e("Master password verified but no available key opens the vault rows")
        return Result.VaultUnreadable(
            "Your master password is correct, but the stored entries cannot be decrypted " +
                "with it. Do not delete anything — export a backup and check whether this " +
                "vault was replaced by one from another device."
        )
    }

    /**
     * Re-encrypts every row from the master key to [vaultKey].
     *
     * Same discipline as the master-password change: everything is re-encrypted in memory
     * before a single transactional write, so a failure leaves the vault untouched and
     * still openable the old way.
     *
     * The wrapped vault key is stored **first**. If the process dies before the rows are
     * written, the next unlock finds a stored key that does not open them, takes the
     * branch above, and resumes with that same key.
     */
    private suspend fun convertToVaultKey(masterKey: SecretKey, vaultKey: SecretKey): Result {
        val originals = credentialDao.getAll().filter { !it.isDeleted }

        val converted = try {
            withContext(cryptoDispatcher) {
                originals.map { entity ->
                    val plaintext = cryptoManager.decrypt(
                        EncryptedData(entity.encryptedPayload, entity.iv), masterKey
                    )
                    val sealed = cryptoManager.encrypt(plaintext, vaultKey)
                    plaintext.fill(0)
                    entity.copy(
                        encryptedPayload = sealed.ciphertext,
                        iv = sealed.iv,
                        // The ciphertext genuinely changed, so sync must see it. Note that
                        // passwordChangedAt is deliberately carried over untouched —
                        // re-encryption is not a rotation (finding #29).
                        updatedAt = System.currentTimeMillis()
                    )
                }
            }
        } catch (e: Exception) {
            Timber.e(e, "Vault-key conversion aborted: not every row could be re-encrypted")
            return Result.VaultUnreadable(
                "Some entries could not be re-encrypted, so nothing was changed. " +
                    "Export a backup before continuing."
            )
        }

        return withContext(NonCancellable) {
            try {
                masterPasswordManager.storeVaultKey(vaultKey, masterKey)
                credentialDao.upsertAll(converted)
            } catch (e: Exception) {
                Timber.e(e, "Vault-key conversion failed to commit")
                return@withContext Result.VaultUnreadable(
                    "Could not finish preparing the vault. Restart and try again."
                )
            }

            masterPasswordManager.adoptVaultKey(vaultKey)
            Timber.i("Converted %d rows to the vault-key layout", converted.size)
            Result.Success
        }
    }

    private fun opens(key: SecretKey, entity: CredentialEntity): Boolean =
        try {
            cryptoManager.decrypt(EncryptedData(entity.encryptedPayload, entity.iv), key).fill(0)
            true
        } catch (_: Exception) {
            false
        }
}
