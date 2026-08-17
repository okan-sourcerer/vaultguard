package com.vaultguard.app.data.remote

import android.content.Context
import androidx.core.content.edit
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Whether cloud sync is on, and how far the pull has got.
 *
 * `isEnabled` defaults to **false** and is only ever set by an explicit user action. Sync
 * used to require no consent at all: any call would sign in anonymously and upload the
 * whole vault, so pulling to refresh the list was enough to put every credential in
 * Firestore — while Settings displayed "Local only" (#15).
 *
 * Nothing here is sensitive: a boolean and an opaque cursor. The vault's own material lives
 * in the Keystore-encrypted store.
 */
@Singleton
class SyncPreferences @Inject constructor(
    @ApplicationContext private val context: Context
) {
    companion object {
        private const val PREFS_NAME = "sync_prefs"
        private const val KEY_ENABLED = "sync_enabled"
        private const val PREFIX_PULL_CURSOR = "pull_cursor_"
    }

    private val prefs by lazy { context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE) }

    var isEnabled: Boolean
        get() = prefs.getBoolean(KEY_ENABLED, false)
        set(value) = prefs.edit { putBoolean(KEY_ENABLED, value) }

    /**
     * Highest Firestore server timestamp already pulled, per account.
     *
     * Server-assigned, so it does not care what any device thinks the time is (#20).
     */
    fun pullCursor(uid: String): Long = prefs.getLong(PREFIX_PULL_CURSOR + uid, 0L)

    fun setPullCursor(uid: String, value: Long) {
        prefs.edit { putLong(PREFIX_PULL_CURSOR + uid, value) }
    }

    /** Called on sign-out and on "delete cloud data" — the next sync starts from scratch. */
    fun clearCursors() {
        prefs.edit {
            prefs.all.keys.filter { it.startsWith(PREFIX_PULL_CURSOR) }.forEach { remove(it) }
        }
    }
}
