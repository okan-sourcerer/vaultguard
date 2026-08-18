package com.vaultguard.desktop.cloud

import com.vaultguard.app.security.CryptoManager
import com.vaultguard.app.security.EncryptedData
import org.json.JSONObject
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.PosixFilePermissions
import java.util.Base64
import javax.crypto.SecretKey

/**
 * The Firebase refresh token, kept between runs so signing in is a one-time event.
 *
 * ## Why this is not a plaintext token file
 *
 * `gcloud`, `gh` and `aws` all keep refresh tokens in the clear in the user's home
 * directory. Here that would be a poor trade, because the master password has to be typed
 * on every launch anyway to open the vault — so sealing the token under the key that
 * password derives costs the user nothing at all.
 *
 * What is on disk in the clear is the **salt**, which is not secret: the same value sits in
 * the Firestore vault document and in the phone's preferences.
 *
 * ## What it exposes
 *
 * The file is inert without the master password. It cannot be exchanged for a Firebase
 * session, and it decrypts nothing.
 *
 * It is, however, a **new offline-attackable artifact on the local disk**, where the desktop
 * client previously persisted nothing at all. Someone holding the file can attempt master
 * passwords against it at Argon2id cost per guess, exactly as they could against the
 * verification blob in Firestore or in the phone's preferences. Same class of exposure,
 * same mitigation, and the reason the parameters in `docs/SECURITY.md` must not be
 * weakened. Deleting the file (`--cloud-signout`) removes it.
 */
data class SavedSession(val refreshToken: String, val email: String?) {

    companion object {
        private const val VERSION = 1
        private val cryptoManager = CryptoManager()

        val defaultPath: File
            get() = File(System.getProperty("user.home"), ".vaultguard/desktop-session.json")

        /**
         * @return null when there is no saved session, or the file cannot be read as one.
         *         A damaged file is not an error to report: signing in again fixes it.
         */
        fun saltOf(file: File = defaultPath): ByteArray? {
            if (!file.exists()) return null
            return try {
                val root = JSONObject(file.readText(Charsets.UTF_8))
                if (root.optInt("version") != VERSION) return null
                Base64.getDecoder().decode(root.getString("salt"))
            } catch (e: Exception) {
                null
            }
        }

        /**
         * Opens the saved session with [masterKey].
         *
         * @return null if there is nothing saved, or if the key does not open it — which
         *         means either a wrong master password or a file left over from a previous
         *         one. Both are recoverable by signing in again, so neither throws.
         */
        fun open(masterKey: SecretKey, file: File = defaultPath): SavedSession? {
            if (!file.exists()) return null

            return try {
                val root = JSONObject(file.readText(Charsets.UTF_8))
                if (root.optInt("version") != VERSION) return null

                val plaintext = cryptoManager.decrypt(
                    EncryptedData(
                        Base64.getDecoder().decode(root.getString("ciphertext")),
                        Base64.getDecoder().decode(root.getString("iv"))
                    ),
                    masterKey
                )
                val payload = JSONObject(String(plaintext, Charsets.UTF_8))
                plaintext.fill(0)

                SavedSession(
                    refreshToken = payload.getString("refreshToken"),
                    email = payload.optString("email").takeIf { it.isNotEmpty() }
                )
            } catch (e: Exception) {
                null
            }
        }

        /**
         * Seals [session] under [masterKey] and writes it beside [salt].
         *
         * The salt is stored so the next launch can derive the same key before it has
         * spoken to Firestore — the token is needed to reach the vault document that would
         * otherwise supply it.
         */
        fun save(
            session: SavedSession,
            masterKey: SecretKey,
            salt: ByteArray,
            file: File = defaultPath
        ) {
            val payload = JSONObject()
                .put("refreshToken", session.refreshToken)
                .apply { session.email?.let { put("email", it) } }
                .toString()
                .toByteArray(Charsets.UTF_8)

            val sealed = cryptoManager.encrypt(payload, masterKey)
            payload.fill(0)

            val document = JSONObject()
                .put("version", VERSION)
                .put("salt", Base64.getEncoder().encodeToString(salt))
                .put("iv", Base64.getEncoder().encodeToString(sealed.iv))
                .put("ciphertext", Base64.getEncoder().encodeToString(sealed.ciphertext))
                .toString(2)

            writeOwnerOnly(file, document)
        }

        fun clear(file: File = defaultPath): Boolean = file.exists() && file.delete()

        /**
         * Writes with owner-only permissions where the filesystem understands them, and
         * atomically either way so a crash cannot leave a half-written session behind.
         *
         * The permissions are defence in depth rather than the protection: the contents are
         * already sealed under the master key.
         */
        private fun writeOwnerOnly(file: File, document: String) {
            val target = file.absoluteFile
            target.parentFile?.mkdirs()

            val temp = File.createTempFile("${target.name}.", ".part", target.parentFile)
            try {
                runCatching {
                    Files.setPosixFilePermissions(
                        temp.toPath(), PosixFilePermissions.fromString("rw-------")
                    )
                }
                temp.writeText(document, Charsets.UTF_8)
                Files.move(
                    temp.toPath(), target.toPath(),
                    StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING
                )
            } finally {
                temp.delete()
            }
        }
    }
}
