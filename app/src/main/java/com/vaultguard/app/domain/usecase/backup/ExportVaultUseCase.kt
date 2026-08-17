package com.vaultguard.app.domain.usecase.backup

import android.content.Context
import android.net.Uri
import com.vaultguard.app.di.CryptoDispatcher
import com.vaultguard.app.domain.repository.CredentialRepository
import com.vaultguard.app.security.CryptoManager
import com.vaultguard.app.security.KeyDerivation
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import javax.inject.Inject

/**
 * Writes an encrypted backup in format v2.
 *
 * The backup password is supplied by the user and is **independent of the master
 * password**, so a backup stays valid after the master password changes — and, since the
 * vault-key indirection, the session key is a random value that could not have been
 * derived from a recorded salt anyway.
 */
class ExportVaultUseCase @Inject constructor(
    @ApplicationContext private val context: Context,
    private val credentialRepository: CredentialRepository,
    private val cryptoManager: CryptoManager,
    private val keyDerivation: KeyDerivation,
    @CryptoDispatcher private val cryptoDispatcher: CoroutineDispatcher
) {
    /** @return the number of credentials written. */
    suspend operator fun invoke(uri: Uri, backupPassword: CharArray): Int {
        val snapshot = credentialRepository.getAllCredentials().first()

        // A backup that silently omits entries is the worst possible outcome here: the
        // user may delete their other copies on the strength of it.
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

        val document = withContext(cryptoDispatcher) {
            val salt = keyDerivation.generateSalt()
            val key = keyDerivation.deriveKey(backupPassword, salt)

            val plaintext = VaultBackupFormat.writeEntries(snapshot.items)
                .toByteArray(Charsets.UTF_8)
            val sealed = cryptoManager.encrypt(plaintext, key)
            plaintext.fill(0)

            VaultBackupFormat.writeEnvelope(
                kdf = VaultBackupFormat.KdfParams(salt),
                iv = sealed.iv,
                ciphertext = sealed.ciphertext
            )
        }

        withContext(Dispatchers.IO) {
            context.contentResolver.openOutputStream(uri)?.use { stream ->
                stream.write(document.toByteArray(Charsets.UTF_8))
                stream.flush()
            } ?: error("Cannot open output stream for $uri")
        }

        return snapshot.items.size
    }
}
