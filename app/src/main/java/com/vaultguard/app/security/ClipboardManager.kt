package com.vaultguard.app.security

import android.content.ClipData
import android.content.Context
import android.os.PersistableBundle
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import dagger.hilt.android.qualifiers.ApplicationContext
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
    }

    fun copyWithAutoExpiry(label: String, text: String) {
        val clipboard = context.getSystemService(android.content.ClipboardManager::class.java)
        val clip = ClipData.newPlainText(label, text)
        clip.description.extras = PersistableBundle().apply {
            putBoolean("android.content.extra.IS_SENSITIVE", true)
        }
        clipboard.setPrimaryClip(clip)

        // Cancel any pending clear work, then schedule a new one
        val workManager = WorkManager.getInstance(context)
        workManager.cancelAllWorkByTag(WORK_TAG)

        val clearRequest = OneTimeWorkRequestBuilder<ClearClipboardWorker>()
            .setInitialDelay(CLEAR_DELAY_SECONDS, TimeUnit.SECONDS)
            .addTag(WORK_TAG)
            .build()
        workManager.enqueue(clearRequest)
    }
}

/**
 * WorkManager worker that clears the clipboard.
 * Survives process death — unlike Handler.postDelayed, this will
 * still fire even if the app is killed before the timer elapses.
 */
class ClearClipboardWorker(
    context: Context,
    params: WorkerParameters
) : Worker(context, params) {
    override fun doWork(): Result {
        val clipboard = applicationContext.getSystemService(android.content.ClipboardManager::class.java)
        clipboard.setPrimaryClip(ClipData.newPlainText("", ""))
        return Result.success()
    }
}
