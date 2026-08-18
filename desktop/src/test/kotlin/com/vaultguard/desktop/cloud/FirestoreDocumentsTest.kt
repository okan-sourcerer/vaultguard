package com.vaultguard.desktop.cloud

import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

class FirestoreDocumentsTest {

    private fun fields(json: String) = JSONObject(json)

    @Test
    fun `an id is the last segment of the document path`() {
        val name = "projects/passwords/databases/(default)/documents/vaults/uid-1/credentials/abc-123"
        assertEquals("abc-123", FirestoreDocuments.documentId(name))
    }

    @Test
    fun `a 64-bit timestamp survives being sent as a string`() {
        // Firestore sends integerValue as a JSON *string* precisely because a 64-bit
        // integer does not survive a JSON number. Reading it as a number truncates, and a
        // truncated updatedAt is a row that sorts and syncs wrongly for ever.
        val json = fields("""{"updatedAt":{"integerValue":"1755000000123"}}""")
        assertEquals(1_755_000_000_123L, FirestoreDocuments.long(json, "updatedAt"))
    }

    @Test
    fun `a missing integer falls back`() {
        assertEquals(42L, FirestoreDocuments.long(fields("{}"), "updatedAt", fallback = 42L))
    }

    @Test
    fun `a non-numeric integer falls back rather than throwing`() {
        val json = fields("""{"updatedAt":{"integerValue":"not a number"}}""")
        assertEquals(7L, FirestoreDocuments.long(json, "updatedAt", fallback = 7L))
    }

    @Test
    fun `base64 fields decode to their bytes`() {
        val bytes = byteArrayOf(1, 2, 3, 4, 5)
        val encoded = Base64.getEncoder().encodeToString(bytes)
        val json = fields("""{"iv":{"stringValue":"$encoded"}}""")

        assertArrayEquals(bytes, FirestoreDocuments.base64(json, "iv"))
    }

    @Test
    fun `a damaged base64 field reads as absent rather than throwing`() {
        val json = fields("""{"iv":{"stringValue":"not base64 at all !!"}}""")
        assertNull(FirestoreDocuments.base64(json, "iv"))
    }

    @Test
    fun `a null value reads as absent`() {
        val json = fields("""{"vaultKeyIv":{"nullValue":null}}""")
        assertNull(FirestoreDocuments.string(json, "vaultKeyIv"))
    }

    @Test
    fun `booleans read, and default when missing`() {
        assertTrue(FirestoreDocuments.boolean(fields("""{"isDeleted":{"booleanValue":true}}"""), "isDeleted"))
        assertFalse(FirestoreDocuments.boolean(fields("{}"), "isDeleted"))
    }

    @Test
    fun `a vault config without a wrapped key is readable but flagged`() {
        val salt = Base64.getEncoder().encodeToString(ByteArray(16) { 1 })
        val blob = Base64.getEncoder().encodeToString(ByteArray(32) { 2 })
        val document = JSONObject(
            """
            {"name":"projects/p/databases/(default)/documents/vaults/uid",
             "fields":{
               "salt":{"stringValue":"$salt"},
               "verificationCiphertext":{"stringValue":"$blob"},
               "verificationIv":{"stringValue":"$blob"}}}
            """.trimIndent()
        )

        val config = RemoteVaultCodec.readVaultConfig(document)!!

        // Readable, but nothing here can reach the rows. Saying so is the whole point of
        // the flag: #4 was a vault that verified and could not be opened.
        assertFalse(config.hasWrappedVaultKey)
    }

    @Test
    fun `a vault document missing its salt is not a config`() {
        val document = JSONObject("""{"name":"vaults/uid","fields":{}}""")
        assertNull(RemoteVaultCodec.readVaultConfig(document))
    }

    @Test
    fun `a credential row reads its metadata`() {
        val payload = Base64.getEncoder().encodeToString(ByteArray(48) { 3 })
        val iv = Base64.getEncoder().encodeToString(ByteArray(12) { 4 })
        val document = JSONObject(
            """
            {"name":"projects/p/databases/(default)/documents/vaults/uid/credentials/row-9",
             "fields":{
               "encryptedPayload":{"stringValue":"$payload"},
               "iv":{"stringValue":"$iv"},
               "createdAt":{"integerValue":"1700000000000"},
               "updatedAt":{"integerValue":"1710000000000"},
               "isDeleted":{"booleanValue":false}}}
            """.trimIndent()
        )

        val row = RemoteVaultCodec.readCredentialRow(document)!!

        assertEquals("row-9", row.id)
        assertEquals(1_700_000_000_000L, row.createdAt)
        assertEquals(1_710_000_000_000L, row.updatedAt)
        // Absent in this document; falls back to updatedAt, as the migration and the backup
        // reader both do.
        assertEquals(1_710_000_000_000L, row.passwordChangedAt)
        assertFalse(row.isDeleted)
    }

    @Test
    fun `a row without ciphertext is not a row`() {
        val document = JSONObject("""{"name":"vaults/uid/credentials/x","fields":{"iv":{"stringValue":"AAAA"}}}""")
        assertNull(RemoteVaultCodec.readCredentialRow(document))
    }
}
