package com.vaultguard.app.domain.usecase.backup

import com.vaultguard.app.domain.model.Credential
import com.vaultguard.app.security.KeyDerivation
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for the backup file format itself, independent of crypto and I/O.
 *
 * A backup file is untrusted input: it may be truncated, hand-edited, written by a newer
 * build, or hostile. Every one of those has to produce a clear message rather than a
 * crash or, worse, a partial import.
 */
class VaultBackupFormatTest {

    private fun credential(id: String = "a", password: String = "pw") = Credential(
        id = id,
        siteName = "GitHub",
        appName = "GitHub",
        url = "https://github.com",
        username = "okan",
        password = password,
        notes = "note",
        category = "Development",
        tags = listOf("work", "2fa"),
        isPinned = true,
        linkedPackages = listOf("com.github.android"),
        linkedDomains = listOf("github.com"),
        createdAt = 1_750_000_000_000,
        updatedAt = 1_755_000_000_000,
        passwordChangedAt = 1_752_000_000_000
    )

    private fun envelope(
        kdf: VaultBackupFormat.KdfParams = VaultBackupFormat.KdfParams(ByteArray(16) { 7 })
    ) = VaultBackupFormat.writeEnvelope(kdf, ByteArray(12) { 3 }, ByteArray(64) { 9 })

    // -- Entries round-trip ------------------------------------------------------------------

    @Test
    fun `entries round-trip with every field`() {
        val source = credential()

        val restored = VaultBackupFormat.readEntries(
            VaultBackupFormat.writeEntries(listOf(source))
        ).single()

        assertEquals(source, restored.credential)
        assertEquals(source.createdAt, restored.createdAt)
        assertEquals(source.passwordChangedAt, restored.passwordChangedAt)
    }

    @Test
    fun `entries round-trip awkward characters`() {
        val source = credential(password = """a"b\c{d}e,f'g/h""")
            .copy(notes = "line one\nline two\t🔐 密码")

        val restored = VaultBackupFormat.readEntries(
            VaultBackupFormat.writeEntries(listOf(source))
        ).single()

        assertEquals(source.password, restored.credential.password)
        assertEquals(source.notes, restored.credential.notes)
    }

    @Test
    fun `the payload carries plaintext, not per-row ciphertext`() {
        // The v1 defect in one assertion: its payload held encrypted blobs keyed to the
        // exporting device, which is why importing produced unreadable entries (#3).
        val json = VaultBackupFormat.writeEntries(listOf(credential(password = "DISTINCTIVE")))

        assertTrue(json.contains("DISTINCTIVE"))
        assertTrue(!json.contains("encryptedPayload"))
    }

    @Test
    fun `an empty vault writes an empty array`() {
        assertEquals(emptyList<VaultBackupFormat.Entry>(),
            VaultBackupFormat.readEntries(VaultBackupFormat.writeEntries(emptyList())))
    }

    @Test
    fun `passwordChangedAt falls back to updatedAt when absent`() {
        val json = """[{"id":"a","createdAt":100,"updatedAt":200,"siteName":"S"}]"""

        val entry = VaultBackupFormat.readEntries(json).single()

        assertEquals(200L, entry.passwordChangedAt)
    }

    // -- Envelope -----------------------------------------------------------------------------

    @Test
    fun `envelope round-trips`() {
        val kdf = VaultBackupFormat.KdfParams(ByteArray(16) { 7 })

        val parsed = VaultBackupFormat.readEnvelope(envelope(kdf))

        assertEquals(VaultBackupFormat.VERSION_2, parsed.version)
        assertEquals(kdf, parsed.kdf)
        assertTrue(parsed.iv.contentEquals(ByteArray(12) { 3 }))
        assertTrue(parsed.ciphertext.contentEquals(ByteArray(64) { 9 }))
    }

    @Test
    fun `envelope records the kdf parameters explicitly`() {
        // Carried in the file so a future change to the vault's Argon2 cost cannot orphan
        // backups written today.
        val root = JSONObject(envelope())
        val kdf = root.getJSONObject("kdf")

        assertEquals("argon2id", kdf.getString("algorithm"))
        assertEquals(KeyDerivation.ARGON2_VERSION, kdf.getInt("version"))
        assertEquals(KeyDerivation.MEMORY_COST_KIB, kdf.getInt("memoryKib"))
        assertEquals(KeyDerivation.ITERATIONS, kdf.getInt("iterations"))
        assertEquals(KeyDerivation.PARALLELISM, kdf.getInt("parallelism"))
    }

    @Test
    fun `non-default kdf parameters survive`() {
        val kdf = VaultBackupFormat.KdfParams(
            salt = ByteArray(16) { 1 }, memoryKib = 32768, iterations = 5, parallelism = 2
        )

        assertEquals(kdf, VaultBackupFormat.readEnvelope(envelope(kdf)).kdf)
    }

    // -- Hostile and damaged input ---------------------------------------------------------------

    @Test
    fun `rejects a file that is not json`() {
        assertThrows(VaultBackupFormat.UnsupportedBackupException::class.java) {
            VaultBackupFormat.readEnvelope("not a backup at all")
        }
    }

    @Test
    fun `rejects json without a version`() {
        assertThrows(VaultBackupFormat.UnsupportedBackupException::class.java) {
            VaultBackupFormat.readEnvelope("""{"hello":"world"}""")
        }
    }

    @Test
    fun `rejects a newer format with a message naming the version`() {
        val exception = assertThrows(VaultBackupFormat.UnsupportedBackupException::class.java) {
            VaultBackupFormat.readEnvelope("""{"version":99}""")
        }

        assertTrue(exception.message!!.contains("99"))
    }

    @Test
    fun `rejects a missing field`() {
        val root = JSONObject(envelope()).apply { remove("payload") }

        assertThrows(VaultBackupFormat.UnsupportedBackupException::class.java) {
            VaultBackupFormat.readEnvelope(root.toString())
        }
    }

    @Test
    fun `rejects malformed base64`() {
        val root = JSONObject(envelope()).apply { put("iv", "!!!not base64!!!") }

        assertThrows(VaultBackupFormat.UnsupportedBackupException::class.java) {
            VaultBackupFormat.readEnvelope(root.toString())
        }
    }

    @Test
    fun `rejects an unknown key derivation algorithm`() {
        val root = JSONObject(envelope())
        root.getJSONObject("kdf").put("algorithm", "scrypt")

        assertThrows(VaultBackupFormat.UnsupportedBackupException::class.java) {
            VaultBackupFormat.readEnvelope(root.toString())
        }
    }

    @Test
    fun `rejects absurd memory cost rather than attempting it`() {
        // A file claiming 64 GiB would take the app down before it could report a problem.
        val root = JSONObject(envelope())
        root.getJSONObject("kdf").put("memoryKib", 64 * 1024 * 1024)

        assertThrows(VaultBackupFormat.UnsupportedBackupException::class.java) {
            VaultBackupFormat.readEnvelope(root.toString())
        }
    }

    @Test
    fun `rejects zero or negative kdf costs`() {
        listOf("memoryKib" to 0, "iterations" to 0, "parallelism" to -1).forEach { (field, value) ->
            val root = JSONObject(envelope())
            root.getJSONObject("kdf").put(field, value)

            assertThrows(
                "should reject $field=$value",
                VaultBackupFormat.UnsupportedBackupException::class.java
            ) { VaultBackupFormat.readEnvelope(root.toString()) }
        }
    }

    // -- v1 compatibility ---------------------------------------------------------------------------

    @Test
    fun `reads a v1 envelope using the frozen kdf parameters`() {
        val v1 = JSONObject().apply {
            put("version", 1)
            put("exportedAt", 1L)
            put("salt", java.util.Base64.getEncoder().encodeToString(ByteArray(16) { 4 }))
            put("iv", java.util.Base64.getEncoder().encodeToString(ByteArray(12) { 5 }))
            put("encryptedVault", java.util.Base64.getEncoder().encodeToString(ByteArray(32) { 6 }))
        }.toString()

        val parsed = VaultBackupFormat.readEnvelope(v1)

        assertEquals(VaultBackupFormat.VERSION_1, parsed.version)
        assertEquals(KeyDerivation.MEMORY_COST_KIB, parsed.kdf.memoryKib)
        assertTrue(parsed.kdf.salt.contentEquals(ByteArray(16) { 4 }))
    }

    @Test
    fun `reads v1 rows`() {
        val json = """[{"id":"a","encryptedPayload":"AQID","iv":"BAUG","createdAt":1,"updatedAt":2}]"""

        val row = VaultBackupFormat.readLegacyRows(json).single()

        assertEquals("a", row.id)
        assertTrue(row.encryptedPayload.contentEquals(byteArrayOf(1, 2, 3)))
        assertEquals(2L, row.updatedAt)
    }
}
