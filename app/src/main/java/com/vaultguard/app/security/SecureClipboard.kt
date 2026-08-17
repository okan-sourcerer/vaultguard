package com.vaultguard.app.security

import android.content.ClipData
import android.content.Context
import android.os.PersistableBundle
import androidx.work.Data
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import timber.log.Timber
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SecureClipboard @Inject constructor(
    @ApplicationContext private val context: Context
) {
    companion object {
        private const val CLEAR_DELAY_SECONDS = 30L
        private const val WORK_TAG = "clipboard_clear"

        internal const val EXTRA_TOKEN = "com.vaultguard.clip.token"
        internal const val DATA_TOKEN = "token"
    }

    private val scope = CoroutineScope(Dispatchers.Main)
    private var pendingClear: Job? = null

    /**
     * Copies [text] and clears it again after 30 seconds.
     *
     * The clip is tagged with a token so the clear can tell VaultGuard's own copy from
     * whatever the user put on the clipboard afterwards (finding #36) — see
     * [clearIfStillOurs] for how far that goes, which is not as far as one would like.
     */
    fun copyWithAutoExpiry(label: String, text: String) {
        val token = System.nanoTime().toString()

        val clipboard = context.getSystemService(android.content.ClipboardManager::class.java)
        val clip = ClipData.newPlainText("VaultGuard · $label", text).apply {
            description.extras = PersistableBundle().apply {
                putBoolean("android.content.extra.IS_SENSITIVE", true)
                putString(EXTRA_TOKEN, token)
            }
        }
        clipboard.setPrimaryClip(clip)

        // Two timers, because neither alone is good enough.
        //
        // WorkManager survives the process being killed, but it schedules on its own terms
        // and can run well past the requested delay — fine for housekeeping, not for a
        // window the user was told is thirty seconds.
        //
        // The in-process timer is punctual but dies with the process. Whichever fires first
        // does the work; the other then finds an empty or foreign clipboard and does
        // nothing.
        pendingClear?.cancel()
        pendingClear = scope.launch {
            delay(TimeUnit.SECONDS.toMillis(CLEAR_DELAY_SECONDS))
            clearIfStillOurs(context, token)
        }

        val workManager = WorkManager.getInstance(context)
        workManager.cancelAllWorkByTag(WORK_TAG)
        workManager.enqueue(
            OneTimeWorkRequestBuilder<ClearClipboardWorker>()
                .setInitialDelay(CLEAR_DELAY_SECONDS, TimeUnit.SECONDS)
                .setInputData(Data.Builder().putString(DATA_TOKEN, token).build())
                .addTag(WORK_TAG)
                .build()
        )
    }
}

/**
 * Backstop for [SecureClipboard]'s in-process timer: survives the process being killed
 * before the window elapses. Delegates to the same routine so both paths behave alike.
 */
class ClearClipboardWorker(
    context: Context,
    params: WorkerParameters
) : Worker(context, params) {

    override fun doWork(): Result {
        clearIfStillOurs(applicationContext, inputData.getString(SecureClipboard.DATA_TOKEN))
        return Result.success()
    }
}

/**
 * Clears the clipboard unless it can be positively identified as somebody else's.
 *
 * The token in the clip description is there so that copying something else within the
 * window is not wiped along with the password (finding #36). Reading it back only works
 * while the app has focus: from Android 10 the clipboard restriction covers
 * `getPrimaryClipDescription()` and `hasPrimaryClip()`, not merely `getPrimaryClip()`, and
 * neither a background worker nor a backgrounded app has focus.
 *
 * The first attempt at #36 read an unreadable description as "not ours" and skipped
 * clearing — which on Android 10 and above meant it *never* cleared, leaving passwords on
 * the clipboard indefinitely. That is the failure this class exists to prevent, and a worse
 * one than the collateral it was guarding against.
 *
 * So the test is inverted: skip only on a **positive** identification of another clip. When
 * the description cannot be read, clear. On most devices that means the token rarely gets
 * consulted and something copied inside the window may be lost; the alternative is a
 * password sitting on the clipboard for the rest of the day.
 */
internal fun clearIfStillOurs(context: Context, expectedToken: String?) {
    val clipboard = context.getSystemService(android.content.ClipboardManager::class.java) ?: return

    val description = clipboard.primaryClipDescription
    val token = description?.extras?.getString(SecureClipboard.EXTRA_TOKEN)

    if (description != null && token != null && token != expectedToken) {
        Timber.d("Clipboard now holds another app's clip; leaving it alone")
        return
    }

    // clearPrimaryClip is API 28+, which minSdk guarantees, but OEM builds have been known
    // to refuse it. Overwriting with an empty clip is the fallback, and neither path may
    // throw out of a background worker.
    runCatching { clipboard.clearPrimaryClip() }
        .recoverCatching { clipboard.setPrimaryClip(ClipData.newPlainText("", "")) }
        .onFailure { Timber.w(it, "Could not clear the clipboard") }
}
