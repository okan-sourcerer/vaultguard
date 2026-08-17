package com.vaultguard.app.security

import android.content.Context
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.crypto.SecretKey
import javax.crypto.spec.SecretKeySpec
import android.util.Log
import javax.inject.Inject
import javax.inject.Singleton
import androidx.core.content.edit

@Singleton
class BiometricAuthManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val keystoreManager: KeystoreManager
) {
    private val TAG = "BiometricAuth"

    companion object {
        private const val PREFS_NAME = "biometric_prefs"
        private const val KEY_WRAPPED_KEY = "wrapped_vault_key"
        private const val KEY_WRAPPED_IV = "wrapped_vault_iv"
    }

    val isBiometricAvailable: Boolean
        get() {
            val manager = BiometricManager.from(context)
            val result = manager.canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_STRONG)
            Log.d(TAG, "canAuthenticate(BIOMETRIC_STRONG) = $result (SUCCESS=0, NONE_ENROLLED=11, NO_HARDWARE=12, UNAVAILABLE=1)")
            if (result != BiometricManager.BIOMETRIC_SUCCESS) {
                val classResult = manager.canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_WEAK)
                Log.d(TAG, "canAuthenticate(BIOMETRIC_WEAK) = $classResult")
            }
            return result == BiometricManager.BIOMETRIC_SUCCESS
        }

    val isBiometricEnabled: Boolean
        get() {
            val prefs = getPrefs()
            return prefs.getString(KEY_WRAPPED_KEY, null) != null && keystoreManager.isBiometricKeyValid
        }

    fun enableBiometric(vaultKey: SecretKey, activity: FragmentActivity, onResult: (Boolean) -> Unit) {
        Log.d(TAG, "enableBiometric called, activity=$activity, vaultKey=${vaultKey.algorithm}")
        keystoreManager.generateBiometricKey()

        val cipher = try {
            keystoreManager.getEncryptCipher()
        } catch (e: Exception) {
            Log.e(TAG, "getEncryptCipher failed", e)
            onResult(false)
            return
        }

        val prompt = BiometricPrompt(
            activity,
            ContextCompat.getMainExecutor(context),
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    val authedCipher = result.cryptoObject?.cipher ?: return onResult(false)
                    val wrapped = keystoreManager.encryptWithCipher(authedCipher, vaultKey.encoded)

                    getPrefs().edit {
                        putString(KEY_WRAPPED_KEY, wrapped.ciphertext.toBase64())
                        putString(KEY_WRAPPED_IV, wrapped.iv.toBase64())
                    }

                    onResult(true)
                }

                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    onResult(false)
                }

                override fun onAuthenticationFailed() {}
            }
        )

        prompt.authenticate(
            buildPromptInfo("Enable biometric unlock"),
            BiometricPrompt.CryptoObject(cipher)
        )
    }

    fun authenticateAndUnwrapKey(activity: FragmentActivity, onResult: (SecretKey?) -> Unit) {
        val prefs = getPrefs()
        val wrappedKey = prefs.getString(KEY_WRAPPED_KEY, null)?.fromBase64() ?: return onResult(null)
        val wrappedIv = prefs.getString(KEY_WRAPPED_IV, null)?.fromBase64() ?: return onResult(null)

        val cipher = try {
            keystoreManager.getDecryptCipher(wrappedIv)
        } catch (_: Exception) {
            disableBiometric()
            onResult(null)
            return
        }

        val prompt = BiometricPrompt(
            activity,
            ContextCompat.getMainExecutor(context),
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    val authedCipher = result.cryptoObject?.cipher ?: return onResult(null)
                    try {
                        val keyBytes = keystoreManager.decryptWithCipher(
                            authedCipher,
                            EncryptedData(wrappedKey, wrappedIv)
                        )
                        val vaultKey = SecretKeySpec(keyBytes, "AES")
                        keyBytes.fill(0)
                        onResult(vaultKey)
                    } catch (_: Exception) {
                        onResult(null)
                    }
                }

                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    onResult(null)
                }

                override fun onAuthenticationFailed() {}
            }
        )

        prompt.authenticate(
            buildPromptInfo("Unlock VaultGuard"),
            BiometricPrompt.CryptoObject(cipher)
        )
    }

    fun disableBiometric() {
        keystoreManager.removeBiometricKey()
        getPrefs().edit {
            remove(KEY_WRAPPED_KEY)
            remove(KEY_WRAPPED_IV)
        }
    }

    private fun buildPromptInfo(title: String) = BiometricPrompt.PromptInfo.Builder()
        .setTitle(title)
        .setDescription("Authenticate with your fingerprint")
        .setNegativeButtonText("Use master password")
        .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_STRONG)
        .build()

    private fun getPrefs() = EncryptedSharedPreferences.create(
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
