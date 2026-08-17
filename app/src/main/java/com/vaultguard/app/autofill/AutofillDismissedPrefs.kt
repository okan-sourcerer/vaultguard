package com.vaultguard.app.autofill

import android.content.Context
import androidx.core.content.edit
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Remembers where the user dismissed the "save this password?" prompt.
 *
 * Two things went wrong here, and the second is the one that made the feature feel broken:
 *
 *  - dismissals were permanent, with nothing in the UI to explain or undo them
 *    (finding #34). They now expire, and Settings can clear them;
 *  - the key was `webDomain ?: packageName`. In a browser without compatibility mode the
 *    web domain is null, so the key became the *browser's* package — and tapping Skip once
 *    on a single website silenced the save prompt for everything you ever browsed
 *    (finding #49).
 *
 * The rule now is that a dismissal is only recorded when the request can be attributed to
 * a specific site or app. If a browser gives us no domain, we cannot tell one site from
 * another, so we decline to remember anything rather than over-apply it.
 */
@Singleton
class AutofillDismissedPrefs @Inject constructor(
    @ApplicationContext private val context: Context
) {
    companion object {
        private const val PREFS_NAME = "autofill_dismissed_prefs"
        private val EXPIRY_MILLIS = TimeUnit.DAYS.toMillis(30)

        private const val DOMAIN_PREFIX = "domain:"
        private const val PACKAGE_PREFIX = "pkg:"

        /**
         * @return the storage key for this request, or null when it cannot be attributed —
         *         a browser that supplied no domain, or no identifiers at all.
         */
        fun keyFor(webDomain: String?, packageName: String?): String? {
            CredentialMatcher.normaliseHost(webDomain)?.let { return DOMAIN_PREFIX + it }

            val pkg = packageName?.trim()?.lowercase()?.takeIf { it.isNotEmpty() } ?: return null

            // A browser identifies a website, not itself. Without a domain there is nothing
            // here worth remembering, and remembering the browser would silence every site.
            if (KnownBrowsers.isBrowser(pkg)) return null

            return PACKAGE_PREFIX + pkg
        }

        private fun isRecognisedKey(key: String) =
            key.startsWith(DOMAIN_PREFIX) || key.startsWith(PACKAGE_PREFIX)
    }

    private val prefs by lazy {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).also { store ->
            // Entries written before keys were prefixed are unattributable — most of them
            // are the browser-wide dismissals described above. Drop them rather than leave
            // them counting toward the total while never matching anything.
            val legacy = store.all.keys.filterNot(::isRecognisedKey)
            if (legacy.isNotEmpty()) {
                store.edit { legacy.forEach { remove(it) } }
            }
        }
    }

    fun isDismissed(
        webDomain: String?,
        packageName: String?,
        now: Long = System.currentTimeMillis()
    ): Boolean {
        val key = keyFor(webDomain, packageName) ?: return false
        val dismissedAt = prefs.getLong(key, 0L)
        if (dismissedAt == 0L) return false

        if (now - dismissedAt >= EXPIRY_MILLIS) {
            prefs.edit { remove(key) }
            return false
        }
        return true
    }

    fun markDismissed(
        webDomain: String?,
        packageName: String?,
        now: Long = System.currentTimeMillis()
    ) {
        val key = keyFor(webDomain, packageName) ?: return
        prefs.edit { putLong(key, now) }
    }

    /** @return how many dismissals were forgotten. */
    fun clearAll(): Int {
        val count = prefs.all.size
        prefs.edit { clear() }
        return count
    }

    val dismissedCount: Int get() = prefs.all.size
}
