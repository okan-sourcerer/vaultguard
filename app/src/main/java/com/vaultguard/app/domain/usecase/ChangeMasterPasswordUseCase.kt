package com.vaultguard.app.domain.usecase

import com.vaultguard.app.data.local.db.dao.CredentialDao
import com.vaultguard.app.data.remote.FirebaseSyncService
import com.vaultguard.app.security.CryptoManager
import com.vaultguard.app.security.EncryptedData
import com.vaultguard.app.security.MasterPasswordManager
import javax.inject.Inject

class ChangeMasterPasswordUseCase @Inject constructor(
    private val credentialDao: CredentialDao,
    private val cryptoManager: CryptoManager,
    private val masterPasswordManager: MasterPasswordManager,
    private val syncService: FirebaseSyncService
) {
    suspend operator fun invoke(currentPassword: CharArray, newPassword: CharArray): Boolean {
        val oldKey = masterPasswordManager.getSessionKey()

        // Verify and set up new master password (derives new key, stores new salt + verification)
        if (!masterPasswordManager.updateMasterPassword(currentPassword, newPassword)) {
            return false
        }

        val newKey = masterPasswordManager.getSessionKey()

        // Re-encrypt every credential with the new key
        val allEntities = credentialDao.getAll().filter { !it.isDeleted }
        for (entity in allEntities) {
            val decrypted = cryptoManager.decrypt(
                EncryptedData(entity.encryptedPayload, entity.iv), oldKey
            )
            val reEncrypted = cryptoManager.encrypt(decrypted, newKey)
            decrypted.fill(0)

            credentialDao.upsert(
                entity.copy(
                    encryptedPayload = reEncrypted.ciphertext,
                    iv = reEncrypted.iv,
                    updatedAt = System.currentTimeMillis()
                )
            )
        }

        // Push new salt to Firestore so other devices can derive the new key
        try {
            val (ciphertext, iv) = masterPasswordManager.getVerificationData()
            syncService.pushVaultConfig(masterPasswordManager.getSalt(), ciphertext, iv)
        } catch (_: Exception) {
            // Non-fatal — will sync on next fullSync
        }

        return true
    }
}
