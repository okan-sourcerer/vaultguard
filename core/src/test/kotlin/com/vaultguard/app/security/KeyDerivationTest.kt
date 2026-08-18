package com.vaultguard.app.security

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Characterization tests for the vault's key derivation.
 *
 * ## Read this before changing an expected value
 *
 * The golden vectors below were produced by an **independent** Argon2id implementation
 * (the `argon2-cffi` reference binding), not by this code. They pin two things at once:
 *
 *  1. the Argon2id parameters — argon2id, v19, m=64 MiB, t=3, p=4, 32-byte output; and
 *  2. the non-standard UTF-16BE password encoding in `KeyDerivation.toPasswordBytes`.
 *
 * Every credential in a live vault is encrypted under a key produced by exactly this
 * combination. **If one of these tests fails, the change under test has made every
 * existing vault permanently unreadable.** The fix is to revert the change, not to update
 * the expected value. See `docs/SECURITY.md`.
 */
class KeyDerivationTest {

    private val keyDerivation = KeyDerivation()

    // -- Golden vectors -------------------------------------------------------------

    @Test
    fun `golden vector - ascii password matches reference argon2id`() {
        val key = keyDerivation.deriveKey(
            "correct horse battery staple".toCharArray(),
            salt = hex("000102030405060708090a0b0c0d0e0f")
        )

        assertArrayEquals(
            hex("40074d13798bdcf72ccea6845d15fae0e778ff1c79e215293fc578731b7e30e3"),
            key.encoded
        )
    }

    @Test
    fun `golden vector - non-ascii password matches reference argon2id`() {
        // Exercises the UTF-16BE encoding on characters above U+00FF, where a UTF-8
        // implementation would diverge by more than just interleaved null bytes.
        val key = keyDerivation.deriveKey(
            "paßwörd-çğıöşü".toCharArray(),
            salt = hex("a5a5a5a5a5a5a5a55a5a5a5a5a5a5a5a")
        )

        assertArrayEquals(
            hex("4529bdda8e1f2319354fc68212180871727815435d03018db0d3e47478bad65b"),
            key.encoded
        )
    }

    @Test
    fun `password bytes are UTF-16BE, not UTF-8`() {
        // Documents the divergence explicitly. This is the value a conventional
        // implementation feeding UTF-8 bytes to Argon2id would produce for the same
        // password and salt. VaultGuard must NOT produce it.
        val key = keyDerivation.deriveKey(
            "correct horse battery staple".toCharArray(),
            salt = hex("000102030405060708090a0b0c0d0e0f")
        )

        val utf8Result = hex("853b272a44db1421c02962669a55eb0994f3cab385ed1c4c79253eee19bab49e")
        assertFalse(
            "Derivation switched to UTF-8 password bytes — this orphans every existing vault",
            key.encoded.contentEquals(utf8Result)
        )
    }

    // -- Structural properties ------------------------------------------------------

    @Test
    fun `derived key is 256 bits and AES-shaped`() {
        val key = keyDerivation.deriveKey("hunter2hunter2".toCharArray(), keyDerivation.generateSalt())

        assertEquals(32, key.encoded.size)
        assertEquals("AES", key.algorithm)
    }

    @Test
    fun `same password and salt derive the same key`() {
        val salt = keyDerivation.generateSalt()

        val first = keyDerivation.deriveKey("repeatable".toCharArray(), salt)
        val second = keyDerivation.deriveKey("repeatable".toCharArray(), salt)

        assertArrayEquals(first.encoded, second.encoded)
    }

    @Test
    fun `different salts derive different keys from the same password`() {
        val a = keyDerivation.deriveKey("same password".toCharArray(), hex("00".repeat(16)))
        val b = keyDerivation.deriveKey("same password".toCharArray(), hex("ff".repeat(16)))

        assertFalse(a.encoded.contentEquals(b.encoded))
    }

    @Test
    fun `different passwords derive different keys from the same salt`() {
        val salt = keyDerivation.generateSalt()

        val a = keyDerivation.deriveKey("password one".toCharArray(), salt)
        val b = keyDerivation.deriveKey("password two".toCharArray(), salt)

        assertFalse(a.encoded.contentEquals(b.encoded))
    }

    // -- Hygiene --------------------------------------------------------------------

    @Test
    fun `caller's password array is zeroed after derivation`() {
        // Callers cannot reuse the array — including for a retry after a failed unlock.
        // MasterPasswordManager.unlock depends on this and so does every ViewModel above it.
        val password = "wipe me".toCharArray()

        keyDerivation.deriveKey(password, keyDerivation.generateSalt())

        assertArrayEquals(CharArray(password.size) { '\u0000' }, password)
    }

    @Test
    fun `generated salt is 16 bytes and does not repeat`() {
        val salts = (1..64).map { keyDerivation.generateSalt() }

        salts.forEach { assertEquals(16, it.size) }
        assertEquals(
            "SecureRandom returned a duplicate salt",
            salts.size,
            salts.map { it.toList() }.distinct().size
        )
    }

    @Test
    fun `empty password still derives a key`() {
        // Not endorsed — setup enforces a minimum length — but it must not throw, because
        // the unlock path can be reached with an empty field.
        val key = keyDerivation.deriveKey(CharArray(0), keyDerivation.generateSalt())

        assertEquals(32, key.encoded.size)
    }

    @Test
    fun `password differing only in case derives a different key`() {
        val salt = keyDerivation.generateSalt()

        val lower = keyDerivation.deriveKey("casesensitive".toCharArray(), salt)
        val upper = keyDerivation.deriveKey("CaseSensitive".toCharArray(), salt)

        assertNotEquals(lower.encoded.toList(), upper.encoded.toList())
    }

    @Test
    fun `surrogate pair password is encoded as two UTF-16 code units`() {
        // U+1F510 CLOSED LOCK WITH KEY is two Kotlin Chars. The encoder walks Chars, not
        // code points, so this is four bytes — matching the golden-vector convention.
        val salt = keyDerivation.generateSalt()
        val emoji = keyDerivation.deriveKey("🔐".toCharArray(), salt)
        val surrogatesAsChars = keyDerivation.deriveKey(charArrayOf('\uD83D', '\uDD10'), salt)

        assertArrayEquals(emoji.encoded, surrogatesAsChars.encoded)
        assertTrue(emoji.encoded.any { it != 0.toByte() })
    }

    private fun hex(value: String): ByteArray =
        ByteArray(value.length / 2) { value.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
}
