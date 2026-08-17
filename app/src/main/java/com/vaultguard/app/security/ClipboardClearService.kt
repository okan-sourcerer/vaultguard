package com.vaultguard.app.security

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.vaultguard.app.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import timber.log.Timber

/**
 * Keeps the process alive long enough to actually clear the clipboard.
 *
 * ## Why a service, of all things
 *
 * Two simpler approaches were tried and neither fires:
 *
 *  - an in-process timer stops when Android freezes the process. Since Android 12 a cached
 *    app is frozen within seconds of being backgrounded, and its threads do not run, so a
 *    thirty-second `delay` simply never completes;
 *  - a WorkManager job with an initial delay goes through JobScheduler, which batches
 *    deferred work into maintenance windows. Thirty seconds is a request, not a promise,
 *    and on a battery-optimised device it can be a great deal longer.
 *
 * A foreground service is exempt from both. It is the mechanism other password managers
 * use for this, and the notification it is obliged to post is itself worth having: it tells
 * the user a password is on the clipboard and offers to clear it early.
 *
 * `shortService` is the correct type on Android 14 and above — meant for brief,
 * user-initiated work that must be allowed to finish — and unlike most types it needs no
 * additional permission.
 *
 * Note that *reading* the clipboard is restricted from Android 10 and writing is not, which
 * is why clearing works here at all while [clearIfStillOurs] usually cannot confirm what it
 * is clearing.
 */
class ClipboardClearService : Service() {

    companion object {
        private const val CHANNEL_ID = "clipboard_clear"
        private const val NOTIFICATION_ID = 4711

        private const val EXTRA_TOKEN = "token"
        private const val EXTRA_LABEL = "label"
        private const val EXTRA_DELAY_MS = "delay_ms"

        private const val ACTION_START = "com.vaultguard.CLIP_START"
        private const val ACTION_CLEAR_NOW = "com.vaultguard.CLIP_CLEAR_NOW"

        fun start(context: Context, label: String, token: String, delayMillis: Long) {
            val intent = Intent(context, ClipboardClearService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_TOKEN, token)
                putExtra(EXTRA_LABEL, label)
                putExtra(EXTRA_DELAY_MS, delayMillis)
            }
            // Started from a foreground screen — the user has just tapped copy — which is
            // what Android 14 requires for a shortService.
            runCatching { context.startForegroundService(intent) }
                .onFailure { Timber.w(it, "Could not start the clipboard-clear service") }
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var countdown: Job? = null
    private var token: String? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_CLEAR_NOW -> {
                clearAndStop()
                return START_NOT_STICKY
            }

            ACTION_START -> {
                token = intent.getStringExtra(EXTRA_TOKEN)
                val label = intent.getStringExtra(EXTRA_LABEL) ?: "Password"
                val delayMillis = intent.getLongExtra(EXTRA_DELAY_MS, 30_000L)

                startForegroundCompat(buildNotification(label, delayMillis))

                countdown?.cancel()
                countdown = scope.launch {
                    delay(delayMillis)
                    clearAndStop()
                }
            }
        }
        return START_NOT_STICKY
    }

    /**
     * Android 14 gives a shortService a few minutes and then calls this. Well beyond the
     * window this service uses, but if it ever arrives the right answer is the same one:
     * clear and get out.
     */
    override fun onTimeout(startId: Int) {
        Timber.w("Clipboard-clear service timed out; clearing now")
        clearAndStop()
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun clearAndStop() {
        clearIfStillOurs(applicationContext, token)
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun startForegroundCompat(notification: Notification) {
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SHORT_SERVICE
        } else {
            0
        }
        runCatching { ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, type) }
            .onFailure {
                // If the platform refuses, the clipboard is more important than the
                // service: clear immediately rather than leave a password sitting there.
                Timber.w(it, "Could not enter the foreground; clearing immediately")
                clearIfStillOurs(applicationContext, token)
                stopSelf()
            }
    }

    private fun buildNotification(label: String, delayMillis: Long): Notification {
        val manager = getSystemService(NotificationManager::class.java)
        // Low importance: informative, not something to interrupt for.
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                "Clipboard",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Shown while a copied password is still on the clipboard"
                setShowBadge(false)
            }
        )

        val clearNow = PendingIntent.getService(
            this,
            0,
            Intent(this, ClipboardClearService::class.java).setAction(ACTION_CLEAR_NOW),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.icon_vault_splash)
            .setContentTitle("$label copied")
            .setContentText("Clearing the clipboard in ${delayMillis / 1000} seconds")
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .setSilent(true)
            // Nothing sensitive on the lock screen: the label says "Password copied", never
            // which entry or what it was.
            .setVisibility(NotificationCompat.VISIBILITY_SECRET)
            .addAction(0, "Clear now", clearNow)
            .build()
    }
}
