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
        // Format v1 sealed the envelope with a key the importer re-derived from the
        // recorded salt. Since the vault-key indirection landed, the session key is the
        // random vault key and is not derivable from the salt at all, so a v1 file written
        // now could never be restored — including by this app.
        //
        // Failing loudly beats writing a backup that looks fine and is not. Backup v2
        // (chunk 7) replaces this; until then the unencrypted CSV export is the working
        // path. See docs/DATA-FORMATS.md and finding #3.
        error(
            "Encrypted export is temporarily unavailable while the backup format is being " +
                "replaced. Use \"Export Unencrypted CSV\" under Migration, and store the " +
                "file somewhere encrypted."
        )

        @Suppress("UNREACHABLE_CODE")
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
