package com.vaultguard.app.domain.usecase.migration

import android.content.Context
import android.net.Uri
import com.vaultguard.app.domain.repository.CredentialRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import javax.inject.Inject

/**
 * TEMPORARY — CLEARTEXT MIGRATION AID. Scheduled for deletion.
 *
 * Writes every credential, passwords included, to an **unencrypted** CSV file. This exists
 * so the vault can be moved into another password manager while VaultGuard's encrypted
 * backup format is broken (finding #3) — a v1 backup cannot be restored after the salt
 * changes, which makes it useless in exactly the scenario a backup is for.
 *
 * Delete this whole `migration` package once the move is done and backup v2 has landed.
 *
 * Runs off the main thread: decrypting the entire vault is O(n) AES-GCM operations.
 */
class ExportCleartextVaultUseCase @Inject constructor(
    @ApplicationContext private val context: Context,
    private val credentialRepository: CredentialRepository
) {
    /** @return the number of credentials written. */
    suspend operator fun invoke(uri: Uri): Int = withContext(Dispatchers.Default) {
        val snapshot = credentialRepository.getAllCredentials().first()

        // Refuse to produce a backup that silently omits entries. Both checks exist
        // because a reassuring-looking CSV is the worst possible failure mode here: the
        // user would delete their other copies on the strength of it.
        check(!snapshot.isLocked) { "The vault is locked. Unlock it before exporting." }
        check(!snapshot.hasUndecryptable) {
            "Refusing to export: ${snapshot.undecryptableCount} of " +
                "${snapshot.items.size + snapshot.undecryptableCount} entries could not be " +
                "decrypted, so this backup would be incomplete. Resolve that first."
        }
        check(snapshot.items.isNotEmpty()) {
            "Refusing to export an empty vault. Either it is genuinely empty, or it is " +
                "unreadable — check before trusting this as a backup."
        }

        val credentials = snapshot.items

        val csv = CleartextCsv.write(credentials)

        withContext(Dispatchers.IO) {
            context.contentResolver.openOutputStream(uri)?.use { stream ->
                stream.write(csv.toByteArray(Charsets.UTF_8))
                stream.flush()
            } ?: error("Cannot open output stream for $uri")
        }

        credentials.size
    }
}
