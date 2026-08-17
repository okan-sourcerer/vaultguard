package com.vaultguard.app.domain.usecase

import com.vaultguard.app.data.local.db.dao.CredentialDao
import com.vaultguard.app.data.local.db.entity.CredentialEntity
import com.vaultguard.app.security.CryptoManager
import com.vaultguard.app.security.EncryptedData
import com.vaultguard.app.security.MasterPasswordManager
import timber.log.Timber
import javax.crypto.SecretKey
import javax.inject.Inject

/**
 * Unlocks the vault, resolving an interrupted master-password change if one is pending.
 *
 * A password change writes to two stores that cannot share a transaction: the salt in
 * encrypted preferences, and the rows in SQLCipher. [ChangeMasterPasswordUseCase] orders
 * those writes and rolls back on error, but process death between them is unavoidable.
 * When that happens a pending marker survives, and this use case works out which side
 * actually landed.
 *
 * The decisive test is not the verification blob — that only says which password was
 * typed. It is whether a real row decrypts, which says which key the vault contents are
 * actually under. That is the question that determines whether to finish the change or
 * undo it.
 */
class UnlockVaultUseCase @Inject constructor(
    private val masterPasswordManager: MasterPasswordManager,
    private val credentialDao: CredentialDao,
    private val cryptoManager: CryptoManager
) {

    sealed interface Result {
        data object Success : Result
        data object WrongPassword : Result

        /**
         * The password was recognised, but it is the wrong one of the two involved in an
         * interrupted change — the vault's rows are under the other key.
         */
        data class NeedsOtherPassword(val message: String) : Result
    }

    suspend operator fun invoke(password: CharArray): Result {
        val pending = masterPasswordManager.pendingChange
            ?: return if (masterPasswordManager.unlock(password)) {
                Result.Success
            } else {
                Result.WrongPassword
            }

        Timber.w("An interrupted master-password change is pending; resolving it")
        return resolvePendingChange(password, pending.salt, pending.verificationCiphertext, pending.verificationIv)
    }

    private suspend fun resolvePendingChange(
        password: CharArray,
        pendingSalt: ByteArray,
        pendingCiphertext: ByteArray,
        pendingIv: ByteArray
    ): Result {
        // deriveKey zeroes the array it is handed, so each attempt needs its own copy.
        val forCurrent = password.copyOf()
        val forPending = password.copyOf()
        password.fill(' ')

        val currentKey = masterPasswordManager.deriveCurrentKey(forCurrent)
        val currentVerifies = currentKey != null && masterPasswordManager.verifyKey(currentKey)

        val pendingKey = masterPasswordManager.deriveKey(forPending, pendingSalt)
        val pendingVerifies =
            masterPasswordManager.verifyKeyAgainst(pendingKey, pendingCiphertext, pendingIv)

        if (!currentVerifies && !pendingVerifies) return Result.WrongPassword

        val probe = firstReadableProbeRow()

        // No rows to test against: either key is as good as the other, so honour the
        // password the user actually typed.
        if (probe == null) {
            return when {
                pendingVerifies -> {
                    masterPasswordManager.commitChange()
                    masterPasswordManager.adoptProvenKey(pendingKey)
                    Timber.i("Empty vault — completed the interrupted password change")
                    Result.Success
                }
                else -> {
                    masterPasswordManager.abortChange()
                    masterPasswordManager.adoptProvenKey(currentKey!!)
                    Timber.i("Empty vault — discarded the interrupted password change")
                    Result.Success
                }
            }
        }

        if (pendingVerifies && opens(pendingKey, probe)) {
            // The rows were written before the crash. Finish the change.
            masterPasswordManager.commitChange()
            masterPasswordManager.adoptProvenKey(pendingKey)
            Timber.i("Completed an interrupted password change: vault is on the new key")
            return Result.Success
        }

        if (currentVerifies && currentKey != null && opens(currentKey, probe)) {
            // The rows never made it. Discard the change; the old password stands.
            masterPasswordManager.abortChange()
            masterPasswordManager.adoptProvenKey(currentKey)
            Timber.i("Discarded an interrupted password change: vault is still on the old key")
            return Result.Success
        }

        // Right password, wrong side of the change — the other one opens the rows.
        return Result.NeedsOtherPassword(
            if (pendingVerifies) {
                "A password change was interrupted before it finished. Enter your " +
                    "PREVIOUS master password to undo it."
            } else {
                "A password change was interrupted after it finished. Enter your " +
                    "NEW master password to complete it."
            }
        )
    }

    /** Any non-deleted row will do; we only need to learn which key opens the vault. */
    private suspend fun firstReadableProbeRow(): CredentialEntity? =
        credentialDao.getAll().firstOrNull { !it.isDeleted }

    private fun opens(key: SecretKey, entity: CredentialEntity): Boolean =
        try {
            cryptoManager
                .decrypt(EncryptedData(entity.encryptedPayload, entity.iv), key)
                .fill(0)
            true
        } catch (_: Exception) {
            false
        }
}
