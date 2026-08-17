package com.vaultguard.app.security

import org.bouncycastle.crypto.generators.Argon2BytesGenerator
import org.bouncycastle.crypto.params.Argon2Parameters
import java.security.SecureRandom
import javax.crypto.SecretKey
import javax.crypto.spec.SecretKeySpec
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class KeyDerivation @Inject constructor() {

    companion object {
        private const val SALT_SIZE_BYTES = 16
        private const val KEY_SIZE_BYTES = 32
        private const val MEMORY_COST_KIB = 65536
        private const val ITERATIONS = 3
        private const val PARALLELISM = 4
    }

    private val secureRandom = SecureRandom()

    fun generateSalt(): ByteArray {
        return ByteArray(SALT_SIZE_BYTES).also { secureRandom.nextBytes(it) }
    }

    fun deriveKey(masterPassword: CharArray, salt: ByteArray): SecretKey {
        val passwordBytes = masterPassword.toPasswordBytes()
        try {
            val params = Argon2Parameters.Builder(Argon2Parameters.ARGON2_id)
                .withVersion(Argon2Parameters.ARGON2_VERSION_13)
                .withMemoryAsKB(MEMORY_COST_KIB)
                .withIterations(ITERATIONS)
                .withParallelism(PARALLELISM)
                .withSalt(salt)
                .build()

            val generator = Argon2BytesGenerator()
            generator.init(params)

            val keyBytes = ByteArray(KEY_SIZE_BYTES)
            generator.generateBytes(passwordBytes, keyBytes)

            return SecretKeySpec(keyBytes, "AES")
        } finally {
            passwordBytes.fill(0)
            masterPassword.fill('\u0000')
        }
    }

    private fun CharArray.toPasswordBytes(): ByteArray {
        val bytes = ByteArray(size * 2)
        for (i in indices) {
            bytes[i * 2] = (this[i].code shr 8).toByte()
            bytes[i * 2 + 1] = this[i].code.toByte()
        }
        return bytes
    }
}
