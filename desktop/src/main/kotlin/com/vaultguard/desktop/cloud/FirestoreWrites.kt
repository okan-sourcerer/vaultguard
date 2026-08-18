package com.vaultguard.desktop.cloud

import org.json.JSONArray
import org.json.JSONObject
import java.util.Base64

/**
 * Building the REST representation of a credential document. Pure, and the exact inverse
 * of [RemoteVaultCodec.readCredentialRow], so a round-trip test can hold the two together.
 *
 * The encoding traps are the same ones reading has, in mirror image: a 64-bit integer has
 * to go out as a **string**, because Firestore's `integerValue` is a string field. Sending
 * it as a JSON number is accepted and silently loses precision on large values.
 */
object FirestoreWrites {

    /**
     * The server timestamp `serverUpdatedAt` is set from.
     *
     * Not optional, and not cosmetic. The phone pulls with an `orderBy` on this field, and
     * Firestore excludes documents that lack an ordered field from the results entirely —
     * so a row written without it would be invisible to the phone for ever while looking
     * perfectly fine in the console.
     */
    const val SERVER_TIME_TRANSFORM = "REQUEST_TIME"

    fun stringValue(value: String): JSONObject = JSONObject().put("stringValue", value)

    fun base64Value(value: ByteArray): JSONObject =
        stringValue(Base64.getEncoder().encodeToString(value))

    /** As a string: `integerValue` is a string field, and a JSON number would truncate. */
    fun integerValue(value: Long): JSONObject = JSONObject().put("integerValue", value.toString())

    fun booleanValue(value: Boolean): JSONObject = JSONObject().put("booleanValue", value)

    /** The `fields` object for one credential document. */
    fun credentialFields(row: RemoteCredentialRow): JSONObject = JSONObject()
        .put(RemoteVaultCodec.FIELD_ENCRYPTED_PAYLOAD, base64Value(row.encryptedPayload))
        .put(RemoteVaultCodec.FIELD_IV, base64Value(row.iv))
        .put(RemoteVaultCodec.FIELD_CREATED_AT, integerValue(row.createdAt))
        .put(RemoteVaultCodec.FIELD_UPDATED_AT, integerValue(row.updatedAt))
        .put(RemoteVaultCodec.FIELD_PASSWORD_CHANGED_AT, integerValue(row.passwordChangedAt))
        .put(RemoteVaultCodec.FIELD_IS_DELETED, booleanValue(row.isDeleted))

    /**
     * One `:commit` write that creates a credential document.
     *
     * Two things make it a create rather than a blind write:
     *
     * - `currentDocument.exists = false` is a precondition the server enforces. If a
     *   document with this id somehow already exists, the commit fails rather than
     *   overwriting a credential — which for a fresh UUID means something is badly wrong
     *   and stopping is the only safe answer.
     * - `updateTransforms` applies the server timestamp in the same operation, so there is
     *   no window in which the row exists without one.
     */
    fun createCredentialWrite(documentName: String, row: RemoteCredentialRow): JSONObject =
        JSONObject()
            .put(
                "update",
                JSONObject()
                    .put("name", documentName)
                    .put("fields", credentialFields(row))
            )
            .put("updateTransforms", serverTimeTransform())
            .put("currentDocument", JSONObject().put("exists", false))

    /**
     * Replaces a credential document, but only if nothing has touched it since it was read.
     *
     * `currentDocument.updateTime` is optimistic concurrency, enforced by the server. It is
     * what makes editing from a second client safe at all: the desktop holds a snapshot,
     * the phone may have written since, and without this precondition the edit would
     * silently discard whatever arrived in between. With it, the commit fails and the user
     * is told to refresh.
     *
     * The phone reaches the same outcome by a different route — `SyncMerge` writes the
     * remote copy alongside the local one rather than choosing — because it is reconciling
     * after the fact. This client is writing live and can simply refuse.
     */
    fun updateCredentialWrite(
        documentName: String,
        row: RemoteCredentialRow,
        expectedUpdateTime: String
    ): JSONObject = JSONObject()
        .put(
            "update",
            JSONObject()
                .put("name", documentName)
                .put("fields", credentialFields(row))
        )
        .put("updateTransforms", serverTimeTransform())
        .put("currentDocument", JSONObject().put("updateTime", expectedUpdateTime))

    /**
     * Flips a row to a tombstone, touching only the two fields that change.
     *
     * A soft delete, exactly as the phone does it: the ciphertext stays, so an undo on the
     * phone still has something to restore and the 30-day tombstone purge remains the only
     * thing that removes data.
     *
     * The `updateMask` is what keeps this to two fields. A commit without one replaces the
     * whole document, which here would mean rewriting the payload from a decrypted copy for
     * no reason — and getting that wrong would destroy the entry rather than hide it.
     */
    fun tombstoneWrite(
        documentName: String,
        updatedAt: Long,
        expectedUpdateTime: String
    ): JSONObject = JSONObject()
        .put(
            "update",
            JSONObject()
                .put("name", documentName)
                .put(
                    "fields",
                    JSONObject()
                        .put(RemoteVaultCodec.FIELD_IS_DELETED, booleanValue(true))
                        .put(RemoteVaultCodec.FIELD_UPDATED_AT, integerValue(updatedAt))
                )
        )
        .put(
            "updateMask",
            JSONObject().put(
                "fieldPaths",
                JSONArray()
                    .put(RemoteVaultCodec.FIELD_IS_DELETED)
                    .put(RemoteVaultCodec.FIELD_UPDATED_AT)
            )
        )
        .put("updateTransforms", serverTimeTransform())
        .put("currentDocument", JSONObject().put("updateTime", expectedUpdateTime))

    private fun serverTimeTransform(): JSONArray = JSONArray().put(
        JSONObject()
            .put("fieldPath", "serverUpdatedAt")
            .put("setToServerValue", SERVER_TIME_TRANSFORM)
    )

    fun commitBody(writes: List<JSONObject>): JSONObject =
        JSONObject().put("writes", JSONArray(writes))
}
