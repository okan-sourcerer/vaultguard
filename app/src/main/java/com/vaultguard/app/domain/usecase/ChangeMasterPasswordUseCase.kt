package com.vaultguard.app.domain.usecase

import com.vaultguard.app.data.local.db.dao.CredentialDao
import com.vaultguard.app.data.remote.FirebaseSyncService
import com.vaultguard.app.security.BiometricAuthManager
import com.vaultguard.app.security.CryptoManager
import com.vaultguard.app.security.EncryptedData
import com.vaultguard.app.security.MasterPasswordManager
import timber.log.Timber
import javax.inject.Inject

/**
 * Result of a master-password change, so the UI can tell the user what else changed.
 */
data class ChangeMasterPasswordResult(
    val succeeded: Boolean,
    val reEncryptedCount: Int = 0,
    val biometricWasDisabled: Boolean = false
)

class ChangeMasterPasswordUseCase @Inject constructor(
    private val credentialDao: CredentialDao,
    private val cryptoManager: CryptoManager,
    private val masterPasswordManager: MasterPasswordManager,
    private val biometricAuthManager: BiometricAuthManager,
    private val syncService: FirebaseSyncService
) {
    suspend operator fun invoke(
        currentPassword: CharArray,
        newPassword: CharArray
    ): ChangeMasterPasswordResult {
        val oldKey = masterPasswordManager.getSessionKey()

        // Whether biometric unlock was on has to be read *before* the change, because the
        // answer decides what we tell the user afterwards.
        val biometricWasEnabled = biometricAuthManager.isBiometricEnabled

        // Verify and set up new master password (derives new key, stores new salt + verification)
        if (!masterPasswordManager.updateMasterPassword(currentPassword, newPassword)) {
            return ChangeMasterPasswordResult(succeeded = false)
        }

        val newKey = masterPasswordManager.getSessionKey()

        // The wrapped copy held by biometric unlock still contains the OLD vault key, and
        // nothing can re-wrap it without another biometric prompt. Leaving it in place
        // meant fingerprint unlock succeeded and then decrypted nothing — an apparently
        // empty vault (finding #6). Turn it off; the user re-enrols from Settings.
        //
        // Done before re-encryption so an interrupted sweep cannot leave biometric unlock
        // pointing at a key that opens only part of the vault.
        if (biometricWasEnabled) {
            biometricAuthManager.disableBiometric()
        }

        // Re-encrypt every credential with the new key
        // TODO(#5): not transactional — a failure part-way leaves rows split across two
        //  keys with no key that opens all of them. Chunk 5 makes this atomic.
        val allEntities = credentialDao.getAll().filter { !it.isDeleted }
        var reEncrypted = 0
        for (entity in allEntities) {
            val decrypted = cryptoManager.decrypt(
                EncryptedData(entity.encryptedPayload, entity.iv), oldKey
            )
            val reEncryptedPayload = cryptoManager.encrypt(decrypted, newKey)
            decrypted.fill(0)

            credentialDao.upsert(
                entity.copy(
                    encryptedPayload = reEncryptedPayload.ciphertext,
                    iv = reEncryptedPayload.iv,
                    updatedAt = System.currentTimeMillis()
                )
            )
            reEncrypted++
        }

        // Push new salt to Firestore so other devices can derive the new key
        try {
            val (ciphertext, iv) = masterPasswordManager.getVerificationData()
            syncService.pushVaultConfig(masterPasswordManager.getSalt(), ciphertext, iv)
        } catch (e: Exception) {
            Timber.w(e, "Could not push new vault config; will retry on next full sync")
        }

        return ChangeMasterPasswordResult(
            succeeded = true,
            reEncryptedCount = reEncrypted,
            biometricWasDisabled = biometricWasEnabled
        )
    }
}
