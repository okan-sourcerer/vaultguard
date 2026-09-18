package com.vaultguard.app.autofill

import com.vaultguard.app.domain.model.Credential

/**
 * Decides which stored credentials may be offered for a given app or website.
 *
 * This is the app's most security-sensitive matching logic: whatever it returns is offered
 * as a fill for whoever asked. Two holes it exists to close:
 *
 *  - **Suffix matching** (finding #10). `domain.endsWith(credHost)` treats
 *    `notgoogle.com` as a match for `google.com`, so a lookalike domain harvested the real
 *    credential. Host comparison now happens on dot boundaries only.
 *  - **Substring package matching** (finding #11). The locked-vault path matched any word
 *    of a credential's site name appearing anywhere in a package name, so an app declaring
 *    itself `com.evil.gmail` matched a credential called "Gmail". Package matching is now
 *    either an exact declared link or a reverse-DNS derivation, and nothing else.
 *
 * Both the service and the unlock activity go through here. They previously carried
 * separate implementations that had drifted, with the looser of the two guarding the more
 * sensitive path.
 *
 * It lives in `:core` because there is now a third caller: the browser extension asks the
 * desktop service which credentials may be offered for a page. That answer must come from
 * this code and not from a matcher written again in JavaScript — a second implementation of
 * *this* function is how #10 and #11 happened, and a lookalike-domain hole in a browser is
 * worse than one in an app.
 *
 * `FieldClassifier` stays in `:app`: it needs `android.text.InputType`, and it answers a
 * different question — which field is which, rather than which credential belongs here.
 */
object CredentialMatcher {

    /**
     * @return matches ordered by confidence, closest first. Empty when nothing matches —
     *         never a "best effort" guess.
     */
    fun match(
        candidates: List<Credential>,
        webDomain: String?,
        packageName: String?
    ): List<Credential> {
        val domain = normaliseHost(webDomain)
        val pkg = packageName?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }
        if (domain == null && pkg == null) return emptyList()

        val scored = candidates.mapNotNull { credential ->
            score(credential, domain, pkg)?.let { credential to it }
        }

        return scored
            .sortedBy { (_, tier) -> tier }
            .map { (credential, _) -> credential }
    }

    /** Lower is a closer match; null means no match at all. */
    private fun score(credential: Credential, domain: String?, pkg: String?): Int? {
        if (pkg != null && credential.linkedPackages.any { it.trim().lowercase() == pkg }) return 0
        if (domain != null && credential.linkedDomains.any { hostsMatch(normaliseHost(it), domain) }) return 1

        val credentialHost = normaliseHost(credential.url)
        if (domain != null && credentialHost != null && hostsMatch(credentialHost, domain)) return 2

        // A package with no declared link may still belong to a site the credential knows:
        // com.github.android reverses to github.com. Bounded and checked on dot
        // boundaries — unlike the old "does any site-name word appear in the package".
        if (pkg != null && credentialHost != null) {
            val derived = domainFromPackage(pkg)
            if (derived != null && hostsMatch(credentialHost, derived)) return 3
        }

        // Siblings under one registrable domain: a credential saved on login.site.com is
        // the one wanted on app.site.com. Ranked below every closer relationship, and
        // refused across shared hosts where siblings belong to different people.
        if (domain != null) {
            if (credential.linkedDomains.any { siblingsMatch(normaliseHost(it), domain) }) return 4
            if (credentialHost != null && siblingsMatch(credentialHost, domain)) return 5
        }
        return null
    }

    /**
     * True when the hosts share a registrable domain but neither contains the other -
     * `login.site.com` and `app.site.com`. This is how browser password managers match
     * (Chrome, Firefox and Bitwarden all use the registrable domain), and the case the
     * stricter parent/child rule got wrong in practice.
     *
     * The registrable domain is computed without the full Public Suffix List. Multi-part
     * public suffixes with a country code (`co.uk`, `com.tr`) are recognised by shape, and
     * [SHARED_HOSTS] names the hosting suffixes where a sibling is a different owner -
     * `alice.github.io` must never fill on `bob.github.io`. That list is a guard, not the
     * PSL; a site it does not know can still be linked explicitly on the credential.
     */
    fun siblingsMatch(a: String?, b: String?): Boolean {
        if (a.isNullOrEmpty() || b.isNullOrEmpty()) return false
        if (hostsMatch(a, b)) return false
        val ra = registrableDomain(a) ?: return false
        val rb = registrableDomain(b) ?: return false
        return ra == rb
    }

    /**
     * `login.site.com` -> `site.com`; `shop.example.co.uk` -> `example.co.uk`;
     * `www.alice.github.io` -> `alice.github.io` (the shared host counts as the suffix).
     * Null for IP literals and anything with too few labels to have a registrable part.
     */
    fun registrableDomain(host: String): String? {
        if (host.startsWith("[") || host.all { it.isDigit() || it == '.' }) return null
        val labels = host.split('.').filter { it.isNotEmpty() }
        if (labels.size < 2) return null

        val shared = SHARED_HOSTS.firstOrNull { host == it || host.endsWith(".$it") }
        val suffixLabels = when {
            shared != null -> shared.count { it == '.' } + 1
            // A two-letter country code behind a generic second-level label: co.uk, com.tr,
            // gov.au. Three labels are then the registrable domain.
            labels.last().length == 2 && labels[labels.size - 2] in COUNTRY_SECOND_LEVEL -> 2
            else -> 1
        }
        if (labels.size <= suffixLabels) return null
        return labels.takeLast(suffixLabels + 1).joinToString(".")
    }

    private val COUNTRY_SECOND_LEVEL = setOf(
        "co", "com", "org", "net", "gov", "edu", "ac", "mil", "gen", "biz", "info", "or", "ne",
        "go", "gr", "ltd", "plc", "me", "sch", "nhs", "police", "mod", "k12", "web", "tv", "av",
        "bel", "pol", "dr", "kep", "tsk", "bbs", "name", "pro", "nom", "id", "in", "asn", "conf"
    )

    /**
     * Suffixes under which each subdomain is somebody else's site. Matching across them
     * would offer one tenant's password to another - the shape of finding #10, one level
     * down. Not exhaustive; it names the hosts a password manager is likely to meet.
     */
    val SHARED_HOSTS: Set<String> = setOf(
        "github.io", "gitlab.io", "bitbucket.io", "pages.dev", "workers.dev",
        "herokuapp.com", "netlify.app", "vercel.app", "web.app", "firebaseapp.com",
        "appspot.com", "azurewebsites.net", "cloudfront.net", "amazonaws.com",
        "blogspot.com", "wordpress.com", "tumblr.com", "wixsite.com", "squarespace.com",
        "weebly.com", "glitch.me", "repl.co", "replit.app", "ngrok.io", "ngrok-free.app",
        "trycloudflare.com", "dyndns.org", "no-ip.org", "duckdns.org", "ddns.net",
        "myshopify.com", "sharepoint.com", "onmicrosoft.com", "zendesk.com", "freshdesk.com",
        "okta.com", "auth0.com", "salesforce.com", "force.com", "atlassian.net",
        "cloudapp.net", "linodeusercontent.com", "ondigitalocean.app", "fly.dev",
        "onrender.com", "railway.app", "koyeb.app", "surge.sh", "neocities.org",
        "000webhostapp.com", "x10.mx", "epizy.com", "ucoz.com", "narod.ru", "ucoz.net"
    )

    /**
     * True when the hosts are equal, or one is a subdomain of the other.
     *
     * The dot boundary is the whole point: `accounts.google.com` matches `google.com`,
     * `notgoogle.com` does not.
     */
    fun hostsMatch(a: String?, b: String?): Boolean {
        if (a.isNullOrEmpty() || b.isNullOrEmpty()) return false
        if (a == b) return true
        return a.endsWith(".$b") || b.endsWith(".$a")
    }

    /**
     * Strips scheme, credentials, port, path, query and a trailing dot, leaving a bare
     * lowercase host. Returns null for anything that is not usable as one.
     */
    fun normaliseHost(raw: String?): String? {
        if (raw.isNullOrBlank()) return null
        var value = raw.trim().lowercase()

        value = value.substringBefore("://", missingDelimiterValue = value)
            .let { if (raw.contains("://")) raw.trim().lowercase().substringAfter("://") else it }

        value = value.substringBefore('/')
            .substringBefore('?')
            .substringBefore('#')
        value = value.substringAfterLast('@')
        // Strip a port, but leave IPv6 literals alone rather than mangling them.
        if (!value.startsWith("[")) value = value.substringBefore(':')
        value = value.trim('.')

        if (value.isEmpty()) return null
        // A bare label with no dot is not a host we can reason about, and matching on it
        // would be far too generous.
        if (!value.contains('.')) return null
        return value
    }

    /**
     * `com.github.android` → `github.com`. Returns null when the package is too short to
     * carry a meaningful domain.
     */
    fun domainFromPackage(packageName: String): String? {
        val parts = packageName.split('.').filter { it.isNotEmpty() }
        if (parts.size < 2) return null
        return "${parts[1]}.${parts[0]}"
    }
}
