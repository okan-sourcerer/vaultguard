package com.vaultguard.app.domain.model

/**
 * Lightweight credential for list display — password is intentionally excluded
 * so it never sits in memory while browsing the vault.
 */
data class CredentialSummary(
    val id: String,
    val siteName: String,
    val appName: String = "",
    val url: String = "",
    val username: String = "",
    val category: String = "",
    val tags: List<String> = emptyList(),
    val isPinned: Boolean = false,
    val linkedPackages: List<String> = emptyList(),
    val linkedDomains: List<String> = emptyList(),
    val createdAt: Long = 0L,
    val updatedAt: Long = 0L
) {
    val displayName: String get() = appName.ifEmpty { siteName }
}

fun Credential.toSummary() = CredentialSummary(
    id = id,
    siteName = siteName,
    appName = appName,
    url = url,
    username = username,
    category = category,
    tags = tags,
    isPinned = isPinned,
    linkedPackages = linkedPackages,
    linkedDomains = linkedDomains,
    createdAt = createdAt,
    updatedAt = updatedAt
)
