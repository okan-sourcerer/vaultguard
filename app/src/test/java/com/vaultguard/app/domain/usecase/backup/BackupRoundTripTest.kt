package com.vaultguard.app.domain.usecase.backup

import com.vaultguard.app.data.repository.CredentialPayloadCodec
import com.vaultguard.app.domain.model.Credential
import com.vaultguard.app.security.CryptoManager
import com.vaultguard.app.security.EncryptedData
import com.vaultguard.app.security.KeyDerivation
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import javax.crypto.AEADBadTagException
import javax.crypto.SecretKey

/**
 * End-to-end crypto for backups, without Android or I/O in the way.
 *
 * The case that matters is the one v1 could not do: **export under one key, import under a
 * different one.** That is the whole of finding #3 — v1 only ever round-tripped when the
 * receiving vault held the byte-identical key, which after a password change or on another
 * device it never did.
 */
class BackupRoundTripTest {

    private val crypto = CryptoManager()
    private val keyDerivation = KeyDerivation()

    private fun credentials(count: Int = 3) = (0 until count).map { index ->
        Credential(
            id = "id-$index",
            siteName = "Site $index",
            username = "user-$index",
            password = "pw-$index",
            notes = "notes $index",
            tags = listOf("tag$index"),
            createdAt = 1_000L + index,
            updatedAt = 2_000L + index,
            passwordChangedAt = 1_500L + index
        )
    }

    /** Writes a v2 backup exactly as ExportVaultUseCase does. */
    private fun exportV2(items: List<Credential>, backupPassword: String): String {
        val salt = keyDerivation.generateSalt()
        val key = keyDerivation.deriveKey(backupPassword.toCharArray(), salt)
        val sealed = crypto.encrypt(
            VaultBackupFormat.writeEntries(items).toByteArray(Charsets.UTF_8), key
        )
        return VaultBackupFormat.writeEnvelope(
            VaultBackupFormat.KdfParams(salt), sealed.iv, sealed.ciphertext
        )
    }

    /** Reads a backup and re-seals under [localVaultKey], as ImportVaultUseCase does. */
    private fun importAndReseal(
        document: String,
        backupPassword: String,
        localVaultKey: SecretKey
    ): List<Credential> {
        val envelope = VaultBackupFormat.readEnvelope(document)
        val backupKey = keyDerivation.deriveKey(
            backupPassword.toCharArray(),
            envelope.kdf.salt,
            envelope.kdf.memoryKib,
            envelope.kdf.iterations,
            envelope.kdf.parallelism
        )
        val plaintext = crypto.decrypt(EncryptedData(envelope.ciphertext, envelope.iv), backupKey)

        val restored = when (envelope.version) {
            VaultBackupFormat.VERSION_2 ->
                VaultBackupFormat.readEntries(String(plaintext)).map { it.credential }
            else -> VaultBackupFormat.readLegacyRows(String(plaintext)).map { row ->
                val payload = crypto.decrypt(EncryptedData(row.encryptedPayload, row.iv), backupKey)
                CredentialPayloadCodec.decode(String(payload), row.id, row.createdAt, row.updatedAt)
            }
        }

        // Seal under the receiving vault's key, then read back — proving the imported rows
        // are usable by *this* vault and not merely parsed.
        return restored.map { credential ->
            val sealed = crypto.encrypt(
                CredentialPayloadCodec.encode(credential).toByteArray(), localVaultKey
            )
            val reopened = crypto.decrypt(EncryptedData(sealed.ciphertext, sealed.iv), localVaultKey)
            CredentialPayloadCodec.decode(
                String(reopened), credential.id, credential.createdAt, credential.updatedAt,
                credential.passwordChangedAt
            )
        }
    }

    private fun randomVaultKey(): SecretKey =
        javax.crypto.spec.SecretKeySpec(
            ByteArray(32).also { java.security.SecureRandom().nextBytes(it) }, "AES"
        )

    // -- The case v1 could not handle ------------------------------------------------------------

    @Test
    fun `a backup restores into a vault holding a completely different key`() {
        val source = credentials(4)
        val document = exportV2(source, "backup-password")

        val restored = importAndReseal(document, "backup-password", randomVaultKey())

        assertEquals(source.map { it.password }, restored.map { it.password })
        assertEquals(source.map { it.siteName }, restored.map { it.siteName })
    }

    @Test
    fun `the backup password is unrelated to any vault key`() {
        val document = exportV2(credentials(1), "backup-password")

        // Two different receiving vaults, same backup, same result.
        val intoOne = importAndReseal(document, "backup-password", randomVaultKey())
        val intoAnother = importAndReseal(document, "backup-password", randomVaultKey())

        assertEquals(intoOne.map { it.password }, intoAnother.map { it.password })
    }

    @Test
    fun `metadata survives the round trip`() {
        val source = credentials(2)
        val document = exportV2(source, "backup-password")

        val restored = importAndReseal(document, "backup-password", randomVaultKey())

        assertEquals(source.map { it.createdAt }, restored.map { it.createdAt })
        assertEquals(source.map { it.passwordChangedAt }, restored.map { it.passwordChangedAt })
        assertEquals(source.map { it.tags }, restored.map { it.tags })
    }

    @Test
    fun `the wrong backup password fails loudly`() {
        val document = exportV2(credentials(2), "correct-password")

        assertThrows(AEADBadTagException::class.java) {
            importAndReseal(document, "wrong-password", randomVaultKey())
        }
    }

    @Test
    fun `a tampered payload fails authentication`() {
        val document = exportV2(credentials(2), "backup-password")
        val root = JSONObject(document)
        val payload = java.util.Base64.getDecoder().decode(root.getString("payload"))
        payload[0]++
        root.put("payload", java.util.Base64.getEncoder().encodeToString(payload))

        assertThrows(AEADBadTagException::class.java) {
            importAndReseal(root.toString(), "backup-password", randomVaultKey())
        }
    }

    @Test
    fun `two exports of the same vault differ`() {
        // Fresh salt and IV each time; identical files would leak that nothing changed.
        val items = credentials(2)

        assertNotEquals(
            JSONObject(exportV2(items, "pw")).getString("payload"),
            JSONObject(exportV2(items, "pw")).getString("payload")
        )
    }

    @Test
    fun `the file contains no plaintext password`() {
        val document = exportV2(
            listOf(credentials(1).first().copy(password = "TOTALLY-DISTINCTIVE")), "pw"
        )

        assertTrue(!document.contains("TOTALLY-DISTINCTIVE"))
    }

    // -- v1 files, now genuinely restorable -----------------------------------------------------------

    /**
     * Builds a v1 file the way the old exporter did: an outer envelope and inner payloads
     * both sealed under the key derived from the master password of the day.
     */
    private fun exportV1(items: List<Credential>, masterPassword: String): String {
        val salt = keyDerivation.generateSalt()
        val key = keyDerivation.deriveKey(masterPassword.toCharArray(), salt)

        val rows = JSONArray()
        for (credential in items) {
            val sealed = crypto.encrypt(
                CredentialPayloadCodec.encode(credential).toByteArray(), key
            )
            rows.put(
                JSONObject().apply {
                    put("id", credential.id)
                    put("encryptedPayload", java.util.Base64.getEncoder().encodeToString(sealed.ciphertext))
                    put("iv", java.util.Base64.getEncoder().encodeToString(sealed.iv))
                    put("createdAt", credential.createdAt)
                    put("updatedAt", credential.updatedAt)
                }
            )
        }

        val outer = crypto.encrypt(rows.toString().toByteArray(), key)
        return JSONObject().apply {
            put("version", 1)
            put("exportedAt", 1L)
            put("salt", java.util.Base64.getEncoder().encodeToString(salt))
            put("iv", java.util.Base64.getEncoder().encodeToString(outer.iv))
            put("encryptedVault", java.util.Base64.getEncoder().encodeToString(outer.ciphertext))
        }.toString()
    }

    @Test
    fun `an old v1 backup restores into a vault with an unrelated key`() {
        // This never worked before. The importer inserted the inner blobs verbatim, so
        // unless the receiving vault held the exporting key, every entry was unreadable.
        val source = credentials(3)
        val document = exportV1(source, "old-master-password")

        val restored = importAndReseal(document, "old-master-password", randomVaultKey())

        assertEquals(source.map { it.password }, restored.map { it.password })
        assertEquals(source.map { it.username }, restored.map { it.username })
    }

    @Test
    fun `a v1 backup with the wrong password fails at the outer layer`() {
        val document = exportV1(credentials(2), "old-master-password")

        assertThrows(AEADBadTagException::class.java) {
            importAndReseal(document, "not-it", randomVaultKey())
        }
    }
}
