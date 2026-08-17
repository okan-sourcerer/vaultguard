package com.vaultguard.app.domain.usecase

import com.vaultguard.app.data.remote.FirebaseSyncService
import com.vaultguard.app.security.MasterPasswordManager
import timber.log.Timber
import javax.inject.Inject

data class ChangeMasterPasswordResult(
    val succeeded: Boolean,
    val failureReason: String? = null
)

/**
 * Changes the master password by re-wrapping the vault key under a key derived from the
 * new one.
 *
 * No credential is read, decrypted, or written. That is the point of the vault-key
 * indirection: this used to rewrite every row across two stores that cannot share a
 * transaction, which is what made a partially-converted vault possible (finding #5).
 * Everything now lands in a single preferences write, and biometric unlock survives
 * untouched because the key it wraps is unchanged (finding #6).
 */
class ChangeMasterPasswordUseCase @Inject constructor(
    private val masterPasswordManager: MasterPasswordManager,
    private val syncService: FirebaseSyncService
) {
    suspend operator fun invoke(
        currentPassword: CharArray,
        newPassword: CharArray
    ): ChangeMasterPasswordResult {
        if (!masterPasswordManager.isVaultUnlocked) {
            newPassword.fill(' ')
            return ChangeMasterPasswordResult(
                succeeded = false,
                failureReason = "The vault must be unlocked to change its password."
            )
        }

        val currentMasterKey = masterPasswordManager.deriveMasterKey(currentPassword)
        if (currentMasterKey == null || !masterPasswordManager.verifyMasterKey(currentMasterKey)) {
            newPassword.fill(' ')
            return ChangeMasterPasswordResult(
                succeeded = false,
                failureReason = "Current password is incorrect"
            )
        }

        // Prefer the live session key over unwrapping again: it is the key the rows are
        // demonstrably encrypted under, having already been proved at unlock.
        val vaultKey = masterPasswordManager.getSessionKey()

        masterPasswordManager.rewrapForNewPassword(newPassword, vaultKey)

        // The salt changed, so any other device needs the new config — but only if the
        // owner turned sync on. Best effort; the next full sync republishes it.
        if (syncService.isSyncEnabled) {
            runCatching { syncService.pushVaultConfig() }
                .onFailure { Timber.w(it, "Could not push new vault config; will retry on sync") }
        }

        return ChangeMasterPasswordResult(succeeded = true)
    }
}
