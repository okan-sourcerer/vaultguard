package com.vaultguard.app.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for password strength (findings #26, #27).
 *
 * Entropy alone cannot see a dictionary. `Password1!` spans four character classes over ten
 * characters and computes to roughly 65 bits — comfortably STRONG by the old rules — while
 * sitting near the top of every cracking list in existence.
 */
class PasswordStrengthEvaluatorTest {

    private val evaluate = PasswordStrengthEvaluator()

    // -- Common passwords — finding #27 -------------------------------------------------

    @Test
    fun `the classic example is not strong`() {
        val result = evaluate("Password1!")

        assertTrue("this is the headline case", result.isWeak)
        assertTrue(result.isCommon)
    }

    @Test
    fun `common passwords are caught through their usual decorations`() {
        listOf(
            "password", "Password", "PASSWORD", "p@ssword", "P@ssw0rd", "password123",
            "Password123!", "letmein", "L3tm31n", "qwerty123", "iloveyou!", "admin",
            "welcome1", "monkey123", "sifre123", "galatasaray"
        ).forEach { candidate ->
            assertTrue("should be flagged: $candidate", evaluate(candidate).isCommon)
            assertTrue("should be weak: $candidate", evaluate(candidate).isWeak)
        }
    }

    @Test
    fun `a passphrase merely containing a common word is not penalised`() {
        // Only a password that reduces *entirely* to a listed entry is flagged; otherwise
        // every long passphrase with an ordinary word in it would be called weak.
        val result = evaluate("correct-horse-battery-password-staple")

        assertFalse(result.isCommon)
        assertFalse(result.isWeak)
    }

    @Test
    fun `a strong generated password is unaffected`() {
        val result = evaluate("7Kq\$mZ2!vRx9Bn4Ld#Wp")

        assertFalse(result.isCommon)
        assertEquals(StrengthLevel.VERY_STRONG, result.level)
    }

    // -- Normalisation ---------------------------------------------------------------------

    @Test
    fun `normalisation undoes leet substitutions and trims decoration`() {
        assertEquals("password", CommonPasswords.normalise("P@ssw0rd123!"))
        assertEquals("letmein", CommonPasswords.normalise("L3tm31n!!!"))
        assertEquals("qwerty", CommonPasswords.normalise("---QWERTY---"))
    }

    @Test
    fun `normalisation leaves an ordinary passphrase recognisable`() {
        assertEquals("correct horse battery", CommonPasswords.normalise("correct horse battery"))
    }

    // -- Entropy behaviour — unchanged ---------------------------------------------------------

    @Test
    fun `length increases strength`() {
        val short = evaluate("aB3\$xY")
        val long = evaluate("aB3\$xYqW7!zM2#pL9&")

        assertTrue(long.entropy > short.entropy)
    }

    @Test
    fun `character variety increases strength`() {
        assertTrue(evaluate("aB3\$aB3\$aB3\$").entropy > evaluate("aaaaaaaaaaaa").entropy)
    }

    @Test
    fun `an empty password is weak`() {
        val result = evaluate("")

        assertTrue(result.isWeak)
        assertEquals(0, result.score)
    }

    @Test
    fun `keyboard walks are penalised`() {
        assertTrue(evaluate("qwertyuiopZ9\$").entropy < evaluate("xkcdmvnbwuroZ9\$").entropy)
    }

    @Test
    fun `score stays within bounds`() {
        listOf("", "a", "Password1!", "7Kq\$mZ2!vRx9Bn4Ld#Wp", "x".repeat(200)).forEach {
            val score = evaluate(it).score
            assertTrue("score out of range for '$it': $score", score in 0..100)
        }
    }

    // -- One definition of weak — finding #26 -----------------------------------------------------

    @Test
    fun `a long generated passphrase without capitals is not weak`() {
        // Vault Stats used to run its own composition check that called this weak for
        // lacking an uppercase letter, while the evaluator called it very strong. Two
        // definitions of the same word, disagreeing about the same password.
        val result = evaluate("kzq-mwv-rtn-bxf-jhd-pls-yge")

        assertFalse(result.isWeak)
    }

    @Test
    fun `a long alphanumeric password is not weak`() {
        assertFalse(evaluate("h7Kq3mZ2vRx9Bn4Ld8Wp6Ty1").isWeak)
    }
}
