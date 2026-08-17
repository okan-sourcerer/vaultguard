package com.vaultguard.app.autofill

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Findings #54 and #55.
 *
 * The shape being tested is a two-step login: username on one screen, password on the
 * next. Android delivers that as two fill contexts, and the service used to read only the
 * last — so it saw a password and no username, offered to save a blank-username entry, and
 * then failed to recognise the credential it already held.
 */
class SaveValueMergeTest {

    private fun merge(vararg observations: SaveValueMerge.Observation) =
        SaveValueMerge.merge(observations.toList())

    @Test
    fun `a single-screen login passes straight through`() {
        val merged = merge(
            SaveValueMerge.Observation(
                username = "okan",
                password = "hunter2",
                webDomain = "example.com"
            )
        )

        assertEquals("okan", merged.username)
        assertEquals("hunter2", merged.password)
        assertEquals("example.com", merged.webDomain)
    }

    @Test
    fun `a two-step login keeps the username from the first screen`() {
        val merged = merge(
            SaveValueMerge.Observation(username = "okan", webDomain = "accounts.google.com"),
            SaveValueMerge.Observation(password = "hunter2", webDomain = "accounts.google.com")
        )

        assertEquals("okan", merged.username)
        assertEquals("hunter2", merged.password)
    }

    @Test
    fun `a later screen that re-renders the username empty does not erase it`() {
        // The password screen often still contains the username field, hidden and blank.
        val merged = merge(
            SaveValueMerge.Observation(username = "okan"),
            SaveValueMerge.Observation(username = "", password = "hunter2")
        )

        assertEquals("okan", merged.username)
    }

    @Test
    fun `going back and correcting a typo overwrites the earlier value`() {
        val merged = merge(
            SaveValueMerge.Observation(username = "okna"),
            SaveValueMerge.Observation(username = "okan"),
            SaveValueMerge.Observation(password = "hunter2")
        )

        assertEquals("okan", merged.username)
    }

    @Test
    fun `whitespace counts as absent`() {
        val merged = merge(
            SaveValueMerge.Observation(username = "okan"),
            SaveValueMerge.Observation(username = "   ", password = "hunter2")
        )

        assertEquals("okan", merged.username)
    }

    @Test
    fun `a genuine password-only screen yields no username`() {
        // A re-authentication prompt with nothing before it. Nothing to recover, and the
        // caller has to decide what to do about it — see the duplicate rule in the service.
        val merged = merge(SaveValueMerge.Observation(password = "hunter2"))

        assertEquals("", merged.username)
        assertEquals("hunter2", merged.password)
    }

    @Test
    fun `the domain comes from the screen that carried the password`() {
        // An SSO hop: the credential belongs to the identity provider, which is where the
        // password was actually typed, not to the site that redirected there.
        val merged = merge(
            SaveValueMerge.Observation(username = "okan", webDomain = "shop.example.com"),
            SaveValueMerge.Observation(password = "hunter2", webDomain = "accounts.google.com")
        )

        assertEquals("accounts.google.com", merged.webDomain)
    }

    @Test
    fun `a missing domain on the last screen falls back to an earlier one`() {
        val merged = merge(
            SaveValueMerge.Observation(username = "okan", webDomain = "example.com"),
            SaveValueMerge.Observation(password = "hunter2", webDomain = null)
        )

        assertEquals("example.com", merged.webDomain)
    }

    @Test
    fun `package name merges on the same rule as the domain`() {
        val merged = merge(
            SaveValueMerge.Observation(username = "okan", packageName = "com.example.app"),
            SaveValueMerge.Observation(password = "hunter2", packageName = null)
        )

        assertEquals("com.example.app", merged.packageName)
    }

    @Test
    fun `no contexts at all is empty rather than a crash`() {
        val merged = SaveValueMerge.merge(emptyList())

        assertEquals("", merged.username)
        assertEquals("", merged.password)
        assertNull(merged.webDomain)
        assertNull(merged.packageName)
    }
}
