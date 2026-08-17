package com.vaultguard.app.autofill

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for how a dismissed save prompt is keyed (finding #49).
 *
 * The old key was `webDomain ?: packageName`. In a browser the domain is often absent, so
 * the key collapsed to the browser's package and one Skip silenced every website the user
 * ever visited. These tests are mostly about that collapse not happening.
 */
class AutofillDismissKeyTest {

    private fun key(domain: String? = null, pkg: String? = null) =
        AutofillDismissedPrefs.keyFor(domain, pkg)

    // -- The collapse ---------------------------------------------------------------------

    @Test
    fun `a browser with no domain is not recorded at all`() {
        // Nothing here identifies a site. Remembering the browser would silence all of them.
        assertNull(key(pkg = "com.android.chrome"))
        assertNull(key(domain = "", pkg = "org.mozilla.firefox"))
        assertNull(key(domain = "   ", pkg = "com.sec.android.app.sbrowser"))
    }

    @Test
    fun `two sites in the same browser get different keys`() {
        val a = key(domain = "github.com", pkg = "com.android.chrome")
        val b = key(domain = "gitlab.com", pkg = "com.android.chrome")

        assertNotEquals(a, b)
        assertTrue(a != null && b != null)
    }

    @Test
    fun `the browser package never becomes the key when a domain is present`() {
        val chrome = key(domain = "github.com", pkg = "com.android.chrome")
        val firefox = key(domain = "github.com", pkg = "org.mozilla.firefox")

        assertEquals("the same site is the same site whichever browser shows it", chrome, firefox)
        assertTrue(chrome!!.contains("github.com"))
        assertTrue(!chrome.contains("chrome"))
    }

    // -- Ordinary apps ----------------------------------------------------------------------

    @Test
    fun `a non-browser app is keyed by its package`() {
        val key = key(pkg = "com.example.banking")

        assertEquals("pkg:com.example.banking", key)
    }

    @Test
    fun `two apps get different keys`() {
        assertNotEquals(key(pkg = "com.example.one"), key(pkg = "com.example.two"))
    }

    @Test
    fun `a domain wins over an app package when both are present`() {
        assertEquals(
            key(domain = "github.com"),
            key(domain = "github.com", pkg = "com.example.embedded.webview")
        )
    }

    // -- Normalisation --------------------------------------------------------------------------

    @Test
    fun `equivalent spellings of a host share one key`() {
        val plain = key(domain = "github.com")

        assertEquals(plain, key(domain = "GitHub.com"))
        assertEquals(plain, key(domain = "https://github.com/login"))
        assertEquals(plain, key(domain = "github.com:443"))
    }

    @Test
    fun `subdomains are kept apart`() {
        // Dismissing on one host should not silence a sibling.
        assertNotEquals(key(domain = "mail.google.com"), key(domain = "drive.google.com"))
    }

    @Test
    fun `nothing identifiable yields no key`() {
        assertNull(key())
        assertNull(key(domain = "", pkg = ""))
        assertNull(key(domain = null, pkg = null))
    }

    @Test
    fun `case in the package name does not create a second key`() {
        assertEquals(key(pkg = "com.Example.App"), key(pkg = "com.example.app"))
    }

    // -- Browser list ------------------------------------------------------------------------------

    @Test
    fun `known browsers are recognised regardless of case`() {
        assertTrue(KnownBrowsers.isBrowser("com.android.chrome"))
        assertTrue(KnownBrowsers.isBrowser("COM.ANDROID.CHROME"))
        assertTrue(KnownBrowsers.isBrowser("org.mozilla.firefox"))
    }

    @Test
    fun `ordinary apps are not browsers`() {
        assertTrue(!KnownBrowsers.isBrowser("com.example.banking"))
        assertTrue(!KnownBrowsers.isBrowser(null))
        assertTrue(!KnownBrowsers.isBrowser(""))
    }
}
