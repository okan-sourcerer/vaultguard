package com.vaultguard.app.domain.repository

import com.vaultguard.app.domain.model.Credential

/**
 * The result of reading the vault, including what could *not* be read.
 *
 * Exists because the repository used to answer with a plain `List` built by `mapNotNull`
 * over a `catch (e: Exception) { null }` — so a row that failed to decrypt simply vanished
 * and an entirely unreadable vault was indistinguishable from an empty one (finding #40).
 *
 * That one swallow is why four separate data-loss bugs all presented to the user as
 * "No credentials yet. Tap + to add one." Callers now have to look at [undecryptableIds]
 * to render honestly, and the type makes ignoring it a visible choice.
 */
data class VaultSnapshot<T>(
    val items: List<T>,
    val undecryptableIds: List<String> = emptyList(),
    val isLocked: Boolean = false
) {
    val hasUndecryptable: Boolean get() = undecryptableIds.isNotEmpty()

    val undecryptableCount: Int get() = undecryptableIds.size

    /** True only when the vault was readable and genuinely holds nothing. */
    val isGenuinelyEmpty: Boolean get() = items.isEmpty() && !hasUndecryptable && !isLocked

    fun <R> map(transform: (T) -> R): VaultSnapshot<R> =
        VaultSnapshot(items.map(transform), undecryptableIds, isLocked)

    companion object {
        fun <T> locked(): VaultSnapshot<T> = VaultSnapshot(emptyList(), emptyList(), isLocked = true)
    }
}

/**
 * The result of looking up one credential.
 *
 * Separates the three outcomes a nullable return conflated: the row is absent, the row
 * exists but cannot be decrypted, and the vault is locked. The detail and edit screens
 * rendered a blank page or span forever because they could not tell these apart
 * (finding #38).
 */
sealed interface CredentialLookup {
    data class Found(val credential: Credential) : CredentialLookup
    data object NotFound : CredentialLookup
    data object Locked : CredentialLookup
    data class Undecryptable(val id: String, val detail: String?) : CredentialLookup

    /** Convenience for the callers that genuinely only care about the happy path. */
    val credentialOrNull: Credential? get() = (this as? Found)?.credential
}
