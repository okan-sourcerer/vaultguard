package com.vaultguard.app.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for the master-password rules (finding #28).
 *
 * Setup and change-password each had their own, and they disagreed — setup wanted length
 * *and* strength, change-password only length. So the vault could be moved to a password
 * setup would have refused.
 */
class MasterPasswordPolicyTest {

    private val policy = MasterPasswordPolicy(PasswordStrengthEvaluator())

    private fun reject(
        password: String,
        confirmation: String? = null,
        current: String? = null
    ): String {
        val result = policy.validate(password, confirmation, current)
        assertTrue("expected a rejection for '$password'", result is MasterPasswordPolicy.Result.Rejected)
        return (result as MasterPasswordPolicy.Result.Rejected).reason
    }

    private fun accept(password: String, confirmation: String? = null, current: String? = null) {
        assertEquals(
            MasterPasswordPolicy.Result.Acceptable,
            policy.validate(password, confirmation, current)
        )
    }

    // -- Length -----------------------------------------------------------------------------

    @Test
    fun `short passwords are refused`() {
        assertTrue(reject("Sh0rt!").contains("12"))
        assertTrue(reject("elevenchar").contains("12"))
    }

    @Test
    fun `the minimum is twelve`() {
        assertEquals(12, MasterPasswordPolicy.MINIMUM_LENGTH)
        accept("froth-ladder-quiet-anvil")
    }

    // -- Strength ----------------------------------------------------------------------------

    @Test
    fun `a long but common password is refused`() {
        // Long enough to clear the length rule, and still the first thing anyone guesses.
        val reason = reject("Password123!")

        assertTrue(reason.lowercase().contains("commonly guessed"))
    }

    @Test
    fun `a long but low-entropy password is refused`() {
        assertTrue(reject("aaaaaaaaaaaaaaaa").isNotEmpty())
    }

    @Test
    fun `a decent passphrase is accepted`() {
        accept("thicket-marlin-oboe-crumble")
    }

    @Test
    fun `a strong generated password is accepted`() {
        accept("7Kq\$mZ2!vRx9Bn4Ld#Wp")
    }

    // -- Confirmation and reuse ------------------------------------------------------------------

    @Test
    fun `mismatched confirmation is refused`() {
        assertTrue(
            reject("thicket-marlin-oboe", confirmation = "thicket-marlin-obo")
                .contains("do not match")
        )
    }

    @Test
    fun `matching confirmation is accepted`() {
        accept("thicket-marlin-oboe", confirmation = "thicket-marlin-oboe")
    }

    @Test
    fun `reusing the current password is refused`() {
        // Nothing stopped this before, so "change your master password" could be a no-op
        // that still rotated the salt and re-published the config.
        assertTrue(
            reject("thicket-marlin-oboe", current = "thicket-marlin-oboe")
                .contains("already")
        )
    }

    @Test
    fun `a genuinely different new password is accepted`() {
        accept("thicket-marlin-oboe", confirmation = "thicket-marlin-oboe", current = "froth-ladder-quiet")
    }

    // -- Both callers get the same answer -------------------------------------------------------------

    @Test
    fun `setup and change-password cannot disagree`() {
        // They call one method now, so this is really asserting there is only one rule set
        // — but it is the property that broke, so it is worth stating.
        val candidates = listOf(
            "short", "Password123!", "aaaaaaaaaaaaaaaa",
            "thicket-marlin-oboe-crumble", "7Kq\$mZ2!vRx9Bn4Ld#Wp"
        )
        for (candidate in candidates) {
            val atSetup = policy.validate(candidate, candidate)
            val atChange = policy.validate(candidate, candidate, currentPassword = "something-else-entirely")
            assertEquals(
                "setup and change disagree about '$candidate'",
                atSetup is MasterPasswordPolicy.Result.Acceptable,
                atChange is MasterPasswordPolicy.Result.Acceptable
            )
        }
    }
}
