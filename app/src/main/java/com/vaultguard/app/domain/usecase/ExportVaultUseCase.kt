package com.vaultguard.app.domain.usecase

import android.content.Context
import android.net.Uri
import com.vaultguard.app.data.local.db.dao.CredentialDao
import com.vaultguard.app.security.CryptoManager
import com.vaultguard.app.security.KeyDerivation
import com.vaultguard.app.security.MasterPasswordManager
import dagger.hilt.android.qualifiers.ApplicationContext
import org.json.JSONArray
import org.json.JSONObject
import javax.inject.Inject

class ExportVaultUseCase @Inject constructor(
    @ApplicationContext private val context: Context,
    private val credentialDao: CredentialDao,
    private val cryptoManager: CryptoManager,
    private val masterPasswordManager: MasterPasswordManager,
    private val keyDerivation: KeyDerivation
) {
    suspend operator fun invoke(uri: Uri) {
        val key = masterPasswordManager.getSessionKey()
        val salt = masterPasswordManager.getSalt()

        // Collect all non-deleted credential entities as JSON array
        val entities = credentialDao.getAll().filter { !it.isDeleted }
        val jsonArray = JSONArray()
        for (entity in entities) {
            jsonArray.put(JSONObject().apply {
                put("id", entity.id)
                put("encryptedPayload", android.util.Base64.encodeToString(entity.encryptedPayload, android.util.Base64.NO_WRAP))
                put("iv", android.util.Base64.encodeToString(entity.iv, android.util.Base64.NO_WRAP))
                put("createdAt", entity.createdAt)
                put("updatedAt", entity.updatedAt)
            })
        }

        val vaultBytes = jsonArray.toString().toByteArray(Charsets.UTF_8)
        val encrypted = cryptoManager.encrypt(vaultBytes, key)
        vaultBytes.fill(0)

        val exportJson = JSONObject().apply {
            put("version", 1)
            put("exportedAt", System.currentTimeMillis())
            put("salt", android.util.Base64.encodeToString(salt, android.util.Base64.NO_WRAP))
            put("iv", android.util.Base64.encodeToString(encrypted.iv, android.util.Base64.NO_WRAP))
            put("encryptedVault", android.util.Base64.encodeToString(encrypted.ciphertext, android.util.Base64.NO_WRAP))
        }

        context.contentResolver.openOutputStream(uri)?.use { stream ->
            stream.write(exportJson.toString(2).toByteArray(Charsets.UTF_8))
        } ?: throw IllegalStateException("Cannot open output stream")
    }
}
