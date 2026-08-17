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
    val updatedAt: Long = System.currentTimeMillis()
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
