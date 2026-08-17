package com.vaultguard.app.security

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Tests for the unlock throttle (finding #12).
 *
 * The two properties that matter are that the delay keeps growing — the old one stopped at
 * 32 seconds — and that it **survives a restart**, since the old counter lived in a
 * ViewModel and an attacker could reset it by killing the app.
 */
class UnlockThrottleTest {

    private lateinit var prefs: FakeSecurePrefs
    private lateinit var throttle: UnlockThrottle

    private val t0 = 1_700_000_000_000L

    @Before
    fun setUp() {
        prefs = FakeSecurePrefs()
        throttle = UnlockThrottle(prefs)
    }

    /** A restart: same stored state, brand-new object. */
    private fun afterRestart() = UnlockThrottle(prefs)

    private fun failTimes(count: Int, at: Long = t0) {
        repeat(count) { throttle.recordFailure(at) }
    }

    // -- Escalation ---------------------------------------------------------------------

    @Test
    fun `the first two failures cost nothing`() {
        assertEquals(UnlockThrottle.State.Allowed, throttle.recordFailure(t0))
        assertEquals(UnlockThrottle.State.Allowed, throttle.recordFailure(t0))
        assertEquals(UnlockThrottle.State.Allowed, throttle.state(t0))
    }

    @Test
    fun `the third failure starts a lockout`() {
        failTimes(2)

        val state = throttle.recordFailure(t0)

        assertTrue(state is UnlockThrottle.State.LockedOut)
        assertEquals(5, (state as UnlockThrottle.State.LockedOut).remainingSeconds)
    }

    @Test
    fun `the delay keeps growing well past the old ceiling`() {
        // The previous implementation capped at 32 seconds, which is no obstacle at all.
        val observed = mutableListOf<Int>()
        repeat(8) {
            val state = throttle.recordFailure(t0)
            if (state is UnlockThrottle.State.LockedOut) observed += state.remainingSeconds
        }

        assertEquals(listOf(5, 15, 60, 300, 900, 3600), observed)
    }

    @Test
    fun `the delay is capped rather than growing forever`() {
        repeat(20) { throttle.recordFailure(t0) }

        val state = throttle.state(t0) as UnlockThrottle.State.LockedOut
        assertEquals(3600, state.remainingSeconds)
    }

    // -- Expiry ---------------------------------------------------------------------------

    @Test
    fun `the lockout clears once the wait has passed`() {
        failTimes(3)

        assertTrue(throttle.state(t0) is UnlockThrottle.State.LockedOut)
        assertEquals(UnlockThrottle.State.Allowed, throttle.state(t0 + 5_000))
    }

    @Test
    fun `the remaining time counts down`() {
        failTimes(4) // 15s

        assertEquals(15, (throttle.state(t0) as UnlockThrottle.State.LockedOut).remainingSeconds)
        assertEquals(10, (throttle.state(t0 + 5_000) as UnlockThrottle.State.LockedOut).remainingSeconds)
        assertEquals(1, (throttle.state(t0 + 14_500) as UnlockThrottle.State.LockedOut).remainingSeconds)
    }

    @Test
    fun `success clears everything`() {
        failTimes(5)
        assertTrue(throttle.failedAttempts > 0)

        throttle.recordSuccess()

        assertEquals(0, throttle.failedAttempts)
        assertEquals(UnlockThrottle.State.Allowed, throttle.state(t0))
        assertEquals(UnlockThrottle.State.Allowed, afterRestart().state(t0))
    }

    // -- Surviving a restart ------------------------------------------------------------------

    @Test
    fun `the attempt count survives a restart`() {
        // The old counter lived in a ViewModel, so killing the app reset it to zero and
        // guessing could continue at full speed.
        failTimes(4)

        assertEquals(4, afterRestart().failedAttempts)
    }

    @Test
    fun `an active lockout survives a restart`() {
        failTimes(5) // 60s

        val state = afterRestart().state(t0 + 10_000)

        assertTrue(state is UnlockThrottle.State.LockedOut)
        assertEquals(50, (state as UnlockThrottle.State.LockedOut).remainingSeconds)
    }

    @Test
    fun `escalation continues across a restart`() {
        failTimes(3)

        val state = afterRestart().recordFailure(t0)

        assertEquals(15, (state as UnlockThrottle.State.LockedOut).remainingSeconds)
    }

    // -- Clock tampering ---------------------------------------------------------------------

    @Test
    fun `winding the clock back does not clear a lockout`() {
        // Wall-clock based, so this is a deterrent rather than a boundary — but it should
        // at least not hand the wait back for free.
        failTimes(5) // 60s

        val state = throttle.state(t0 - 86_400_000)

        assertTrue("a backwards clock must not unlock", state is UnlockThrottle.State.LockedOut)
    }

    @Test
    fun `winding the clock forward does end the wait`() {
        // The honest limitation, asserted so it is not mistaken for a security boundary.
        failTimes(3)

        assertEquals(UnlockThrottle.State.Allowed, throttle.state(t0 + 86_400_000))
    }

    // -- Storage ------------------------------------------------------------------------------

    @Test
    fun `nothing is stored before the first failure`() {
        assertTrue(prefs.values.isEmpty())
        assertEquals(UnlockThrottle.State.Allowed, throttle.state(t0))
    }

    @Test
    fun `throttle state does not collide with vault material`() {
        // It shares vault_secure_prefs with the salt and wrapped key.
        failTimes(3)

        assertEquals(
            setOf("unlock_failed_attempts", "unlock_locked_until", "unlock_locked_at"),
            prefs.values.keys
        )
    }
}
