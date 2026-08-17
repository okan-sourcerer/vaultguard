package com.vaultguard.app.domain.usecase

import android.content.Context
import android.net.Uri
import com.vaultguard.app.data.local.db.dao.CredentialDao
import com.vaultguard.app.data.local.db.entity.CredentialEntity
import com.vaultguard.app.security.CryptoManager
import com.vaultguard.app.security.EncryptedData
import com.vaultguard.app.security.KeyDerivation
import dagger.hilt.android.qualifiers.ApplicationContext
import org.json.JSONArray
import org.json.JSONObject
import javax.inject.Inject

class ImportVaultUseCase @Inject constructor(
    @ApplicationContext private val context: Context,
    private val credentialDao: CredentialDao,
    private val cryptoManager: CryptoManager,
    private val keyDerivation: KeyDerivation
) {
    /**
     * @param merge true = skip existing IDs, false = replace all
     */
    suspend operator fun invoke(uri: Uri, masterPassword: CharArray, merge: Boolean) {
        val fileContent = context.contentResolver.openInputStream(uri)?.use { stream ->
            stream.readBytes().toString(Charsets.UTF_8)
        } ?: throw IllegalStateException("Cannot open input stream")

        val exportJson = JSONObject(fileContent)
        val version = exportJson.getInt("version")
        if (version != 1) throw IllegalArgumentException("Unsupported backup version: $version")

        val salt = android.util.Base64.decode(exportJson.getString("salt"), android.util.Base64.NO_WRAP)
        val iv = android.util.Base64.decode(exportJson.getString("iv"), android.util.Base64.NO_WRAP)
        val encryptedVault = android.util.Base64.decode(exportJson.getString("encryptedVault"), android.util.Base64.NO_WRAP)

        val key = keyDerivation.deriveKey(masterPassword, salt)

        val decrypted = try {
            cryptoManager.decrypt(EncryptedData(encryptedVault, iv), key)
        } catch (e: Exception) {
            throw IllegalArgumentException("Wrong password or corrupted backup")
        }

        val jsonArray = JSONArray(String(decrypted, Charsets.UTF_8))
        decrypted.fill(0)

        val entities = (0 until jsonArray.length()).map { i ->
            val obj = jsonArray.getJSONObject(i)
            CredentialEntity(
                id = obj.getString("id"),
                encryptedPayload = android.util.Base64.decode(obj.getString("encryptedPayload"), android.util.Base64.NO_WRAP),
                iv = android.util.Base64.decode(obj.getString("iv"), android.util.Base64.NO_WRAP),
                createdAt = obj.getLong("createdAt"),
                updatedAt = obj.getLong("updatedAt")
            )
        }

        if (merge) {
            for (entity in entities) {
                val existing = credentialDao.getById(entity.id)
                if (existing == null) {
                    credentialDao.upsert(entity)
                }
            }
        } else {
            // Replace: soft-delete all existing, then insert imported
            val allExisting = credentialDao.getAll()
            for (existing in allExisting) {
                credentialDao.softDelete(existing.id)
            }
            for (entity in entities) {
                credentialDao.upsert(entity)
            }
        }
    }
}
