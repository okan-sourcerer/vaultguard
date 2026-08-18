package com.vaultguard.app.security

import com.vaultguard.app.di.CryptoDispatcher
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.security.SecureRandom
import javax.crypto.SecretKey
import javax.crypto.spec.SecretKeySpec
import javax.inject.Inject
import javax.inject.Named
import javax.inject.Singleton

/**
 * Owns the key hierarchy and the unlocked session.
 *
 * ## Two keys, not one
 *
 * ```
 *   master password ──Argon2id(salt)──► masterKey        (key-encrypting key)
 *                                          │
 *                                          ├─AES-GCM─► verification blob
 *                                          └─AES-GCM─► wrapped vaultKey
 *
 *   vaultKey (random, never changes) ──AES-GCM─► credential payloads
 *                                    ──wrapped by Keystore─► biometric unlock
 * ```
 *
 * The vault key is a random 256-bit value generated once at setup. Credentials are
 * encrypted under it, and it is itself encrypted under the master-derived key.
 *
 * Originally the master-derived key encrypted payloads directly, which made changing the
 * master password an O(n) rewrite of the entire vault across two stores that cannot share
 * a transaction (finding #5). With this indirection a password change re-wraps one small
 * blob in a single preferences write: no bulk re-encryption, no partially-converted vault,
 * and biometric unlock keeps working because the key it wraps never changes.
 *
 * `sessionKey` is the **vault key**. Everything above this class encrypts and decrypts
 * with it and never sees the master-derived key.
 */
@Singleton
class MasterPasswordManager @Inject constructor(
    @Named(VAULT_PREFS) private val prefs: SecurePrefs,
    private val cryptoManager: CryptoManager,
    private val keyDerivation: KeyDerivation,
    @CryptoDispatcher private val cryptoDispatcher: CoroutineDispatcher
) {

    companion object {
        /** Frozen — see docs/SECURITY.md. Changing it orphans every existing vault. */
        const val PREFS_NAME = "vault_secure_prefs"
        const val VAULT_PREFS = "vault_secure_prefs_qualifier"

        private const val KEY_SALT = "master_salt"
        private const val KEY_VERIFICATION_CIPHERTEXT = "verification_ciphertext"
        private const val KEY_VERIFICATION_IV = "verification_iv"
        private const val KEY_DB_PASSPHRASE = "db_passphrase"

        /** The vault key, sealed under the master-derived key. */
        private const val KEY_VAULT_KEY_CIPHERTEXT = "vault_key_ciphertext"
        private const val KEY_VAULT_KEY_IV = "vault_key_iv"

        /**
         * A verification blob sealed under the *vault* key, so a key arriving by some
         * route other than the master password — the biometric wrapper — can be checked.
         * The main verification blob is sealed under the master key and cannot serve here.
         */
        private const val KEY_VAULT_CHECK_CIPHERTEXT = "vault_key_check_ciphertext"
        private const val KEY_VAULT_CHECK_IV = "vault_key_check_iv"

        private const val VERIFICATION_PLAINTEXT = "VAULTGUARD_VERIFY"
        private const val VAULT_KEY_SIZE_BYTES = 32
    }

    @Volatile
    private var sessionKey: SecretKey? = null

    private val secureRandom = SecureRandom()

    private val _vaultLocked = MutableStateFlow(false)
    /** Emits `true` when the vault is locked. Cleared by [consumeLockEvent]. */
    val vaultLocked: StateFlow<Boolean> = _vaultLocked.asStateFlow()

    fun consumeLockEvent() { _vaultLocked.value = false }

    private val _pendingLockMessage = MutableStateFlow<String?>(null)
    /** One-shot message to display on the unlock screen after a programmatic lock. */
    val pendingLockMessage: StateFlow<String?> = _pendingLockMessage.asStateFlow()

    fun consumeLockMessage() { _pendingLockMessage.value = null }

    val isVaultUnlocked: Boolean get() = sessionKey != null

    val isSetupComplete: Boolean get() = prefs.getString(KEY_SALT) != null

    /**
     * False for vaults created before the vault-key indirection existed, whose payloads
     * are encrypted directly under the master-derived key. [UnlockVaultUseCase] converts
     * them on the next successful unlock.
     */
    val hasWrappedVaultKey: Boolean get() = prefs.getString(KEY_VAULT_KEY_CIPHERTEXT) != null

    // -- Key derivation --------------------------------------------------------------------

    /**
     * Derives on [cryptoDispatcher]: Argon2id at 64 MiB blocks for hundreds of
     * milliseconds and must never run on the main thread (finding #17).
     *
     * [KeyDerivation.deriveKey] zeroes the array it is given, so callers needing two
     * derivations from one password must pass separate copies.
     */
    suspend fun deriveKey(password: CharArray, salt: ByteArray): SecretKey =
        withContext(cryptoDispatcher) { keyDerivation.deriveKey(password, salt) }

    /** Derives the master key against the stored salt, or null if the vault is not set up. */
    suspend fun deriveMasterKey(password: CharArray): SecretKey? {
        val salt = prefs.getString(KEY_SALT)?.fromBase64() ?: run {
            password.fill('\u0000')
            return null
        }
        val key = deriveKey(password, salt)
        salt.fill(0)
        return key
    }

    // -- Setup -----------------------------------------------------------------------------

    suspend fun setup(masterPassword: CharArray) {
        val salt = keyDerivation.generateSalt()
        val masterKey = deriveKey(masterPassword, salt)
        val vaultKey = generateVaultKey()

        prefs.putAll(
            buildMap {
                put(KEY_SALT, salt.toBase64())
                putAll(verificationEntries(masterKey))
                putAll(wrappedVaultKeyEntries(vaultKey, masterKey))
                putAll(vaultCheckEntries(vaultKey))
            }
        )

        salt.fill(0)
        sessionKey = vaultKey
    }

    fun generateVaultKey(): SecretKey =
        SecretKeySpec(ByteArray(VAULT_KEY_SIZE_BYTES).also { secureRandom.nextBytes(it) }, "AES")

    // -- Master key ⇄ vault key ---------------------------------------------------------------

    /** True if [masterKey] decrypts the stored verification blob. Does not change session state. */
    fun verifyMasterKey(masterKey: SecretKey): Boolean {
        val ciphertext = prefs.getString(KEY_VERIFICATION_CIPHERTEXT)?.fromBase64() ?: return false
        val iv = prefs.getString(KEY_VERIFICATION_IV)?.fromBase64() ?: return false
        return opensVerification(masterKey, ciphertext, iv)
    }

    /** Unwraps the stored vault key, or null if absent or [masterKey] does not open it. */
    fun unwrapVaultKey(masterKey: SecretKey): SecretKey? {
        val ciphertext = prefs.getString(KEY_VAULT_KEY_CIPHERTEXT)?.fromBase64() ?: return null
        val iv = prefs.getString(KEY_VAULT_KEY_IV)?.fromBase64() ?: return null
        return try {
            val raw = cryptoManager.decrypt(EncryptedData(ciphertext, iv), masterKey)
            SecretKeySpec(raw, "AES").also { raw.fill(0) }
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Persists [vaultKey] wrapped under [masterKey], together with the vault-key check blob.
     *
     * Written *before* payloads are re-encrypted during conversion, deliberately: if the
     * process dies in between, the next unlock finds a stored vault key that does not yet
     * open the rows, recognises the half-done state, and resumes with the same key.
     */
    fun storeVaultKey(vaultKey: SecretKey, masterKey: SecretKey) {
        prefs.putAll(wrappedVaultKeyEntries(vaultKey, masterKey) + vaultCheckEntries(vaultKey))
    }

    /**
     * Re-wraps the vault key under a new master password, in a single preferences write.
     *
     * This is the whole payoff of the indirection: no credential is touched, so there is
     * no partially-converted vault to recover from, and the biometric wrapper stays valid
     * because the key it holds is unchanged.
     */
    suspend fun rewrapForNewPassword(newPassword: CharArray, vaultKey: SecretKey) {
        val newSalt = keyDerivation.generateSalt()
        val newMasterKey = deriveKey(newPassword, newSalt)

        prefs.putAll(
            buildMap {
                put(KEY_SALT, newSalt.toBase64())
                putAll(verificationEntries(newMasterKey))
                putAll(wrappedVaultKeyEntries(vaultKey, newMasterKey))
                putAll(vaultCheckEntries(vaultKey))
            }
        )
        newSalt.fill(0)
    }

    // -- Session ---------------------------------------------------------------------------

    fun adoptVaultKey(key: SecretKey) {
        sessionKey = key
    }

    /**
     * Adopts a vault key obtained from the biometric wrapper.
     *
     * @return false if it does not open the vault-key check blob, leaving the session
     *         untouched. Assigning unconditionally was finding #7.
     */
    fun unlockWithKey(key: SecretKey): Boolean {
        if (!verifyVaultKey(key)) return false
        sessionKey = key
        return true
    }

    /** True if [key] is the current vault key. */
    fun verifyVaultKey(key: SecretKey): Boolean {
        val ciphertext = prefs.getString(KEY_VAULT_CHECK_CIPHERTEXT)?.fromBase64() ?: return false
        val iv = prefs.getString(KEY_VAULT_CHECK_IV)?.fromBase64() ?: return false
        return opensVerification(key, ciphertext, iv)
    }

    fun getSessionKey(): SecretKey =
        sessionKey ?: throw IllegalStateException("Vault is locked")

    fun lockVault(message: String? = null) {
        sessionKey = null
        _pendingLockMessage.value = message
        _vaultLocked.value = true
    }

    // -- Stored material ----------------------------------------------------------------------

    fun getDatabasePassphrase(): ByteArray {
        val stored = prefs.getString(KEY_DB_PASSPHRASE)
        if (stored != null) return stored.fromBase64()

        // Generated and persisted on first call, before setup completes, so vault.db is
        // always opened with the same passphrase regardless of when Hilt creates this.
        val generated = ByteArray(32).also { secureRandom.nextBytes(it) }
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
     * The wrapped vault key exactly as stored, for publishing to another device.
     *
     * Sharing it is what makes a second device able to reach the key the rows are actually
     * encrypted under. It is sealed under the master-derived key, so it is no more use to
     * whoever holds the account than the verification blob beside it.
     */
    fun getWrappedVaultKey(): EncryptedData? {
        val ciphertext = prefs.getString(KEY_VAULT_KEY_CIPHERTEXT)?.fromBase64() ?: return null
        val iv = prefs.getString(KEY_VAULT_KEY_IV)?.fromBase64() ?: return null
        return EncryptedData(ciphertext, iv)
    }

    /**
     * Adopts the salt and verification data from a remote vault (another device).
     *
     * The wrapped vault key must come with it. Adopting a salt without one leaves the
     * remote master password verifying while nothing can unwrap the key the rows need,
     * which is how this used to orphan a vault (#4). Callers that cannot supply it should
     * not be adopting.
     */
    fun adoptRemoteSetup(
        salt: ByteArray,
        verificationCiphertext: ByteArray,
        verificationIv: ByteArray,
        wrappedVaultKey: EncryptedData
    ) {
        prefs.putAll(
            mapOf(
                KEY_SALT to salt.toBase64(),
                KEY_VERIFICATION_CIPHERTEXT to verificationCiphertext.toBase64(),
                KEY_VERIFICATION_IV to verificationIv.toBase64(),
                KEY_VAULT_KEY_CIPHERTEXT to wrappedVaultKey.ciphertext.toBase64(),
                KEY_VAULT_KEY_IV to wrappedVaultKey.iv.toBase64()
            )
        )
    }

    // -- Internals -------------------------------------------------------------------------------

    private fun verificationEntries(masterKey: SecretKey): Map<String, String> {
        val sealed = cryptoManager.encrypt(VERIFICATION_PLAINTEXT.toByteArray(Charsets.UTF_8), masterKey)
        return mapOf(
            KEY_VERIFICATION_CIPHERTEXT to sealed.ciphertext.toBase64(),
            KEY_VERIFICATION_IV to sealed.iv.toBase64()
        )
    }

    private fun wrappedVaultKeyEntries(vaultKey: SecretKey, masterKey: SecretKey): Map<String, String> {
        val encoded = vaultKey.encoded
        val sealed = cryptoManager.encrypt(encoded, masterKey)
        encoded.fill(0)
        return mapOf(
            KEY_VAULT_KEY_CIPHERTEXT to sealed.ciphertext.toBase64(),
            KEY_VAULT_KEY_IV to sealed.iv.toBase64()
        )
    }

    private fun vaultCheckEntries(vaultKey: SecretKey): Map<String, String> {
        val sealed = cryptoManager.encrypt(VERIFICATION_PLAINTEXT.toByteArray(Charsets.UTF_8), vaultKey)
        return mapOf(
            KEY_VAULT_CHECK_CIPHERTEXT to sealed.ciphertext.toBase64(),
            KEY_VAULT_CHECK_IV to sealed.iv.toBase64()
        )
    }

    private fun opensVerification(key: SecretKey, ciphertext: ByteArray, iv: ByteArray): Boolean =
        try {
            val decrypted = cryptoManager.decrypt(EncryptedData(ciphertext, iv), key)
            val valid = decrypted.contentEquals(VERIFICATION_PLAINTEXT.toByteArray(Charsets.UTF_8))
            decrypted.fill(0)
            valid
        } catch (_: Exception) {
            false
        }

    /**
     * `java.util.Base64` rather than `android.util.Base64` — byte-identical for the flags
     * this app uses (standard alphabet, padded, no line separators), so everything already
     * in `vault_secure_prefs` decodes unchanged, and it is not a stub in host-side tests.
     */
    private fun ByteArray.toBase64(): String = java.util.Base64.getEncoder().encodeToString(this)

    private fun String.fromBase64(): ByteArray = java.util.Base64.getDecoder().decode(this)
}
