package com.vaultguard.desktop.cloud

import org.json.JSONObject
import java.util.Base64

/**
 * Decoding of Firestore's REST document shape. Pure, so the awkward parts can be
 * exercised without a network or an account.
 *
 * The awkward parts are real. Firestore's REST representation wraps every field in a
 * one-key object naming its type, and `integerValue` arrives as a **JSON string** because
 * a 64-bit integer does not survive a JSON number. Reading it as a number silently
 * truncates timestamps; reading it as a string and parsing is correct.
 */
object FirestoreDocuments {

    /** The last path segment of `projects/…/documents/vaults/{uid}/credentials/{id}`. */
    fun documentId(name: String): String = name.substringAfterLast('/')

    fun fieldsOf(document: JSONObject): JSONObject =
        document.optJSONObject("fields") ?: JSONObject()

    fun string(fields: JSONObject, key: String): String? =
        fields.optJSONObject(key)?.let { value ->
            if (value.has("nullValue")) null else value.optString("stringValue").takeIf { it.isNotEmpty() }
        }

    fun base64(fields: JSONObject, key: String): ByteArray? =
        string(fields, key)?.let {
            try {
                Base64.getDecoder().decode(it)
            } catch (e: IllegalArgumentException) {
                null
            }
        }

    /** Firestore sends 64-bit integers as strings; a JSON number would lose precision. */
    fun long(fields: JSONObject, key: String, fallback: Long = 0L): Long {
        val value = fields.optJSONObject(key) ?: return fallback
        value.optString("integerValue").takeIf { it.isNotEmpty() }?.let { raw ->
            return raw.toLongOrNull() ?: fallback
        }
        if (value.has("doubleValue")) return value.optDouble("doubleValue").toLong()
        return fallback
    }

    fun boolean(fields: JSONObject, key: String, fallback: Boolean = false): Boolean =
        fields.optJSONObject(key)?.let {
            if (it.has("booleanValue")) it.optBoolean("booleanValue") else null
        } ?: fallback
}

/**
 * The remote vault document: everything a second device needs to reach the vault key, and
 * nothing that opens it without the master password.
 *
 * [vaultKey] is nullable because a vault written before the key indirection has no wrapped
 * key to publish. That case cannot be read here — the rows are sealed under a key this
 * device can never reach — and it is reported rather than presented as an empty vault
 * (#4, and #40 for why silence is not an option).
 */
data class RemoteVaultConfig(
    val salt: ByteArray,
    val verificationCiphertext: ByteArray,
    val verificationIv: ByteArray,
    val vaultKeyCiphertext: ByteArray?,
    val vaultKeyIv: ByteArray?
) {
    val hasWrappedVaultKey: Boolean get() = vaultKeyCiphertext != null && vaultKeyIv != null

    override fun equals(other: Any?) = this === other ||
        (other is RemoteVaultConfig && salt.contentEquals(other.salt))

    override fun hashCode() = salt.contentHashCode()
}

/** One credential row as it arrives from Firestore, still encrypted. */
data class RemoteCredentialRow(
    val id: String,
    val encryptedPayload: ByteArray,
    val iv: ByteArray,
    val createdAt: Long,
    val updatedAt: Long,
    val passwordChangedAt: Long,
    val isDeleted: Boolean,
    /**
     * Firestore's own `updateTime` for the document, as read.
     *
     * This is what makes an edit from here safe. Sent back as a write precondition, it
     * turns "overwrite whatever is there" into "overwrite only if nothing has touched it
     * since I read it" — so a desktop edit against a stale listing fails loudly instead of
     * silently discarding whatever the phone wrote in the meantime.
     *
     * Null for a row this client built rather than read; such a row cannot be used to
     * update anything.
     */
    val updateTime: String? = null
) {
    override fun equals(other: Any?) = this === other || (other is RemoteCredentialRow && id == other.id)
    override fun hashCode() = id.hashCode()
}

/**
 * Maps Firestore documents onto the shapes above. Field names are pinned against
 * `docs/DATA-FORMATS.md` and must match `FirebaseSyncService` exactly — a rename on one
 * side reads as an empty or unopenable vault on the other.
 */
object RemoteVaultCodec {

    const val FIELD_SALT = "salt"
    const val FIELD_VERIFICATION_CIPHERTEXT = "verificationCiphertext"
    const val FIELD_VERIFICATION_IV = "verificationIv"
    const val FIELD_VAULT_KEY_CIPHERTEXT = "vaultKeyCiphertext"
    const val FIELD_VAULT_KEY_IV = "vaultKeyIv"

    const val FIELD_ENCRYPTED_PAYLOAD = "encryptedPayload"
    const val FIELD_IV = "iv"
    const val FIELD_CREATED_AT = "createdAt"
    const val FIELD_UPDATED_AT = "updatedAt"
    const val FIELD_PASSWORD_CHANGED_AT = "passwordChangedAt"
    const val FIELD_IS_DELETED = "isDeleted"

    /** @return null when the document exists but carries no usable vault configuration. */
    fun readVaultConfig(document: JSONObject): RemoteVaultConfig? {
        val fields = FirestoreDocuments.fieldsOf(document)
        val salt = FirestoreDocuments.base64(fields, FIELD_SALT) ?: return null
        val ciphertext = FirestoreDocuments.base64(fields, FIELD_VERIFICATION_CIPHERTEXT) ?: return null
        val iv = FirestoreDocuments.base64(fields, FIELD_VERIFICATION_IV) ?: return null

        return RemoteVaultConfig(
            salt = salt,
            verificationCiphertext = ciphertext,
            verificationIv = iv,
            vaultKeyCiphertext = FirestoreDocuments.base64(fields, FIELD_VAULT_KEY_CIPHERTEXT),
            vaultKeyIv = FirestoreDocuments.base64(fields, FIELD_VAULT_KEY_IV)
        )
    }

    /** @return null when the document is missing the ciphertext or IV it needs to be a row. */
    fun readCredentialRow(document: JSONObject): RemoteCredentialRow? {
        val fields = FirestoreDocuments.fieldsOf(document)
        val payload = FirestoreDocuments.base64(fields, FIELD_ENCRYPTED_PAYLOAD) ?: return null
        val iv = FirestoreDocuments.base64(fields, FIELD_IV) ?: return null

        val createdAt = FirestoreDocuments.long(fields, FIELD_CREATED_AT)
        val updatedAt = FirestoreDocuments.long(fields, FIELD_UPDATED_AT, createdAt)

        return RemoteCredentialRow(
            id = FirestoreDocuments.documentId(document.optString("name")),
            encryptedPayload = payload,
            iv = iv,
            createdAt = createdAt,
            updatedAt = updatedAt,
            // Same fallback the schema migration and the backup reader use.
            passwordChangedAt = FirestoreDocuments.long(fields, FIELD_PASSWORD_CHANGED_AT, updatedAt),
            isDeleted = FirestoreDocuments.boolean(fields, FIELD_IS_DELETED),
            updateTime = document.optString("updateTime").takeIf { it.isNotEmpty() }
        )
    }
}
