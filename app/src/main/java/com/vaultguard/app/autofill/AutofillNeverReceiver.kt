package com.vaultguard.app.autofill

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import dagger.hilt.android.AndroidEntryPoint
import timber.log.Timber
import javax.inject.Inject

/**
 * Fired by the platform when the user taps **Never** on its own "Save to VaultGuard?"
 * bar.
 *
 * That bar is the platform's, drawn because the fill response carried a `SaveInfo`, and
 * it appears before anything of ours does. Dismissing it never reached this app, so
 * nothing was remembered and it came back on the next login - which is what "I dismissed
 * it and it keeps showing" looked like from the outside. `SaveInfo.setNegativeAction`
 * with the NEVER style turns the button into one that tells us, and the dismissal is then
 * recorded the same way as Skip on our own screen: attributed to the site or app, and for
 * thirty days.
 */
@AndroidEntryPoint
class AutofillNeverReceiver : BroadcastReceiver() {

    @Inject lateinit var dismissedPrefs: AutofillDismissedPrefs

    override fun onReceive(context: Context, intent: Intent) {
        val webDomain = intent.getStringExtra(EXTRA_WEB_DOMAIN)?.ifEmpty { null }
        val packageName = intent.getStringExtra(EXTRA_PACKAGE_NAME)?.ifEmpty { null }
        dismissedPrefs.markDismissed(webDomain, packageName)
        Timber.i("Save prompt declined for %s", webDomain ?: packageName ?: "(unattributed)")
    }

    companion object {
        const val ACTION = "com.vaultguard.app.autofill.NEVER_SAVE"
        const val EXTRA_WEB_DOMAIN = "web_domain"
        const val EXTRA_PACKAGE_NAME = "package_name"
    }
}
