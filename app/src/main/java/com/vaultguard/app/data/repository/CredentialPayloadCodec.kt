package com.vaultguard.app.data.repository

import com.vaultguard.app.domain.model.Credential
import org.json.JSONArray
import org.json.JSONObject

/**
 * Serialises the plaintext half of a credential — everything that gets sealed into
 * [com.vaultguard.app.data.local.db.entity.CredentialEntity.encryptedPayload].
 *
 * Identity and timestamps deliberately live on the row, not in the payload, so they can
 * be read and ordered without decrypting anything.
 *
 * The wire format is specified in `docs/DATA-FORMATS.md`. Reads use `opt*` with defaults
 * throughout, so adding a field stays backward-compatible with payloads already in the
 * vault; removing or renaming one does not.
 *
 * Extracted from `CredentialRepositoryImpl` unchanged so the mapping can be tested on the
 * host JVM. `VaultAutofillService` and `AutofillAuthActivity` still parse this JSON by
 * hand (finding #11) and should be moved onto this codec in chunk 8.
 */
object CredentialPayloadCodec {

    fun encode(credential: Credential): String =
        JSONObject().apply {
            put("siteName", credential.siteName)
            put("appName", credential.appName)
            put("url", credential.url)
            put("username", credential.username)
            put("password", credential.password)
            put("notes", credential.notes)
            put("category", credential.category)
            put("tags", JSONArray(credential.tags))
            put("isPinned", credential.isPinned)
            put("linkedPackages", JSONArray(credential.linkedPackages))
            put("linkedDomains", JSONArray(credential.linkedDomains))
            // In the payload rather than on the row, unlike the other timestamps: nothing
            // sorts or filters by it, and a new column would need a migration where a new
            // payload field does not (#58).
            put("contentChangedAt", credential.contentChangedAt)
        }.toString()

    fun decode(
        json: String,
        id: String,
        createdAt: Long,
        updatedAt: Long,
        passwordChangedAt: Long = updatedAt
    ): Credential {
        val obj = JSONObject(json)
        return Credential(
            id = id,
            siteName = obj.optString("siteName", ""),
            appName = obj.optString("appName", ""),
            url = obj.optString("url", ""),
            username = obj.optString("username", ""),
            password = obj.optString("password", ""),
            notes = obj.optString("notes", ""),
            category = obj.optString("category", ""),
            tags = obj.optJSONArray("tags").toStringList(),
            isPinned = obj.optBoolean("isPinned", false),
            linkedPackages = obj.optJSONArray("linkedPackages").toStringList(),
            linkedDomains = obj.optJSONArray("linkedDomains").toStringList(),
            createdAt = createdAt,
            updatedAt = updatedAt,
            passwordChangedAt = passwordChangedAt,
            // Entries written before this field existed fall back to the row clock, which
            // is what they were already being displayed as.
            contentChangedAt = obj.optLong("contentChangedAt", updatedAt)
        )
    }

    private fun JSONArray?.toStringList(): List<String> {
        if (this == null) return emptyList()
        return (0 until length()).map { getString(it) }
    }
}
