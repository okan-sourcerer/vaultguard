package com.vaultguard.app.autofill

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import com.vaultguard.app.MainActivity

/**
 * The target of `android:settingsActivity` in the autofill service config (finding #53).
 *
 * That attribute drives the gear beside VaultGuard in Android's "Autofill service" screen.
 * It used to name `MainActivity`, which meant tapping it landed on whatever the app would
 * normally show — the unlock screen, or the vault list — with no sign that a settings
 * request had been made at all.
 *
 * The system launches this activity with an intent of its own making, so there is no way
 * to have it carry an extra. Hence a trampoline: it adds the extra itself and forwards.
 * It has no UI and finishes before it is ever drawn.
 */
class AutofillSettingsActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        startActivity(
            Intent(this, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                putExtra(MainActivity.EXTRA_OPEN_SETTINGS, true)
            }
        )

        finish()
    }
}
