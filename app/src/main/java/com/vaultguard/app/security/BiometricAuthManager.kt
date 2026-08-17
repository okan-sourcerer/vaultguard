package com.vaultguard.app.security

import android.content.Context
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import dagger.hilt.android.qualifiers.ApplicationContext
import timber.log.Timber
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.spec.SecretKeySpec
import javax.inject.Inject
import javax.inject.Named
import javax.inject.Singleton

/**
 * Biometric unlock: the vault key, wrapped by a Keystore key gated on a strong biometric.
 *
 * Two invariants this class exists to keep, both learned from bugs:
 *
 *  - **Stored wrapped material and the Keystore key must never disagree.** If the Keystore
 *    key is replaced, anything wrapped under the old one is dead weight and must be
 *    cleared in the same breath (finding #8).
 *  - **[isBiometricEnabled] must not claim more than it can deliver.** Reporting enabled
 *    while unwrapping is guaranteed to fail leaves the user with a fingerprint button that
 *    silently does nothing.
 *
 * Note that a *successful* unwrap still does not prove the key opens the vault — the
 * master password may have changed underneath it (finding #6). That check belongs to
 * [MasterPasswordManager.unlockWithKey], which verifies before adopting.
 */
@Singleton
class BiometricAuthManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val keystore: BiometricKeystore,
    @Named(BIOMETRIC_PREFS) private val prefs: SecurePrefs
) {

    companion object {
        /** Frozen — see docs/SECURITY.md. */
        const val PREFS_NAME = "biometric_prefs"
        const val BIOMETRIC_PREFS = "biometric_prefs_qualifier"

        private const val KEY_WRAPPED_KEY = "wrapped_vault_key"
        private const val KEY_WRAPPED_IV = "wrapped_vault_iv"

        private val WRAPPED_KEYS = listOf(KEY_WRAPPED_KEY, KEY_WRAPPED_IV)
    }

    val isBiometricAvailable: Boolean
        get() = BiometricManager.from(context)
            .canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_STRONG) ==
            BiometricManager.BIOMETRIC_SUCCESS

    /**
     * True only when there is wrapped material *and* a Keystore key that could unwrap it.
     *
     * Both halves matter. Checking only for stored bytes reports enabled after the
     * Keystore key has been invalidated by a new fingerprint enrolment; checking only for
     * the key reports enabled when nothing was ever wrapped.
     */
    val isBiometricEnabled: Boolean
        get() = hasWrappedKey && keystore.isKeyValid

    private val hasWrappedKey: Boolean
        get() = prefs.getString(KEY_WRAPPED_KEY) != null && prefs.getString(KEY_WRAPPED_IV) != null

    fun enableBiometric(vaultKey: SecretKey, activity: FragmentActivity, onResult: (Boolean) -> Unit) {
        val cipher = try {
            prepareEnrolment()
        } catch (e: Exception) {
            Timber.e(e, "Could not prepare a biometric enrolment cipher")
            onResult(false)
            return
        }

        authenticate(
            activity = activity,
            title = "Enable biometric unlock",
            cipher = cipher,
            onFailure = {
                // The Keystore key is left exactly as prepareEnrolment left it. When it
                // reused an existing valid key, a cancellation here changes nothing at
                // all — which is the whole point of finding #8.
                onResult(false)
            },
            onSuccess = { authedCipher ->
                completeEnrolment(authedCipher, vaultKey)
                onResult(true)
            }
        )
    }

    /** Wraps and stores the vault key with an already-authenticated cipher. */
    internal fun completeEnrolment(authedCipher: Cipher, vaultKey: SecretKey) {
        val encoded = vaultKey.encoded
        val wrapped = keystore.encryptWith(authedCipher, encoded)
        encoded.fill(0)
        storeWrappedKey(wrapped)
    }

    /**
     * Returns a cipher for wrapping, generating a Keystore key only if the current one is
     * unusable.
     *
     * The previous implementation called `generateBiometricKey()` unconditionally, *before*
     * showing the prompt. Cancelling then left the stored wrapped key encrypted under a
     * Keystore key that no longer existed, while [isBiometricEnabled] still reported true —
     * biometric unlock was permanently broken with no way back except toggling it off and
     * on, which most users would not think to do (finding #8).
     */
    internal fun prepareEnrolment(): Cipher {
        if (!keystore.isKeyValid) {
            // Whatever is stored was wrapped under a key that is gone. Clear it now so a
            // cancelled enrolment leaves biometric honestly off rather than broken-on.
            clearWrappedKey()
            keystore.generateKey()
        }
        return keystore.encryptCipher()
    }

    fun authenticateAndUnwrapKey(activity: FragmentActivity, onResult: (SecretKey?) -> Unit) {
        val wrapped = loadWrappedKey()
        if (wrapped == null) {
            disableBiometric()
            onResult(null)
            return
        }

        val cipher = try {
            keystore.decryptCipher(wrapped.iv)
        } catch (e: Exception) {
            Timber.e(e, "Biometric key unusable; disabling biometric unlock")
            disableBiometric()
            onResult(null)
            return
        }

        authenticate(
            activity = activity,
            title = "Unlock VaultGuard",
            cipher = cipher,
            onFailure = { onResult(null) },
            onSuccess = { authedCipher ->
                try {
                    val keyBytes = keystore.decryptWith(authedCipher, wrapped)
                    val vaultKey = SecretKeySpec(keyBytes, "AES")
                    keyBytes.fill(0)
                    onResult(vaultKey)
                } catch (e: Exception) {
                    // Authentication succeeded but the stored blob would not unwrap, so
                    // the Keystore key and the blob disagree. Nothing will ever fix that
                    // by itself — turn it off so the state stops lying.
                    Timber.e(e, "Wrapped vault key did not unwrap; disabling biometric unlock")
                    disableBiometric()
                    onResult(null)
                }
            }
        )
    }

    /** Removes the Keystore key and the wrapped vault key together — never one alone. */
    fun disableBiometric() {
        keystore.removeKey()
        clearWrappedKey()
    }

    private fun authenticate(
        activity: FragmentActivity,
        title: String,
        cipher: Cipher,
        onSuccess: (Cipher) -> Unit,
        onFailure: () -> Unit
    ) {
        val prompt = BiometricPrompt(
            activity,
            ContextCompat.getMainExecutor(context),
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    val authedCipher = result.cryptoObject?.cipher
                    if (authedCipher == null) onFailure() else onSuccess(authedCipher)
                }

                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    onFailure()
                }

                // A single non-matching finger. The prompt stays up for another attempt,
                // so this is deliberately not a terminal failure.
                override fun onAuthenticationFailed() = Unit
            }
        )

        prompt.authenticate(
            BiometricPrompt.PromptInfo.Builder()
                .setTitle(title)
                .setDescription("Authenticate with your fingerprint")
                .setNegativeButtonText("Use master password")
                .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_STRONG)
                .build(),
            BiometricPrompt.CryptoObject(cipher)
        )
    }

    internal fun loadWrappedKey(): EncryptedData? {
        val ciphertext = prefs.getString(KEY_WRAPPED_KEY)?.fromBase64() ?: return null
        val iv = prefs.getString(KEY_WRAPPED_IV)?.fromBase64() ?: return null
        return EncryptedData(ciphertext, iv)
    }

    private fun storeWrappedKey(data: EncryptedData) {
        prefs.putAll(
            mapOf(
                KEY_WRAPPED_KEY to data.ciphertext.toBase64(),
                KEY_WRAPPED_IV to data.iv.toBase64()
            )
        )
    }

    private fun clearWrappedKey() {
        prefs.remove(WRAPPED_KEYS)
    }

    /** See the note in [MasterPasswordManager] — identical output to `Base64.NO_WRAP`. */
    private fun ByteArray.toBase64(): String = java.util.Base64.getEncoder().encodeToString(this)

    private fun String.fromBase64(): ByteArray = java.util.Base64.getDecoder().decode(this)
}
