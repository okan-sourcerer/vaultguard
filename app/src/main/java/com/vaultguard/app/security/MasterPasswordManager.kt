package com.vaultguard.app.security

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.security.SecureRandom
import javax.crypto.SecretKey
import javax.inject.Inject
import javax.inject.Named
import javax.inject.Singleton

@Singleton
class MasterPasswordManager @Inject constructor(
    @Named(VAULT_PREFS) private val prefs: SecurePrefs,
    private val cryptoManager: CryptoManager,
    private val keyDerivation: KeyDerivation
) {

    companion object {
        /** Frozen — see docs/SECURITY.md. Changing it orphans every existing vault. */
        const val PREFS_NAME = "vault_secure_prefs"
        const val VAULT_PREFS = "vault_secure_prefs_qualifier"

        private const val KEY_SALT = "master_salt"
        private const val KEY_VERIFICATION_CIPHERTEXT = "verification_ciphertext"
        private const val KEY_VERIFICATION_IV = "verification_iv"
        private const val KEY_DB_PASSPHRASE = "db_passphrase"
        private const val VERIFICATION_PLAINTEXT = "VAULTGUARD_VERIFY"
    }

    @Volatile
    private var sessionKey: SecretKey? = null

    private val _vaultLocked = MutableStateFlow(false)
    /**
     * Emits `true` when the vault is locked (e.g. auto-lock timeout).
     * Cleared by [consumeLockEvent] — not automatically on collection.
     */
    val vaultLocked: StateFlow<Boolean> = _vaultLocked.asStateFlow()

    fun consumeLockEvent() { _vaultLocked.value = false }

    private val _pendingLockMessage = MutableStateFlow<String?>(null)
    /** One-shot message to display on the unlock screen after a programmatic lock. */
    val pendingLockMessage: StateFlow<String?> = _pendingLockMessage.asStateFlow()

    fun consumeLockMessage() { _pendingLockMessage.value = null }

    val isVaultUnlocked: Boolean get() = sessionKey != null

    val isSetupComplete: Boolean get() = prefs.getString(KEY_SALT) != null

    fun setup(masterPassword: CharArray) {
        val salt = keyDerivation.generateSalt()
        val key = keyDerivation.deriveKey(masterPassword, salt)

        val verification = cryptoManager.encrypt(
            VERIFICATION_PLAINTEXT.toByteArray(Charsets.UTF_8),
            key
        )

        // DB passphrase is generated eagerly by getDatabasePassphrase() before setup runs,
        // so we only need to persist the master-password-derived keys here.
        prefs.putAll(
            mapOf(
                KEY_SALT to salt.toBase64(),
                KEY_VERIFICATION_CIPHERTEXT to verification.ciphertext.toBase64(),
                KEY_VERIFICATION_IV to verification.iv.toBase64()
            )
        )

        salt.fill(0)
        masterPassword.fill('\u0000')
        sessionKey = key
    }

    /**
     * Derives a key from [masterPassword] and adopts it as the session key if it opens the
     * verification blob.
     *
     * Note that [KeyDerivation.deriveKey] zeroes the array it is given, so a caller cannot
     * reuse it for a retry.
     */
    fun unlock(masterPassword: CharArray): Boolean {
        val salt = prefs.getString(KEY_SALT)?.fromBase64() ?: run {
            masterPassword.fill('\u0000')
            return false
        }

        val key = keyDerivation.deriveKey(masterPassword, salt)
        salt.fill(0)

        if (!verifyKey(key)) return false
        sessionKey = key
        return true
    }

    /**
     * Adopts a key obtained by some route other than the master password — currently the
     * biometric-wrapped copy.
     *
     * @return false if the key does not open the verification blob, in which case the
     *         session is left untouched.
     *
     * This used to assign the key unconditionally (finding #7). A stale wrapped key —
     * which is exactly what a master-password change leaves behind (finding #6) — was
     * therefore accepted, every subsequent decryption failed, and the user was shown an
     * empty vault instead of an error.
     */
    fun unlockWithKey(key: SecretKey): Boolean {
        if (!verifyKey(key)) return false
        sessionKey = key
        return true
    }

    /** True if [key] decrypts the stored verification blob. Does not change session state. */
    fun verifyKey(key: SecretKey): Boolean {
        val ciphertext = prefs.getString(KEY_VERIFICATION_CIPHERTEXT)?.fromBase64() ?: return false
        val iv = prefs.getString(KEY_VERIFICATION_IV)?.fromBase64() ?: return false

        return try {
            val decrypted = cryptoManager.decrypt(EncryptedData(ciphertext, iv), key)
            val valid = decrypted.contentEquals(VERIFICATION_PLAINTEXT.toByteArray(Charsets.UTF_8))
            decrypted.fill(0)
            valid
        } catch (_: Exception) {
            false
        }
    }

    fun getSessionKey(): SecretKey =
        sessionKey ?: throw IllegalStateException("Vault is locked")

    fun lockVault(message: String? = null) {
        sessionKey = null
        _pendingLockMessage.value = message
        _vaultLocked.value = true
    }

    fun getDatabasePassphrase(): ByteArray {
        val stored = prefs.getString(KEY_DB_PASSPHRASE)
        if (stored != null) return stored.fromBase64()

        // No passphrase yet — generate and persist one now (before setup completes).
        // This ensures vault.db is always opened with the same passphrase regardless
        // of when the Hilt singleton is first created relative to setup.
        val generated = ByteArray(32).also { SecureRandom().nextBytes(it) }
        prefs.putAll(mapOf(KEY_DB_PASSPHRASE to generated.toBase64()))
        return generated
    }

    fun getSalt(): ByteArray =
        prefs.getString(KEY_SALT)?.fromBase64()
            ?: throw IllegalStateException("Vault not set up")

    fun getVerificationData(): Pair<ByteArray, ByteArray> {
        val ciphertext = prefs.getString(KEY_VERIFICATION_CIPHERTEXT)?.fromBase64()
            ?: throw IllegalStateException("Vault not set up")
        val iv = prefs.getString(KEY_VERIFICATION_IV)?.fromBase64()
            ?: throw IllegalStateException("Vault not set up")
        return Pair(ciphertext, iv)
    }

    /**
     * Adopts the salt and verification data from a remote vault (another device).
     * The current session key becomes invalid — the caller must lock the vault so the
     * user re-unlocks using their master password with the new salt.
     */
    fun adoptRemoteSetup(salt: ByteArray, verificationCiphertext: ByteArray, verificationIv: ByteArray) {
        prefs.putAll(
            mapOf(
                KEY_SALT to salt.toBase64(),
                KEY_VERIFICATION_CIPHERTEXT to verificationCiphertext.toBase64(),
                KEY_VERIFICATION_IV to verificationIv.toBase64()
            )
        )
    }

    fun updateMasterPassword(currentPassword: CharArray, newPassword: CharArray): Boolean {
        if (!unlock(currentPassword)) {
            newPassword.fill('\u0000')
            return false
        }

        val newSalt = keyDerivation.generateSalt()
        val newKey = keyDerivation.deriveKey(newPassword, newSalt)

        val verification = cryptoManager.encrypt(
            VERIFICATION_PLAINTEXT.toByteArray(Charsets.UTF_8),
            newKey
        )

        prefs.putAll(
            mapOf(
                KEY_SALT to newSalt.toBase64(),
                KEY_VERIFICATION_CIPHERTEXT to verification.ciphertext.toBase64(),
                KEY_VERIFICATION_IV to verification.iv.toBase64()
            )
        )

        newSalt.fill(0)
        sessionKey = newKey
        return true
    }

    /**
     * `java.util.Base64` rather than `android.util.Base64`.
     *
     * These are byte-identical for the flags this app uses: `android.util.Base64.NO_WRAP`
     * is the standard alphabet, padded, with no line separators, which is exactly what
     * `java.util.Base64.getEncoder()` produces. Every value already in
     * `vault_secure_prefs` decodes unchanged.
     *
     * The reason to prefer it is testability — `android.util.Base64` is a stub that throws
     * "not mocked" in host-side unit tests, which is part of why this class had none.
     * minSdk is 28, comfortably above the API 26 this requires.
     */
    private fun ByteArray.toBase64(): String =
        java.util.Base64.getEncoder().encodeToString(this)

    private fun String.fromBase64(): ByteArray =
        java.util.Base64.getDecoder().decode(this)
}
