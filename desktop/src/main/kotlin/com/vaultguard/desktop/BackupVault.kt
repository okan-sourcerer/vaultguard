package com.vaultguard.desktop

import com.vaultguard.app.data.repository.CredentialPayloadCodec
import com.vaultguard.app.domain.model.Credential
import com.vaultguard.app.domain.usecase.backup.VaultBackupFormat
import com.vaultguard.app.security.CryptoManager
import com.vaultguard.app.security.EncryptedData
import com.vaultguard.app.security.KeyDerivation
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import javax.crypto.SecretKey

class WrongBackupPasswordException :
    Exception("Wrong backup password, or the file is damaged.")

/**
 * The in-memory vault the CLI edits: a list of credentials read from a backup file and
 * written back to one.
 *
 * Everything cryptographic here comes from `:core` — the same [KeyDerivation],
 * [CryptoManager], [VaultBackupFormat] and [CredentialPayloadCodec] the phone uses. That
 * is the whole point of the module: nothing about the format is restated on this side, so
 * there is nothing to drift.
 */
class BackupVault(initial: List<Credential> = emptyList()) {

    private val entries = initial.toMutableList()

    val credentials: List<Credential> get() = entries.toList()
    val size: Int get() = entries.size

    fun add(credential: Credential) {
        entries.add(credential)
    }
}

/**
 * Reads and writes format v2 backup files.
 *
 * ## On the `CharArray`
 *
 * [KeyDerivation.deriveKey] zeroes the array it is given, so both functions here **consume**
 * the password they are passed and a caller needing two derivations must hand over two
 * separate copies. That is the documented contract in `docs/SECURITY.md`, not an accident,
 * and reusing the array silently derives from all-zeroes.
 */
object BackupFile {

    private val keyDerivation = KeyDerivation()
    private val cryptoManager = CryptoManager()

    /**
     * Opens a backup. Consumes [password].
     *
     * Both formats are accepted, for the same reason the Android importer accepts both: a
     * v1 file's envelope and its inner payloads were sealed under the same key, so the
     * backup password opens both layers.
     */
    fun read(file: File, password: CharArray): BackupVault {
        val text = file.readText(Charsets.UTF_8)
        val envelope = VaultBackupFormat.readEnvelope(text)

        val key = keyDerivation.deriveKey(
            password,
            envelope.kdf.salt,
            envelope.kdf.memoryKib,
            envelope.kdf.iterations,
            envelope.kdf.parallelism
        )

        val decrypted = try {
            cryptoManager.decrypt(EncryptedData(envelope.ciphertext, envelope.iv), key)
        } catch (e: Exception) {
            throw WrongBackupPasswordException()
        }

        val json = String(decrypted, Charsets.UTF_8)
        decrypted.fill(0)

        val credentials = when (envelope.version) {
            VaultBackupFormat.VERSION_2 -> VaultBackupFormat.readEntries(json).map { it.credential }
            VaultBackupFormat.VERSION_1 -> openLegacyRows(json, key)
            else -> error("unreachable: readEnvelope rejects unknown versions")
        }

        return BackupVault(credentials)
    }

    /**
     * Writes [vault] to [file] under a freshly derived key. Consumes [password].
     *
     * A new salt every time, matching `ExportVaultUseCase`. Reusing the salt from the file
     * that was opened would have saved a derivation and quietly weakened the format.
     */
    fun write(file: File, vault: BackupVault, password: CharArray) {
        // The Android exporter refuses for the same reason: someone may delete their other
        // copies on the strength of a backup that turns out to hold nothing.
        require(vault.size > 0) { "Refusing to write an empty backup." }

        val salt = keyDerivation.generateSalt()
        val key = keyDerivation.deriveKey(password, salt)

        val plaintext = VaultBackupFormat.writeEntries(vault.credentials).toByteArray(Charsets.UTF_8)
        val sealed = cryptoManager.encrypt(plaintext, key)
        plaintext.fill(0)

        val document = VaultBackupFormat.writeEnvelope(
            kdf = VaultBackupFormat.KdfParams(salt),
            iv = sealed.iv,
            ciphertext = sealed.ciphertext
        )

        writeAtomically(file, document)
    }

    /**
     * Writes beside the target and moves the result into place, so a crash or a full disk
     * leaves the previous backup intact rather than truncated. Overwriting a vault file in
     * place is the one mistake here that loses data outright.
     */
    private fun writeAtomically(file: File, document: String) {
        val target = file.absoluteFile
        val directory = target.parentFile ?: File(".")
        directory.mkdirs()

        val temp = File.createTempFile("${target.name}.", ".part", directory)
        try {
            temp.writeText(document, Charsets.UTF_8)
            try {
                Files.move(
                    temp.toPath(), target.toPath(),
                    StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING
                )
            } catch (e: java.nio.file.AtomicMoveNotSupportedException) {
                // Some filesystems cannot promise it. Still better than writing in place.
                Files.move(temp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
        } finally {
            temp.delete()
        }
    }

    /**
     * A v1 row's payload is sealed under the same key as the envelope around it. Any row
     * that resists is reported rather than dropped — a backup that silently omits entries
     * is worse than one that refuses to open.
     */
    private fun openLegacyRows(json: String, backupKey: SecretKey): List<Credential> {
        val rows = VaultBackupFormat.readLegacyRows(json)
        val failed = mutableListOf<String>()

        val credentials = rows.mapNotNull { row ->
            try {
                val plaintext = cryptoManager.decrypt(
                    EncryptedData(row.encryptedPayload, row.iv), backupKey
                )
                val payload = String(plaintext, Charsets.UTF_8)
                plaintext.fill(0)
                CredentialPayloadCodec.decode(
                    json = payload,
                    id = row.id,
                    createdAt = row.createdAt,
                    updatedAt = row.updatedAt
                )
            } catch (e: Exception) {
                failed += row.id
                null
            }
        }

        check(failed.isEmpty()) {
            "${failed.size} of ${rows.size} entries in this v1 backup could not be " +
                "decrypted. Refusing to open it as complete."
        }
        return credentials
    }
}
