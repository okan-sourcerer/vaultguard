package com.vaultguard.app.security

import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * In-memory stand-in for the Android Keystore.
 *
 * Backed by real AES-GCM, so material wrapped under one generated key genuinely fails to
 * unwrap after the key is replaced — which is the behaviour finding #8 turns on.
 */
class FakeBiometricKeystore : BiometricKeystore {

    private var key: SecretKeySpec? = null

    /** Set to simulate a key invalidated by a new biometric enrolment. */
    var invalidated = false

    var generateCount = 0
        private set
    var removeCount = 0
        private set

    override val isKeyValid: Boolean
        get() = key != null && !invalidated

    override fun generateKey() {
        generateCount++
        invalidated = false
        key = SecretKeySpec(ByteArray(32).also { SecureRandom().nextBytes(it) }, "AES")
    }

    override fun removeKey() {
        removeCount++
        key = null
    }

    override fun encryptCipher(): Cipher {
        val current = key ?: error("No biometric key")
        check(!invalidated) { "Key permanently invalidated" }
        return Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, current) }
    }

    override fun decryptCipher(iv: ByteArray): Cipher {
        val current = key ?: error("No biometric key")
        check(!invalidated) { "Key permanently invalidated" }
        return Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(Cipher.DECRYPT_MODE, current, GCMParameterSpec(128, iv))
        }
    }

    override fun encryptWith(cipher: Cipher, data: ByteArray): EncryptedData =
        EncryptedData(cipher.doFinal(data), cipher.iv)

    override fun decryptWith(cipher: Cipher, encryptedData: EncryptedData): ByteArray =
        cipher.doFinal(encryptedData.ciphertext)
}
