package com.vaultguard.app.security

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.SecureRandom
import javax.crypto.AEADBadTagException
import javax.crypto.spec.SecretKeySpec

/**
 * Characterization tests for payload encryption.
 *
 * Pins the AEAD contract every credential in the vault depends on: AES-256-GCM, a fresh
 * 12-byte IV per encryption, a 128-bit tag, and no AAD. See `docs/SECURITY.md`.
 *
 * The integrity tests matter as much as the round-trip ones — GCM's authentication is
 * what makes a corrupted or tampered row fail loudly instead of decrypting to garbage.
 */
class CryptoManagerTest {

    private val crypto = CryptoManager()

    private fun key(seed: Byte = 1) = SecretKeySpec(ByteArray(32) { seed }, "AES")

    private fun randomKey() = SecretKeySpec(
        ByteArray(32).also { SecureRandom().nextBytes(it) }, "AES"
    )

    // -- Round-trip -----------------------------------------------------------------

    @Test
    fun `round-trips a payload`() {
        val plaintext = """{"siteName":"GitHub","password":"hunter2"}""".toByteArray()

        val encrypted = crypto.encrypt(plaintext, key())

        assertArrayEquals(plaintext, crypto.decrypt(encrypted, key()))
    }

    @Test
    fun `round-trips an empty payload`() {
        val encrypted = crypto.encrypt(ByteArray(0), key())

        assertArrayEquals(ByteArray(0), crypto.decrypt(encrypted, key()))
    }

    @Test
    fun `round-trips a large payload`() {
        val plaintext = ByteArray(512 * 1024).also { SecureRandom().nextBytes(it) }

        val encrypted = crypto.encrypt(plaintext, key())

        assertArrayEquals(plaintext, crypto.decrypt(encrypted, key()))
    }

    @Test
    fun `round-trips multi-byte unicode`() {
        val plaintext = "şifre-🔐-пароль-密码".toByteArray(Charsets.UTF_8)

        val encrypted = crypto.encrypt(plaintext, key())

        assertEquals("şifre-🔐-пароль-密码", String(crypto.decrypt(encrypted, key()), Charsets.UTF_8))
    }

    // -- IV and ciphertext shape ----------------------------------------------------

    @Test
    fun `iv is 12 bytes`() {
        assertEquals(12, crypto.encrypt("x".toByteArray(), key()).iv.size)
    }

    @Test
    fun `ciphertext carries a 128-bit tag`() {
        val plaintext = ByteArray(40)

        val encrypted = crypto.encrypt(plaintext, key())

        assertEquals(plaintext.size + 16, encrypted.ciphertext.size)
    }

    @Test
    fun `every encryption uses a fresh iv`() {
        // Catastrophic if violated: GCM nonce reuse under one key leaks the keystream and
        // the authentication key. There must be no counter or deterministic nonce anywhere.
        val ivs = (1..256).map { crypto.encrypt("same plaintext".toByteArray(), key()).iv.toList() }

        assertEquals("IV was reused across encryptions", ivs.size, ivs.distinct().size)
    }

    @Test
    fun `identical plaintext encrypts to different ciphertext`() {
        val a = crypto.encrypt("same".toByteArray(), key())
        val b = crypto.encrypt("same".toByteArray(), key())

        assertFalse(a.ciphertext.contentEquals(b.ciphertext))
    }

    // -- Integrity ------------------------------------------------------------------

    @Test
    fun `decryption fails with the wrong key`() {
        val encrypted = crypto.encrypt("secret".toByteArray(), key(seed = 1))

        assertThrows(AEADBadTagException::class.java) {
            crypto.decrypt(encrypted, key(seed = 2))
        }
    }

    @Test
    fun `decryption fails when a ciphertext bit is flipped`() {
        val encrypted = crypto.encrypt("secret payload".toByteArray(), key())
        val tampered = encrypted.copy(
            ciphertext = encrypted.ciphertext.copyOf().also { it[0] = (it[0].toInt() xor 0x01).toByte() }
        )

        assertThrows(AEADBadTagException::class.java) { crypto.decrypt(tampered, key()) }
    }

    @Test
    fun `decryption fails when a tag bit is flipped`() {
        val encrypted = crypto.encrypt("secret payload".toByteArray(), key())
        val last = encrypted.ciphertext.lastIndex
        val tampered = encrypted.copy(
            ciphertext = encrypted.ciphertext.copyOf().also { it[last] = (it[last].toInt() xor 0x01).toByte() }
        )

        assertThrows(AEADBadTagException::class.java) { crypto.decrypt(tampered, key()) }
    }

    @Test
    fun `decryption fails when the iv is altered`() {
        val encrypted = crypto.encrypt("secret payload".toByteArray(), key())
        val tampered = encrypted.copy(
            iv = encrypted.iv.copyOf().also { it[0] = (it[0].toInt() xor 0x01).toByte() }
        )

        assertThrows(AEADBadTagException::class.java) { crypto.decrypt(tampered, key()) }
    }

    @Test
    fun `decryption fails when ciphertext is truncated`() {
        val encrypted = crypto.encrypt("secret payload".toByteArray(), key())
        val truncated = encrypted.copy(ciphertext = encrypted.ciphertext.copyOf(encrypted.ciphertext.size - 1))

        assertThrows(AEADBadTagException::class.java) { crypto.decrypt(truncated, key()) }
    }

    @Test
    fun `ciphertext does not contain the plaintext`() {
        val plaintext = "TOTALLY-DISTINCTIVE-PASSWORD".toByteArray()

        val encrypted = crypto.encrypt(plaintext, randomKey())

        assertFalse(
            String(encrypted.ciphertext, Charsets.ISO_8859_1)
                .contains("TOTALLY-DISTINCTIVE-PASSWORD")
        )
    }

    // -- EncryptedData value semantics ----------------------------------------------

    @Test
    fun `EncryptedData compares by content`() {
        val a = EncryptedData(byteArrayOf(1, 2, 3), byteArrayOf(4, 5, 6))
        val b = EncryptedData(byteArrayOf(1, 2, 3), byteArrayOf(4, 5, 6))

        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
    }

    @Test
    fun `EncryptedData distinguishes differing iv`() {
        val a = EncryptedData(byteArrayOf(1, 2, 3), byteArrayOf(4, 5, 6))
        val b = EncryptedData(byteArrayOf(1, 2, 3), byteArrayOf(9, 9, 9))

        assertNotEquals(a, b)
    }

    @Test
    fun `EncryptedData is not equal to other types or null`() {
        val a = EncryptedData(byteArrayOf(1), byteArrayOf(2))

        assertNotEquals(a, "not encrypted data")
        assertFalse(a.equals(null))
        assertTrue(a == a)
    }
}
