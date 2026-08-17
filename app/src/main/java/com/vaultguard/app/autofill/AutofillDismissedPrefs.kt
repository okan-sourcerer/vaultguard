package com.vaultguard.app.autofill

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import androidx.core.content.edit

@Singleton
class AutofillDismissedPrefs @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val prefs by lazy {
        context.getSharedPreferences("autofill_dismissed_prefs", Context.MODE_PRIVATE)
    }

    /**
     * Returns a stable key for the given app/website identifiers.
     * Uses webDomain when available, falls back to packageName.
     */
    private fun keyFor(webDomain: String?, packageName: String?): String? {
        val domain = webDomain?.takeIf { it.isNotBlank() }
        val pkg = packageName?.takeIf { it.isNotBlank() }
        return domain ?: pkg
    }

    fun isDismissed(webDomain: String?, packageName: String?): Boolean {
        val key = keyFor(webDomain, packageName) ?: return false
        return prefs.getBoolean(key, false)
    }

    fun markDismissed(webDomain: String?, packageName: String?) {
        val key = keyFor(webDomain, packageName) ?: return
        prefs.edit { putBoolean(key, true) }
    }
}
