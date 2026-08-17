package com.vaultguard.app.autofill

/**
 * Android browsers that need autofill **compatibility mode**.
 *
 * Most browsers do not implement the Autofill Framework's virtual-view API. For those, the
 * platform can synthesise a structure from the accessibility tree instead — but only if the
 * autofill service opts in per package, which happens in
 * `res/xml/autofill_service_config.xml`.
 *
 * **This list must mirror the `<compatibility-package>` entries in that file.** It is
 * duplicated here because the code needs to reason about browsers too: a fill request from
 * a browser identifies a *website*, not an app, so anything keyed on the package name — the
 * dismissal store in particular — would otherwise lump every site behind one browser
 * identity (finding #49).
 *
 * Two things worth knowing about compat mode:
 *
 *  - the web domain is recovered from the browser's URL bar, so it is only as trustworthy
 *    as that text. It is a weaker signal than a native `webDomain`;
 *  - it costs the browser performance, which is why the platform demands an explicit opt-in
 *    rather than enabling it for everything.
 */
object KnownBrowsers {

    val PACKAGES: Set<String> = setOf(
        // Chrome and Chromium builds
        "com.android.chrome",
        "com.chrome.beta",
        "com.chrome.dev",
        "com.chrome.canary",
        "org.chromium.chrome",
        // Firefox
        "org.mozilla.firefox",
        "org.mozilla.firefox_beta",
        "org.mozilla.fenix",
        "org.mozilla.focus",
        // Chromium derivatives
        "com.microsoft.emmx",
        "com.brave.browser",
        "com.brave.browser_beta",
        "com.vivaldi.browser",
        "com.kiwibrowser.browser",
        "com.sec.android.app.sbrowser",
        "com.sec.android.app.sbrowser.beta",
        "com.duckduckgo.mobile.android",
        "com.ecosia.android",
        // Opera
        "com.opera.browser",
        "com.opera.browser.beta",
        "com.opera.mini.native",
        "com.opera.touch",
        "com.opera.gx",
        // Others
        "com.yandex.browser",
        "com.UCMobile.intl",
        "com.qwant.liberty"
    )

    fun isBrowser(packageName: String?): Boolean =
        packageName != null && packageName.lowercase() in PACKAGES
}
