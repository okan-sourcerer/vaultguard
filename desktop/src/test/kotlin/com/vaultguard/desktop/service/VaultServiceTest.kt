package com.vaultguard.desktop.service

import com.vaultguard.app.security.KeyDerivation
import com.vaultguard.desktop.cloud.DesktopConfig
import com.vaultguard.desktop.cloud.SavedSession
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.concurrent.TimeUnit

class AutoLockPolicyTest {

    private val fifteenMinutes = AutoLockPolicy(TimeUnit.MINUTES.toMillis(15))

    @Test
    fun `an idle vault locks at the timeout, not after it`() {
        assertFalse(fifteenMinutes.shouldLock(TimeUnit.MINUTES.toMillis(14)))
        assertTrue(fifteenMinutes.shouldLock(TimeUnit.MINUTES.toMillis(15)))
        assertTrue(fifteenMinutes.shouldLock(TimeUnit.MINUTES.toMillis(90)))
    }

    @Test
    fun `a zero timeout never locks`() {
        // For someone who would rather hold the vault open and lock it by hand. Saying
        // "never" explicitly beats setting an absurdly large number and hoping.
        assertFalse(AutoLockPolicy.NEVER.isEnabled)
        assertFalse(AutoLockPolicy.NEVER.shouldLock(Long.MAX_VALUE / 2))
        assertEquals(Long.MAX_VALUE, AutoLockPolicy.NEVER.remainingMillis(1_000))
    }

    @Test
    fun `remaining time counts down and stops at zero`() {
        assertEquals(TimeUnit.MINUTES.toMillis(15), fifteenMinutes.remainingMillis(0))
        assertEquals(TimeUnit.MINUTES.toMillis(5), fifteenMinutes.remainingMillis(TimeUnit.MINUTES.toMillis(10)))
        assertEquals(0, fifteenMinutes.remainingMillis(TimeUnit.MINUTES.toMillis(40)))
    }

    @Test
    fun `the default is longer than the phone's`() {
        // Deliberate. Five minutes suits a device that gets lost in a pocket; on a desktop
        // it mostly teaches the user to choose a shorter master password.
        assertTrue(AutoLockPolicy.DEFAULT.timeoutMillis > TimeUnit.MINUTES.toMillis(5))
    }
}

class VaultServiceTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private val config = DesktopConfig("p", "k", "id", "secret")
    private var now = 0L

    private fun sessionFile(): File = File(temporaryFolder.root, "desktop-session.json")

    private fun service(policy: AutoLockPolicy = AutoLockPolicy.DEFAULT) =
        VaultService(config, policy, clock = { now }, sessionFile = sessionFile())

    private fun writeSavedSession() {
        val salt = ByteArray(16) { 7 }
        val key = KeyDerivation().deriveKey("master".toCharArray(), salt)
        SavedSession.save(SavedSession("token", "okan@example.com"), key, salt, sessionFile())
    }

    @Test
    fun `no saved session means signed out`() {
        assertEquals(ServiceState.SIGNED_OUT, service().state)
    }

    @Test
    fun `a saved session means locked, not signed out`() {
        writeSavedSession()

        // The distinction the menu turns on: locked needs a master password, signed out
        // needs a browser.
        assertEquals(ServiceState.LOCKED, service().state)
    }

    @Test
    fun `signing out forgets the saved session`() {
        writeSavedSession()
        val service = service()

        service.signOut()

        assertEquals(ServiceState.SIGNED_OUT, service.state)
        assertFalse(sessionFile().exists())
    }

    @Test
    fun `locking while already locked is harmless`() {
        writeSavedSession()
        val service = service()

        service.lock()
        service.lock()

        assertEquals(ServiceState.LOCKED, service.state)
    }

    @Test
    fun `a locked vault is not locked again by the idle check`() {
        writeSavedSession()
        val service = service(AutoLockPolicy(1_000))
        now += 10_000

        // Nothing to lock, so nothing to announce. Returning true here would have the tray
        // claim it locked something every minute for ever.
        assertFalse(service.lockIfIdle())
    }

    @Test
    fun `state changes are announced`() {
        writeSavedSession()
        val service = service()
        val seen = mutableListOf<ServiceState>()
        service.onStateChanged = { seen += it }

        service.lock()
        service.signOut()

        // One event per transition, and no flicker through a state that was never real:
        // signing out clears the saved session before locking, so the single notification
        // already says SIGNED_OUT.
        assertEquals(listOf(ServiceState.LOCKED, ServiceState.SIGNED_OUT), seen)
    }

    @Test
    fun `idle time is measured from the last use`() {
        val service = service()
        now = 5_000
        service.touch()
        now = 12_000

        assertEquals(7_000, service.idleMillis)
    }

    @Test
    fun `reading the vault counts as using it`() {
        val service = service()
        now = 1_000
        service.touch()
        now = 60_000

        service.credentials()

        // Otherwise an extension serving credentials steadily would still be locked out
        // from under itself on the timeout.
        assertEquals(0, service.idleMillis)
    }

    @Test
    fun `remaining time reflects the policy and the clock`() {
        val service = service(AutoLockPolicy(10_000))
        now = 1_000
        service.touch()
        now = 4_000

        assertEquals(7_000, service.remainingBeforeLock())
    }
}
