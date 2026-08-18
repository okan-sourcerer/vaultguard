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
            .put(
                "updateTransforms",
                JSONArray().put(
                    JSONObject()
                        .put("fieldPath", "serverUpdatedAt")
                        .put("setToServerValue", SERVER_TIME_TRANSFORM)
                )
            )
            .put("currentDocument", JSONObject().put("exists", false))

    fun commitBody(writes: List<JSONObject>): JSONObject =
        JSONObject().put("writes", JSONArray(writes))
}
