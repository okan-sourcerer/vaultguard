package com.vaultguard.app.domain.model

/**
 * Lightweight credential for list display — the password is intentionally excluded, so
 * browsing the vault does not hold every password in memory at once.
 *
 * Note the limit of that claim: the detail screen loads a full [Credential] and keeps its
 * plaintext password in UI state for as long as it is open. This type narrows the exposure
 * to one credential at a time; it does not eliminate it.
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
