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
 * Dismissals **expire** and can be cleared from Settings. They used to be permanent with
 * no way back: one tap on Skip and VaultGuard never offered to save for that site again,
 * with nothing in the UI to explain why or undo it (finding #34). Skipping once usually
 * means "not this password", not "never ask again".
 */
@Singleton
class AutofillDismissedPrefs @Inject constructor(
    @ApplicationContext private val context: Context
) {
    companion object {
        private const val PREFS_NAME = "autofill_dismissed_prefs"
        private val EXPIRY_MILLIS = TimeUnit.DAYS.toMillis(30)
    }

    private val prefs by lazy {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    /** Uses the web domain when available, falling back to the package name. */
    private fun keyFor(webDomain: String?, packageName: String?): String? {
        val domain = webDomain?.takeIf { it.isNotBlank() }
        val pkg = packageName?.takeIf { it.isNotBlank() }
        return domain ?: pkg
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
