package com.vaultguard.app.security

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyPermanentlyInvalidatedException
import android.security.keystore.KeyProperties
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class KeystoreManager @Inject constructor() {

    companion object {
        private const val KEYSTORE_PROVIDER = "AndroidKeyStore"
        private const val KEY_ALIAS = "vaultguard_biometric_key"
        private const val ALGORITHM = "AES/GCM/NoPadding"
        private const val GCM_TAG_BITS = 128
    }

    private val keyStore: KeyStore = KeyStore.getInstance(KEYSTORE_PROVIDER).apply { load(null) }

    val isBiometricKeyValid: Boolean
        get() {
            if (!keyStore.containsAlias(KEY_ALIAS)) return false
            return try {
                val key = keyStore.getKey(KEY_ALIAS, null) as SecretKey
                val cipher = Cipher.getInstance(ALGORITHM)
                cipher.init(Cipher.ENCRYPT_MODE, key)
                true
            } catch (_: KeyPermanentlyInvalidatedException) {
                keyStore.deleteEntry(KEY_ALIAS)
                false
            } catch (_: Exception) {
                false
            }
        }

    fun generateBiometricKey() {
        val keyGenerator = KeyGenerator.getInstance(
            KeyProperties.KEY_ALGORITHM_AES,
            KEYSTORE_PROVIDER
        )
        keyGenerator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .setUserAuthenticationRequired(true)
                .setInvalidatedByBiometricEnrollment(true)
                .build()
        )
        keyGenerator.generateKey()
    }

    fun getEncryptCipher(): Cipher {
        val key = keyStore.getKey(KEY_ALIAS, null) as SecretKey
        return Cipher.getInstance(ALGORITHM).apply {
            init(Cipher.ENCRYPT_MODE, key)
        }
    }

    fun getDecryptCipher(iv: ByteArray): Cipher {
        val key = keyStore.getKey(KEY_ALIAS, null) as SecretKey
        return Cipher.getInstance(ALGORITHM).apply {
            init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(GCM_TAG_BITS, iv))
        }
    }

    fun encryptWithCipher(cipher: Cipher, data: ByteArray): EncryptedData {
        val ciphertext = cipher.doFinal(data)
        return EncryptedData(ciphertext = ciphertext, iv = cipher.iv)
    }

    fun decryptWithCipher(cipher: Cipher, encryptedData: EncryptedData): ByteArray {
        return cipher.doFinal(encryptedData.ciphertext)
    }

    fun removeBiometricKey() {
        if (keyStore.containsAlias(KEY_ALIAS)) {
            keyStore.deleteEntry(KEY_ALIAS)
        }
    }
}
