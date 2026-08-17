package com.vaultguard.app.domain.usecase.backup

import com.vaultguard.app.domain.model.Credential
import com.vaultguard.app.security.KeyDerivation
import org.json.JSONArray
import org.json.JSONObject

/**
 * Reading and writing of the encrypted backup file. Pure — no Android, no I/O, no crypto
 * side effects — so the format can be exercised directly.
 *
 * The wire formats are specified in `docs/DATA-FORMATS.md`.
 *
 * ## Why v2 exists
 *
 * v1 was doubly encrypted with two different keys and only shipped one of them. The outer
 * envelope was sealed under a key the file described (via its salt), but each inner
 * credential payload stayed sealed under the export-time key, and the importer re-inserted
 * those bytes verbatim. The import appeared to succeed and every credential was then
 * unreadable (finding #3).
 *
 * v2 has a single encryption layer whose key is fully described by the file, and the
 * importer re-encrypts under whatever key the receiving vault uses.
 */
object VaultBackupFormat {

    const val VERSION_1 = 1
    const val VERSION_2 = 2

    /** Refuses absurd Argon2 costs from a hostile or corrupt file rather than trying them. */
    private const val MAX_MEMORY_KIB = 1024 * 1024 // 1 GiB
    private const val MAX_ITERATIONS = 32
    private const val MAX_PARALLELISM = 16

    class UnsupportedBackupException(message: String) : Exception(message)

    data class KdfParams(
        val salt: ByteArray,
        val memoryKib: Int = KeyDerivation.MEMORY_COST_KIB,
        val iterations: Int = KeyDerivation.ITERATIONS,
        val parallelism: Int = KeyDerivation.PARALLELISM
    ) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is KdfParams) return false
            return salt.contentEquals(other.salt) && memoryKib == other.memoryKib &&
                iterations == other.iterations && parallelism == other.parallelism
        }

        override fun hashCode(): Int =
            31 * (31 * (31 * salt.contentHashCode() + memoryKib) + iterations) + parallelism
    }

    /**
     * The envelope, before its payload is decrypted. [version] decides how the decrypted
     * bytes are interpreted.
     */
    data class Envelope(
        val version: Int,
        val kdf: KdfParams,
        val iv: ByteArray,
        val ciphertext: ByteArray
    ) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is Envelope) return false
            return version == other.version && kdf == other.kdf &&
                iv.contentEquals(other.iv) && ciphertext.contentEquals(other.ciphertext)
        }

        override fun hashCode(): Int =
            31 * (31 * (31 * version + kdf.hashCode()) + iv.contentHashCode()) +
                ciphertext.contentHashCode()
    }

    /** One credential as it appears inside a decrypted v2 payload. */
    data class Entry(
        val id: String,
        val createdAt: Long,
        val updatedAt: Long,
        val passwordChangedAt: Long,
        val credential: Credential
    )

    // -- Envelope ---------------------------------------------------------------------------

    fun writeEnvelope(kdf: KdfParams, iv: ByteArray, ciphertext: ByteArray): String =
        JSONObject().apply {
            put("version", VERSION_2)
            put("exportedAt", System.currentTimeMillis())
            put(
                "kdf",
                JSONObject().apply {
                    put("algorithm", "argon2id")
                    put("version", KeyDerivation.ARGON2_VERSION)
                    put("memoryKib", kdf.memoryKib)
                    put("iterations", kdf.iterations)
                    put("parallelism", kdf.parallelism)
                    put("salt", kdf.salt.toBase64())
                }
            )
            put("iv", iv.toBase64())
            put("payload", ciphertext.toBase64())
        }.toString(2)

    fun readEnvelope(text: String): Envelope {
        val root = try {
            JSONObject(text)
        } catch (e: Exception) {
            throw UnsupportedBackupException("This file is not a VaultGuard backup.")
        }

        return when (val version = root.optInt("version", -1)) {
            VERSION_2 -> readV2(root)
            VERSION_1 -> readV1(root)
            -1 -> throw UnsupportedBackupException("This file is not a VaultGuard backup.")
            else -> throw UnsupportedBackupException(
                "This backup was written by a newer version of VaultGuard (format $version)."
            )
        }
    }

    private fun readV2(root: JSONObject): Envelope {
        val kdfObject = root.optJSONObject("kdf")
            ?: throw UnsupportedBackupException("Backup is missing its key-derivation settings.")

        val algorithm = kdfObject.optString("algorithm")
        if (!algorithm.equals("argon2id", ignoreCase = true)) {
            throw UnsupportedBackupException("Unsupported key derivation: $algorithm")
        }
        val argonVersion = kdfObject.optInt("version", KeyDerivation.ARGON2_VERSION)
        if (argonVersion != KeyDerivation.ARGON2_VERSION) {
            throw UnsupportedBackupException("Unsupported Argon2 version: $argonVersion")
        }

        val memoryKib = kdfObject.optInt("memoryKib", KeyDerivation.MEMORY_COST_KIB)
        val iterations = kdfObject.optInt("iterations", KeyDerivation.ITERATIONS)
        val parallelism = kdfObject.optInt("parallelism", KeyDerivation.PARALLELISM)

        // A file is untrusted input. Attempting a 64 GiB derivation because it said so
        // would take the app down rather than report a bad backup.
        if (memoryKib !in 1..MAX_MEMORY_KIB ||
            iterations !in 1..MAX_ITERATIONS ||
            parallelism !in 1..MAX_PARALLELISM
        ) {
            throw UnsupportedBackupException("Backup declares unreasonable key-derivation settings.")
        }

        return Envelope(
            version = VERSION_2,
            kdf = KdfParams(
                salt = root.requireBase64From(kdfObject, "salt"),
                memoryKib = memoryKib,
                iterations = iterations,
                parallelism = parallelism
            ),
            iv = root.requireBase64("iv"),
            ciphertext = root.requireBase64("payload")
        )
    }

    private fun readV1(root: JSONObject): Envelope = Envelope(
        version = VERSION_1,
        // v1 predates the kdf block; it always used the vault's parameters.
        kdf = KdfParams(salt = root.requireBase64("salt")),
        iv = root.requireBase64("iv"),
        ciphertext = root.requireBase64("encryptedVault")
    )

    // -- v2 payload -----------------------------------------------------------------------------

    fun writeEntries(credentials: List<Credential>): String {
        val array = JSONArray()
        for (credential in credentials) {
            array.put(
                JSONObject().apply {
                    put("id", credential.id)
                    put("createdAt", credential.createdAt)
                    put("updatedAt", credential.updatedAt)
                    put("passwordChangedAt", credential.passwordChangedAt)
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
                }
            )
        }
        return array.toString()
    }

    fun readEntries(json: String): List<Entry> {
        val array = try {
            JSONArray(json)
        } catch (e: Exception) {
            throw UnsupportedBackupException("Backup contents are not in the expected shape.")
        }

        return (0 until array.length()).map { index ->
            val obj = array.getJSONObject(index)
            val createdAt = obj.optLong("createdAt", 0L)
            val updatedAt = obj.optLong("updatedAt", createdAt)
            Entry(
                id = obj.optString("id", ""),
                createdAt = createdAt,
                updatedAt = updatedAt,
                // Backups written before the column existed fall back to updatedAt, the
                // same convention the schema migration used.
                passwordChangedAt = obj.optLong("passwordChangedAt", updatedAt),
                credential = Credential(
                    id = obj.optString("id", ""),
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
                    passwordChangedAt = obj.optLong("passwordChangedAt", updatedAt)
                )
            )
        }
    }

    // -- v1 payload -------------------------------------------------------------------------------

    /** One row from a v1 payload: metadata plus a payload still sealed under the backup key. */
    data class LegacyRow(
        val id: String,
        val encryptedPayload: ByteArray,
        val iv: ByteArray,
        val createdAt: Long,
        val updatedAt: Long
    ) {
        override fun equals(other: Any?): Boolean = this === other || (other is LegacyRow && id == other.id)
        override fun hashCode(): Int = id.hashCode()
    }

    fun readLegacyRows(json: String): List<LegacyRow> {
        val array = try {
            JSONArray(json)
        } catch (e: Exception) {
            throw UnsupportedBackupException("Backup contents are not in the expected shape.")
        }

        return (0 until array.length()).map { index ->
            val obj = array.getJSONObject(index)
            LegacyRow(
                id = obj.getString("id"),
                encryptedPayload = obj.getString("encryptedPayload").fromBase64(),
                iv = obj.getString("iv").fromBase64(),
                createdAt = obj.optLong("createdAt", 0L),
                updatedAt = obj.optLong("updatedAt", 0L)
            )
        }
    }

    // -- Helpers ------------------------------------------------------------------------------------

    private fun JSONObject.requireBase64(key: String): ByteArray =
        requireBase64From(this, key)

    private fun JSONObject.requireBase64From(source: JSONObject, key: String): ByteArray {
        val raw = source.optString(key, "")
        if (raw.isEmpty()) throw UnsupportedBackupException("Backup is missing \"$key\".")
        return try {
            raw.fromBase64()
        } catch (e: Exception) {
            throw UnsupportedBackupException("Backup field \"$key\" is not valid base64.")
        }
    }

    private fun JSONArray?.toStringList(): List<String> {
        if (this == null) return emptyList()
        return (0 until length()).map { getString(it) }
    }

    private fun ByteArray.toBase64(): String = java.util.Base64.getEncoder().encodeToString(this)

    private fun String.fromBase64(): ByteArray = java.util.Base64.getDecoder().decode(this)
}
