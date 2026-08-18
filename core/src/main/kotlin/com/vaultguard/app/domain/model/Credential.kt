package com.vaultguard.app.domain.model

data class Credential(
    val id: String,
    val siteName: String,
    val appName: String = "",
    val url: String = "",
    val username: String = "",
    val password: String = "",
    val notes: String = "",
    val category: String = "",
    val tags: List<String> = emptyList(),
    val isPinned: Boolean = false,
    val linkedPackages: List<String> = emptyList(),
    val linkedDomains: List<String> = emptyList(),
    val createdAt: Long = System.currentTimeMillis(),
    /** Any write to this entry, including a pin toggle. The sync clock (#58). */
    val updatedAt: Long = System.currentTimeMillis(),
    /** When the password itself last changed — see CredentialEntity (finding #29). */
    val passwordChangedAt: Long = System.currentTimeMillis(),
    /**
     * When the entry's *content* last changed, as distinct from when the row was written.
     *
     * [updatedAt] cannot answer this: sync pushes a row when `updatedAt > syncedAt`, and
     * the pin state travels in the encrypted payload, so pinning genuinely must move it.
     * The detail screen was reading that clock and reporting an entry as updated today
     * because it had been pinned (finding #58). Pinning is not a change to the credential.
     */
    val contentChangedAt: Long = System.currentTimeMillis()
) {
    /** Display name: prefers appName, falls back to siteName */
    val displayName: String get() = appName.ifEmpty { siteName }
}

object CategoryPresets {
    val list = listOf(
        "Social", "Email", "Finance", "Shopping", "Work",
        "Entertainment", "Gaming", "Development", "Education", "Other"
    )
}
