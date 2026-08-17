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

    /**
     * Copies [text], then clears it again 30 seconds later — but only if it is still the
     * thing on the clipboard.
     *
     * The clear used to fire unconditionally, so copying a password and then copying
     * anything else within the window meant VaultGuard wiped the *other* thing (finding
     * #36). A token in the clip description identifies our own copy.
     *
     * The token is why this cannot simply compare the text: from Android 10 an app may not
     * read clipboard **contents** unless it has focus, and a background worker never does.
     * The clip *description* stays readable, so the marker goes there.
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
 * Clears the clipboard, if what is on it is still what VaultGuard put there.
 *
 * WorkManager rather than a delayed handler, so it survives the process being killed
 * before the timer elapses.
 */
class ClearClipboardWorker(
    context: Context,
    params: WorkerParameters
) : Worker(context, params) {

    override fun doWork(): Result {
        val expected = inputData.getString(SecureClipboard.DATA_TOKEN) ?: return Result.success()
        val clipboard = applicationContext
            .getSystemService(android.content.ClipboardManager::class.java)
            ?: return Result.success()

        // Only the description is legible to a background app; contents are not.
        val actual = clipboard.primaryClipDescription
            ?.extras
            ?.getString(SecureClipboard.EXTRA_TOKEN)

        if (actual != expected) {
            Timber.d("Clipboard holds something else now; leaving it alone")
            return Result.success()
        }

        runCatching { clipboard.clearPrimaryClip() }
            .onFailure {
                // clearPrimaryClip is API 28+, which minSdk guarantees, but a manufacturer
                // build refusing it should not crash a background worker.
                Timber.w(it, "Could not clear the clipboard")
            }
        return Result.success()
    }
}
