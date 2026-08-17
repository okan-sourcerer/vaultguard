package com.vaultguard.app.data.local

import android.content.Context
import androidx.core.content.edit
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Ordinary app settings. Nothing here is sensitive — vault material lives in the
 * Keystore-encrypted store.
 */
@Singleton
class AppPreferences @Inject constructor(
    @ApplicationContext private val context: Context
) {
    companion object {
        private const val PREFS_NAME = "app_prefs"
        private const val KEY_AUTO_LOCK_MINUTES = "auto_lock_minutes"
        const val DEFAULT_AUTO_LOCK_MINUTES = 5
    }

    private val prefs by lazy { context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE) }

    /**
     * Minutes in the background before the vault locks.
     *
     * Was held only in memory on a singleton, so it silently reverted to five minutes on
     * every launch — and Settings displayed the default too, giving no hint the choice had
     * been discarded (finding #25).
     */
    var autoLockMinutes: Int
        get() = prefs.getInt(KEY_AUTO_LOCK_MINUTES, DEFAULT_AUTO_LOCK_MINUTES)
        set(value) = prefs.edit { putInt(KEY_AUTO_LOCK_MINUTES, value) }
}
