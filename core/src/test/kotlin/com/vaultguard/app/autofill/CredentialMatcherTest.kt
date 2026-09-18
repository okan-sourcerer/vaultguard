package com.vaultguard.app.autofill

import com.vaultguard.app.domain.model.Credential
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for autofill matching (findings #10, #11).
 *
 * Whatever this returns is offered as a fill to whoever asked, so the tests that matter
 * most are the ones asserting a match is **refused**.
 */
class CredentialMatcherTest {

    private fun credential(
        id: String = "a",
        siteName: String = "GitHub",
        url: String = "",
        linkedDomains: List<String> = emptyList(),
        linkedPackages: List<String> = emptyList()
    ) = Credential(
        id = id,
        siteName = siteName,
        url = url,
        username = "okan",
        password = "pw",
        linkedDomains = linkedDomains,
        linkedPackages = linkedPackages
    )

    private fun matchDomain(domain: String, vararg candidates: Credential) =
        CredentialMatcher.match(candidates.toList(), domain, null)

    private fun matchPackage(pkg: String, vararg candidates: Credential) =
        CredentialMatcher.match(candidates.toList(), null, pkg)

    // -- Lookalike domains — finding #10 -------------------------------------------------

    @Test
    fun `a lookalike domain does not match`() {
        val github = credential(url = "https://github.com")

        assertTrue(matchDomain("notgithub.com", github).isEmpty())
        assertTrue(matchDomain("github.com.evil.example", github).isEmpty())
        assertTrue(matchDomain("github.co", github).isEmpty())
        assertTrue(matchDomain("evilgithub.com", github).isEmpty())
    }

    @Test
    fun `the exact host matches`() {
        val github = credential(url = "https://github.com")

        assertEquals(1, matchDomain("github.com", github).size)
    }

    @Test
    fun `a subdomain of the stored host matches`() {
        val google = credential(url = "https://google.com")

        assertEquals(1, matchDomain("accounts.google.com", google).size)
    }

    @Test
    fun `the parent of a stored subdomain matches`() {
        val accounts = credential(url = "https://accounts.google.com")

        assertEquals(1, matchDomain("google.com", accounts).size)
    }

    @Test
    fun `a sibling subdomain matches through the registrable domain`() {
        // Saved on login.site.com, wanted on app.site.com. Both belong to whoever owns
        // site.com; every browser password manager treats them as one site.
        val login = credential(url = "https://login.site.com")

        assertEquals(listOf(login), matchDomain("app.site.com", login))
        assertEquals(listOf(login), matchDomain("site.com", login))
    }

    @Test
    fun `a sibling subdomain does not match across a shared host`() {
        // alice.github.io and bob.github.io are different people. The registrable domain
        // stops at the shared host, so neither fills on the other.
        val alice = credential(url = "https://alice.github.io")

        assertTrue(matchDomain("bob.github.io", alice).isEmpty())
        // But a subdomain of alice's own still does.
        assertEquals(listOf(alice), matchDomain("blog.alice.github.io", alice))
    }

    @Test
    fun `a sibling match ranks below exact and parent-child matches`() {
        val sibling = credential(id = "sibling", url = "https://login.site.com")
        val parent = credential(id = "parent", url = "https://site.com")
        val exact = credential(id = "exact", url = "https://app.site.com")

        val ids = matchDomain("app.site.com", sibling, parent, exact).map { it.id }
        assertEquals(setOf("exact", "parent", "sibling"), ids.toSet())
        assertEquals("sibling", ids.last())
    }

    @Test
    fun `registrable domains respect country-code second levels and refuse IPs`() {
        assertEquals("site.com", CredentialMatcher.registrableDomain("login.site.com"))
        assertEquals("site.com", CredentialMatcher.registrableDomain("site.com"))
        assertEquals("example.co.uk", CredentialMatcher.registrableDomain("shop.example.co.uk"))
        assertEquals("bank.com.tr", CredentialMatcher.registrableDomain("internet.bank.com.tr"))
        assertEquals("alice.github.io", CredentialMatcher.registrableDomain("www.alice.github.io"))
        assertNull(CredentialMatcher.registrableDomain("github.io"))
        assertNull(CredentialMatcher.registrableDomain("co.uk"))
        assertNull(CredentialMatcher.registrableDomain("10.0.0.1"))
        assertNull(CredentialMatcher.registrableDomain("localhost"))
        // A lookalike is still a different registrable domain.
        assertFalse(CredentialMatcher.siblingsMatch("login.site.com", "app.notsite.com"))
        assertFalse(CredentialMatcher.siblingsMatch("a.example.co.uk", "b.other.co.uk"))
    }

    @Test
    fun `host comparison ignores scheme, port, path and case`() {
        val github = credential(url = "HTTPS://GitHub.com:443/login?next=%2F")

        assertEquals(1, matchDomain("github.com", github).size)
    }

    @Test
    fun `a credential with no url and no links matches nothing`() {
        val orphan = credential(url = "", siteName = "GitHub")

        assertTrue(matchDomain("github.com", orphan).isEmpty())
        assertTrue(matchPackage("com.github.android", orphan).isEmpty())
    }

    @Test
    fun `an empty request matches nothing`() {
        val github = credential(url = "https://github.com")

        assertTrue(CredentialMatcher.match(listOf(github), null, null).isEmpty())
        assertTrue(CredentialMatcher.match(listOf(github), "", "").isEmpty())
    }

    // -- Package matching — finding #11 ------------------------------------------------------

    @Test
    fun `a hostile package name does not match on a site name substring`() {
        // The #11 hole: any word of the site name appearing anywhere in the package.
        val gmail = credential(siteName = "Gmail", url = "https://mail.google.com")

        assertTrue(matchPackage("com.evil.gmail", gmail).isEmpty())
        assertTrue(matchPackage("com.gmail.phisher", gmail).isEmpty())
        assertTrue(matchPackage("gmail", gmail).isEmpty())
    }

    @Test
    fun `an explicitly linked package matches`() {
        val github = credential(linkedPackages = listOf("com.github.android"))

        assertEquals(1, matchPackage("com.github.android", github).size)
    }

    @Test
    fun `a linked package match is exact, not a prefix`() {
        val github = credential(linkedPackages = listOf("com.github.android"))

        assertTrue(matchPackage("com.github.android.evil", github).isEmpty())
        assertTrue(matchPackage("com.github", github).isEmpty())
    }

    @Test
    fun `a package reversing to the stored host matches`() {
        val github = credential(url = "https://github.com")

        assertEquals(1, matchPackage("com.github.android", github).size)
    }

    @Test
    fun `a package reversing to an unrelated host does not match`() {
        val github = credential(url = "https://github.com")

        assertTrue(matchPackage("com.evil.github", github).isEmpty())
        assertTrue(matchPackage("net.github.clone", github).isEmpty())
    }

    @Test
    fun `a too-short package derives nothing`() {
        assertNull(CredentialMatcher.domainFromPackage("android"))
        assertEquals("github.com", CredentialMatcher.domainFromPackage("com.github.android"))
    }

    // -- Ordering ------------------------------------------------------------------------------

    @Test
    fun `an explicit link outranks a derived one`() {
        val linked = credential(id = "linked", linkedPackages = listOf("com.github.android"))
        val derived = credential(id = "derived", url = "https://github.com")

        val results = matchPackage("com.github.android", derived, linked)

        assertEquals(listOf("linked", "derived"), results.map { it.id })
    }

    @Test
    fun `an exact host outranks a subdomain relationship`() {
        val exact = credential(id = "exact", url = "https://accounts.google.com")
        val parent = credential(id = "parent", linkedDomains = listOf("google.com"))

        val results = matchDomain("accounts.google.com", exact, parent)

        assertEquals(setOf("exact", "parent"), results.map { it.id }.toSet())
        assertEquals("parent", results.first().id) // linkedDomains outrank a bare url
    }

    @Test
    fun `several genuine matches are all returned`() {
        val work = credential(id = "work", url = "https://github.com")
        val personal = credential(id = "personal", url = "https://github.com")

        assertEquals(2, matchDomain("github.com", work, personal).size)
    }

    // -- Host normalisation --------------------------------------------------------------------

    @Test
    fun `normaliseHost strips the parts that are not the host`() {
        assertEquals("github.com", CredentialMatcher.normaliseHost("https://github.com/login"))
        assertEquals("github.com", CredentialMatcher.normaliseHost("github.com:8443"))
        assertEquals("github.com", CredentialMatcher.normaliseHost("http://user:pw@github.com/"))
        assertEquals("github.com", CredentialMatcher.normaliseHost("  GitHub.com.  "))
    }

    @Test
    fun `normaliseHost rejects values that are not hosts`() {
        assertNull(CredentialMatcher.normaliseHost(null))
        assertNull(CredentialMatcher.normaliseHost(""))
        assertNull(CredentialMatcher.normaliseHost("   "))
        // A bare label would match far too generously.
        assertNull(CredentialMatcher.normaliseHost("localhost"))
        assertNull(CredentialMatcher.normaliseHost("https://"))
    }

    @Test
    fun `hostsMatch is symmetric and rejects empties`() {
        assertTrue(CredentialMatcher.hostsMatch("a.example.com", "example.com"))
        assertTrue(CredentialMatcher.hostsMatch("example.com", "a.example.com"))
        assertFalse(CredentialMatcher.hostsMatch(null, "example.com"))
        assertFalse(CredentialMatcher.hostsMatch("", ""))
    }
}
