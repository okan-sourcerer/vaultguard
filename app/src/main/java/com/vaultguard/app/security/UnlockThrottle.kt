package com.vaultguard.app.security

import timber.log.Timber
import javax.inject.Inject
import javax.inject.Named
import javax.inject.Singleton
import kotlin.math.max

/**
 * Escalating delay between failed master-password attempts.
 *
 * Replaces a counter that lived in `UnlockViewModel` (finding #12), which had two problems:
 * it capped at 32 seconds, and it vanished with the ViewModel — killing the app reset it to
 * zero, so an attacker could keep guessing at full speed by restarting between tries. The
 * autofill unlock path had no throttle at all.
 *
 * State lives in the Keystore-encrypted preferences and is shared by every unlock path,
 * because the throttle is only worth having if it covers all of them.
 *
 * ## What this does and does not buy
 *
 * It is a deterrent against someone tapping at an unlocked phone, not a security boundary.
 * The lockout is wall-clock based, so anyone who can change the device clock can shorten it
 * — and anyone holding an unlocked device can. The real defence against guessing is the
 * Argon2id cost in [KeyDerivation]: every attempt, throttled or not, costs 64 MiB and
 * several hundred milliseconds.
 */
@Singleton
class UnlockThrottle @Inject constructor(
    @Named(MasterPasswordManager.VAULT_PREFS) private val prefs: SecurePrefs
) {

    companion object {
        private const val KEY_FAILED_ATTEMPTS = "unlock_failed_attempts"
        private const val KEY_LOCKED_UNTIL = "unlock_locked_until"
        private const val KEY_LOCKED_AT = "unlock_locked_at"

        private val KEYS = listOf(KEY_FAILED_ATTEMPTS, KEY_LOCKED_UNTIL, KEY_LOCKED_AT)

        /**
         * Delay in seconds after the nth consecutive failure, indexed by attempt count.
         *
         * The first two are free: a mistyped password is ordinary. Beyond that it climbs
         * steeply, because a third, fourth and fifth wrong guess in a row is not how
         * someone who knows their own password behaves.
         *
         * Slot 0 is a placeholder — attempts are counted from 1 — and the labels are here
         * because getting that offset wrong is exactly the mistake this table invites.
         */
        private val BACKOFF_SECONDS = intArrayOf(
            0,   // unused: there is no attempt 0
            0,   // 1st failure
            0,   // 2nd
            5,   // 3rd
            15,  // 4th
            60,  // 5th
            300, // 6th
            900  // 7th
        )
        private const val MAX_BACKOFF_SECONDS = 3600
    }

    sealed interface State {
        data object Allowed : State
        data class LockedOut(val remainingSeconds: Int, val failedAttempts: Int) : State
    }

    val failedAttempts: Int
        get() = prefs.getString(KEY_FAILED_ATTEMPTS)?.toIntOrNull() ?: 0

    fun state(now: Long = System.currentTimeMillis()): State {
        val lockedUntil = prefs.getString(KEY_LOCKED_UNTIL)?.toLongOrNull() ?: return State.Allowed
        if (lockedUntil <= 0L) return State.Allowed

        val lockedAt = prefs.getString(KEY_LOCKED_AT)?.toLongOrNull() ?: 0L

        // A clock moved backwards past the moment the lockout began cannot be trusted to
        // say the wait is over. Hold the lockout rather than let a clock change clear it.
        if (now < lockedAt) {
            Timber.w("Device clock moved backwards during a lockout; holding it")
            return State.LockedOut(remainingSecondsFor(failedAttempts), failedAttempts)
        }

        if (now >= lockedUntil) return State.Allowed

        val remaining = ((lockedUntil - now + 999) / 1000).toInt()
        return State.LockedOut(max(remaining, 1), failedAttempts)
    }

    /** @return the state produced by this failure, so the caller can report the wait. */
    fun recordFailure(now: Long = System.currentTimeMillis()): State {
        val attempts = failedAttempts + 1
        val delaySeconds = remainingSecondsFor(attempts)

        val values = mutableMapOf(KEY_FAILED_ATTEMPTS to attempts.toString())
        if (delaySeconds > 0) {
            values[KEY_LOCKED_AT] = now.toString()
            values[KEY_LOCKED_UNTIL] = (now + delaySeconds * 1000L).toString()
        }
        prefs.putAll(values)

        return if (delaySeconds > 0) State.LockedOut(delaySeconds, attempts) else State.Allowed
    }

    fun recordSuccess() {
        prefs.remove(KEYS)
    }

    private fun remainingSecondsFor(attempts: Int): Int = when {
        attempts <= 0 -> 0
        attempts <= BACKOFF_SECONDS.lastIndex -> BACKOFF_SECONDS[attempts]
        else -> MAX_BACKOFF_SECONDS
    }
}
