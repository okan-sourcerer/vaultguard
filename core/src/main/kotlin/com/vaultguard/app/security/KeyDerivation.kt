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

        /** Frozen for the vault. Backups carry their own copy — see docs/SECURITY.md. */
        const val MEMORY_COST_KIB = 65536
        const val ITERATIONS = 3
        const val PARALLELISM = 4
        const val ARGON2_VERSION = 19
    }

    private val secureRandom = SecureRandom()

    fun generateSalt(): ByteArray {
        return ByteArray(SALT_SIZE_BYTES).also { secureRandom.nextBytes(it) }
    }

    /** Derives with the vault's frozen parameters. See docs/SECURITY.md. */
    fun deriveKey(masterPassword: CharArray, salt: ByteArray): SecretKey =
        deriveKey(masterPassword, salt, MEMORY_COST_KIB, ITERATIONS, PARALLELISM)

    /**
     * Derives with explicit cost parameters.
     *
     * Only backup files use this: they carry their own KDF block so that a future change
     * to the vault's parameters cannot orphan an old backup. The vault itself must always
     * go through the no-argument overload.
     */
    fun deriveKey(
        masterPassword: CharArray,
        salt: ByteArray,
        memoryKib: Int,
        iterations: Int,
        parallelism: Int
    ): SecretKey {
        val passwordBytes = masterPassword.toPasswordBytes()
        try {
            val params = Argon2Parameters.Builder(Argon2Parameters.ARGON2_id)
                .withVersion(Argon2Parameters.ARGON2_VERSION_13)
                .withMemoryAsKB(memoryKib)
                .withIterations(iterations)
                .withParallelism(parallelism)
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
