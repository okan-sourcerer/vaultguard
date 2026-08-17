package com.vaultguard.app.security

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.crypto.SecretKey
import javax.inject.Inject
import javax.inject.Singleton
import androidx.core.content.edit

@Singleton
class MasterPasswordManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val cryptoManager: CryptoManager,
    private val keyDerivation: KeyDerivation
) {

    companion object {
        private const val PREFS_NAME = "vault_secure_prefs"
        private const val KEY_SALT = "master_salt"
        private const val KEY_VERIFICATION_CIPHERTEXT = "verification_ciphertext"
        private const val KEY_VERIFICATION_IV = "verification_iv"
        private const val KEY_DB_PASSPHRASE = "db_passphrase"
        private const val VERIFICATION_PLAINTEXT = "VAULTGUARD_VERIFY"
    }

    @Volatile
    private var sessionKey: SecretKey? = null

    private val _vaultLocked = MutableStateFlow(false)
    /** Emits `true` once when the vault is locked (e.g. auto-lock timeout). Resets on collect. */
    val vaultLocked: StateFlow<Boolean> = _vaultLocked.asStateFlow()

    fun consumeLockEvent() { _vaultLocked.value = false }

    val isVaultUnlocked: Boolean get() = sessionKey != null

    val isSetupComplete: Boolean get() {
        val prefs = getEncryptedPrefs()
        return prefs.getString(KEY_SALT, null) != null
    }

    fun setup(masterPassword: CharArray) {
        val salt = keyDerivation.generateSalt()
        val key = keyDerivation.deriveKey(masterPassword, salt)

        val verification = cryptoManager.encrypt(
            VERIFICATION_PLAINTEXT.toByteArray(Charsets.UTF_8),
            key
        )

        // DB passphrase is generated eagerly by getDatabasePassphrase() before setup runs,
        // so we only need to persist the master-password-derived keys here.
        getEncryptedPrefs().edit {
            putString(KEY_SALT, salt.toBase64())
            putString(KEY_VERIFICATION_CIPHERTEXT, verification.ciphertext.toBase64())
            putString(KEY_VERIFICATION_IV, verification.iv.toBase64())
        }

        salt.fill(0)
        masterPassword.fill('\u0000')
        sessionKey = key
    }

    fun unlock(masterPassword: CharArray): Boolean {
        val prefs = getEncryptedPrefs()
        val salt = prefs.getString(KEY_SALT, null)?.fromBase64() ?: return false
        val ciphertext = prefs.getString(KEY_VERIFICATION_CIPHERTEXT, null)?.fromBase64() ?: return false
        val iv = prefs.getString(KEY_VERIFICATION_IV, null)?.fromBase64() ?: return false

        val key = keyDerivation.deriveKey(masterPassword, salt)
        salt.fill(0)
        masterPassword.fill('\u0000')

        return try {
            val decrypted = cryptoManager.decrypt(EncryptedData(ciphertext, iv), key)
            val valid = decrypted.contentEquals(VERIFICATION_PLAINTEXT.toByteArray(Charsets.UTF_8))
            decrypted.fill(0)
            if (valid) {
                sessionKey = key
            }
            valid
        } catch (_: Exception) {
            false
        }
    }

    fun getSessionKey(): SecretKey {
        return sessionKey ?: throw IllegalStateException("Vault is locked")
    }

    fun unlockWithKey(key: SecretKey) {
        sessionKey = key
    }

    private val _pendingLockMessage = MutableStateFlow<String?>(null)
    /** One-shot message to display on the unlock screen after a programmatic lock. */
    val pendingLockMessage: StateFlow<String?> = _pendingLockMessage.asStateFlow()

    fun consumeLockMessage() { _pendingLockMessage.value = null }

    fun lockVault(message: String? = null) {
        sessionKey = null
        _pendingLockMessage.value = message
        _vaultLocked.value = true
    }

    fun getDatabasePassphrase(): ByteArray {
        val prefs = getEncryptedPrefs()
        val stored = prefs.getString(KEY_DB_PASSPHRASE, null)
        if (stored != null) return stored.fromBase64()

        // No passphrase yet — generate and persist one now (before setup completes).
        // This ensures vault.db is always opened with the same passphrase regardless
        // of when the Hilt singleton is first created relative to setup.
        val generated = ByteArray(32).also { java.security.SecureRandom().nextBytes(it) }
        prefs.edit { putString(KEY_DB_PASSPHRASE, generated.toBase64()) }
        return generated
    }

    fun getSalt(): ByteArray {
        val prefs = getEncryptedPrefs()
        return prefs.getString(KEY_SALT, null)?.fromBase64()
            ?: throw IllegalStateException("Vault not set up")
    }

    fun getVerificationData(): Pair<ByteArray, ByteArray> {
        val prefs = getEncryptedPrefs()
        val ciphertext = prefs.getString(KEY_VERIFICATION_CIPHERTEXT, null)?.fromBase64()
            ?: throw IllegalStateException("Vault not set up")
        val iv = prefs.getString(KEY_VERIFICATION_IV, null)?.fromBase64()
            ?: throw IllegalStateException("Vault not set up")
        return Pair(ciphertext, iv)
    }

    /**
     * Adopts the salt and verification data from a remote vault (another device).
     * The current session key becomes invalid — the caller must lock the vault so the
     * user re-unlocks using their master password with the new salt.
     */
    fun adoptRemoteSetup(salt: ByteArray, verificationCiphertext: ByteArray, verificationIv: ByteArray) {
        getEncryptedPrefs().edit {
            putString(KEY_SALT, salt.toBase64())
                .putString(KEY_VERIFICATION_CIPHERTEXT, verificationCiphertext.toBase64())
                .putString(KEY_VERIFICATION_IV, verificationIv.toBase64())
        }
    }

    fun updateMasterPassword(currentPassword: CharArray, newPassword: CharArray): Boolean {
        if (!unlock(currentPassword)) return false

        val newSalt = keyDerivation.generateSalt()
        val newKey = keyDerivation.deriveKey(newPassword, newSalt)

        val verification = cryptoManager.encrypt(
            VERIFICATION_PLAINTEXT.toByteArray(Charsets.UTF_8),
            newKey
        )

        getEncryptedPrefs().edit {
            putString(KEY_SALT, newSalt.toBase64())
            putString(KEY_VERIFICATION_CIPHERTEXT, verification.ciphertext.toBase64())
            putString(KEY_VERIFICATION_IV, verification.iv.toBase64())
        }

        sessionKey = newKey
        return true
    }

    private fun getEncryptedPrefs() = EncryptedSharedPreferences.create(
        context,
        PREFS_NAME,
        MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build(),
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
    )

    private fun ByteArray.toBase64(): String =
        android.util.Base64.encodeToString(this, android.util.Base64.NO_WRAP)

    private fun String.fromBase64(): ByteArray =
        android.util.Base64.decode(this, android.util.Base64.NO_WRAP)
}
