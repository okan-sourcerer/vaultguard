package com.vaultguard.desktop.cloud

import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FirestoreWritesTest {

    private val row = RemoteCredentialRow(
        id = "row-1",
        encryptedPayload = ByteArray(64) { it.toByte() },
        iv = ByteArray(12) { (it * 3).toByte() },
        createdAt = 1_700_000_000_000L,
        updatedAt = 1_755_123_456_789L,
        passwordChangedAt = 1_705_000_000_000L,
        isDeleted = false
    )

    private val documentName =
        "projects/p/databases/(default)/documents/vaults/uid/credentials/row-1"

    @Test
    fun `integers are written as strings, not numbers`() {
        val value = FirestoreWrites.integerValue(1_755_123_456_789L)

        // The mirror of the reading trap. A JSON number here is accepted by Firestore and
        // loses precision on large values, which for a timestamp means a row that sorts
        // and syncs wrongly for ever.
        assertTrue(value.get("integerValue") is String)
        assertEquals("1755123456789", value.getString("integerValue"))
    }

    @Test
    fun `a written row reads back identically`() {
        // The encoder and the decoder are inverses, and this is what holds them that way.
        // They are the two halves of the same wire format and are edited months apart.
        val document = JSONObject()
            .put("name", documentName)
            .put("fields", FirestoreWrites.credentialFields(row))

        val decoded = RemoteVaultCodec.readCredentialRow(document)!!

        assertEquals(row.id, decoded.id)
        assertArrayEquals(row.encryptedPayload, decoded.encryptedPayload)
        assertArrayEquals(row.iv, decoded.iv)
        assertEquals(row.createdAt, decoded.createdAt)
        assertEquals(row.updatedAt, decoded.updatedAt)
        assertEquals(row.passwordChangedAt, decoded.passwordChangedAt)
        assertEquals(row.isDeleted, decoded.isDeleted)
    }

    @Test
    fun `a create write refuses to replace an existing document`() {
        val write = FirestoreWrites.createCredentialWrite(documentName, row)

        // Server-enforced. For a freshly generated UUID this should never fire, which is
        // exactly why it is worth having: if it does, something is wrong and stopping is
        // the only safe answer.
        assertFalse(write.getJSONObject("currentDocument").getBoolean("exists"))
    }

    @Test
    fun `a create write carries the server timestamp transform`() {
        val write = FirestoreWrites.createCredentialWrite(documentName, row)
        val transforms = write.getJSONArray("updateTransforms")

        assertEquals(1, transforms.length())
        val transform = transforms.getJSONObject(0)
        assertEquals("serverUpdatedAt", transform.getString("fieldPath"))
        assertEquals("REQUEST_TIME", transform.getString("setToServerValue"))
    }

    @Test
    fun `the transform is not written as an ordinary field`() {
        // serverUpdatedAt must be the server's value, never the writing device's. If it
        // appeared in `fields` too, the write would set it twice and a device clock could
        // win -- which is finding #20 reintroduced through the back door.
        val fields = FirestoreWrites.credentialFields(row)
        assertFalse(fields.has("serverUpdatedAt"))
    }

    @Test
    fun `a commit body wraps the writes`() {
        val body = FirestoreWrites.commitBody(
            listOf(FirestoreWrites.createCredentialWrite(documentName, row))
        )
        assertEquals(1, body.getJSONArray("writes").length())
    }

    @Test
    fun `the document name ends with the row id`() {
        val write = FirestoreWrites.createCredentialWrite(documentName, row)
        val name = write.getJSONObject("update").getString("name")

        assertEquals(row.id, FirestoreDocuments.documentId(name))
    }

    // -- Updating and deleting -----------------------------------------------------------

    private val readAt = "2026-08-18T10:11:12.131415Z"

    @Test
    fun `an update is conditional on the version that was read`() {
        val write = FirestoreWrites.updateCredentialWrite(documentName, row, readAt)

        // Without this the desktop would overwrite whatever the phone wrote since the
        // listing was fetched, and the loser would never know.
        assertEquals(readAt, write.getJSONObject("currentDocument").getString("updateTime"))
    }

    @Test
    fun `an update does not carry the create-only precondition`() {
        val write = FirestoreWrites.updateCredentialWrite(documentName, row, readAt)
        assertFalse(write.getJSONObject("currentDocument").has("exists"))
    }

    @Test
    fun `an update rewrites the whole document`() {
        // No updateMask: an edit changes the ciphertext, the IV and the clocks together,
        // and a partial write would leave a payload that no longer matches its IV.
        val write = FirestoreWrites.updateCredentialWrite(documentName, row, readAt)
        assertFalse(write.has("updateMask"))
        assertTrue(write.getJSONObject("update").getJSONObject("fields").has("encryptedPayload"))
    }

    @Test
    fun `a delete touches only the tombstone fields`() {
        val write = FirestoreWrites.tombstoneWrite(documentName, 1_760_000_000_000L, readAt)
        val paths = write.getJSONObject("updateMask").getJSONArray("fieldPaths")
        val listed = (0 until paths.length()).map { paths.getString(it) }.toSet()

        assertEquals(setOf("isDeleted", "updatedAt"), listed)
    }

    @Test
    fun `a delete leaves the ciphertext alone`() {
        val write = FirestoreWrites.tombstoneWrite(documentName, 1_760_000_000_000L, readAt)
        val fields = write.getJSONObject("update").getJSONObject("fields")

        // A soft delete, as on the phone. The payload has to survive or an undo there has
        // nothing to restore, and the 30-day purge stops being the only thing that removes
        // data.
        assertFalse(fields.has("encryptedPayload"))
        assertFalse(fields.has("iv"))
        assertTrue(fields.getJSONObject("isDeleted").getBoolean("booleanValue"))
    }

    @Test
    fun `a delete is conditional on the version that was read`() {
        val write = FirestoreWrites.tombstoneWrite(documentName, 1L, readAt)
        assertEquals(readAt, write.getJSONObject("currentDocument").getString("updateTime"))
    }

    @Test
    fun `every write sets the server timestamp`() {
        val writes = listOf(
            FirestoreWrites.createCredentialWrite(documentName, row),
            FirestoreWrites.updateCredentialWrite(documentName, row, readAt),
            FirestoreWrites.tombstoneWrite(documentName, 1L, readAt)
        )

        // A row the phone cannot see is a row that does not exist to it: its pull orders by
        // this field and Firestore omits documents that lack an ordered field. A delete
        // that never reaches the phone is the worst of the three.
        for (write in writes) {
            val transform = write.getJSONArray("updateTransforms").getJSONObject(0)
            assertEquals("serverUpdatedAt", transform.getString("fieldPath"))
            assertEquals("REQUEST_TIME", transform.getString("setToServerValue"))
        }
    }

    @Test
    fun `a read row carries the update time it was read at`() {
        val document = JSONObject()
            .put("name", documentName)
            .put("updateTime", readAt)
            .put("fields", FirestoreWrites.credentialFields(row))

        assertEquals(readAt, RemoteVaultCodec.readCredentialRow(document)!!.updateTime)
    }

    @Test
    fun `a row built rather than read has no update time`() {
        assertNull(row.updateTime)
    }
}
