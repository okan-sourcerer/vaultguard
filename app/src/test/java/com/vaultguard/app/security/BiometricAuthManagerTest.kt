package com.vaultguard.app.security

import android.content.Context
import io.mockk.mockk
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import javax.crypto.AEADBadTagException
import javax.crypto.spec.SecretKeySpec

/**
 * Tests for the biometric enrolment lifecycle, centred on finding #8 — the Keystore key
 * was regenerated *before* the prompt, so cancelling left stored material wrapped under a
 * key that no longer existed while `isBiometricEnabled` still reported true.
 *
 * The prompt itself needs an Activity and cannot run here; these exercise everything
 * either side of it, with a fake Keystore backed by real AES-GCM so a replaced key really
 * does make old material unwrappable.
 */
class BiometricAuthManagerTest {

    private val vaultKey = SecretKeySpec(ByteArray(32) { it.toByte() }, "AES")

    private lateinit var keystore: FakeBiometricKeystore
    private lateinit var prefs: FakeSecurePrefs
    private lateinit var manager: BiometricAuthManager

    @Before
    fun setUp() {
        keystore = FakeBiometricKeystore()
        prefs = FakeSecurePrefs()
        manager = BiometricAuthManager(mockk<Context>(relaxed = true), keystore, prefs)
    }

    /** Enrolment as it happens when the user authenticates successfully. */
    private fun enrol(key: SecretKeySpec = vaultKey) {
        manager.completeEnrolment(manager.prepareEnrolment(), key)
    }

    // -- Enabled state honesty ---------------------------------------------------------

    @Test
    fun `not enabled before anything is enrolled`() {
        assertFalse(manager.isBiometricEnabled)
    }

    @Test
    fun `enabled after a successful enrolment`() {
        enrol()

        assertTrue(manager.isBiometricEnabled)
        assertNotNull(manager.loadWrappedKey())
    }

    @Test
    fun `not enabled when the keystore key was invalidated by a new fingerprint`() {
        enrol()

        keystore.invalidated = true

        assertFalse(
            "reporting enabled here gives the user a button that cannot work",
            manager.isBiometricEnabled
        )
    }

    @Test
    fun `not enabled when the keystore key exists but nothing was wrapped`() {
        keystore.generateKey()

        assertFalse(manager.isBiometricEnabled)
    }

    // -- Finding #8: cancelling enrolment ----------------------------------------------

    @Test
    fun `preparing enrolment reuses a valid key rather than replacing it`() {
        enrol()
        val generationsAfterEnrol = keystore.generateCount

        manager.prepareEnrolment()

        assertEquals(
            "regenerating here is what orphaned the stored wrap",
            generationsAfterEnrol,
            keystore.generateCount
        )
    }

    @Test
    fun `cancelling a re-enrolment leaves the existing wrap working`() {
        // The exact #8 scenario: enable biometric, start enabling again, cancel.
        enrol()
        val wrappedBefore = manager.loadWrappedKey()!!

        manager.prepareEnrolment() // user then cancels, so no completeEnrolment

        assertTrue("biometric must still be usable", manager.isBiometricEnabled)
        assertEquals(wrappedBefore, manager.loadWrappedKey())

        // And it genuinely still unwraps to the same vault key.
        val unwrapped = keystore.decryptWith(
            keystore.decryptCipher(wrappedBefore.iv), wrappedBefore
        )
        assertArrayEquals(vaultKey.encoded, unwrapped)
    }

    @Test
    fun `preparing enrolment after invalidation clears the stale wrap`() {
        // Here the key genuinely must be replaced, so anything already stored is dead.
        // Clearing it up front means a cancellation leaves biometric honestly off rather
        // than reporting enabled while guaranteed to fail.
        enrol()
        keystore.invalidated = true

        manager.prepareEnrolment() // user cancels

        assertNull("stale wrapped key must not survive", manager.loadWrappedKey())
        assertFalse(manager.isBiometricEnabled)
    }

    @Test
    fun `re-enrolling after invalidation produces a working wrap`() {
        enrol()
        keystore.invalidated = true

        enrol()

        assertTrue(manager.isBiometricEnabled)
        val wrapped = manager.loadWrappedKey()!!
        assertArrayEquals(
            vaultKey.encoded,
            keystore.decryptWith(keystore.decryptCipher(wrapped.iv), wrapped)
        )
    }

    @Test
    fun `material wrapped under a replaced key does not unwrap`() {
        // Confirms the fake reproduces the real hazard, so the tests above mean something.
        enrol()
        val stale = manager.loadWrappedKey()!!

        keystore.generateKey()

        assertThrows(AEADBadTagException::class.java) {
            keystore.decryptWith(keystore.decryptCipher(stale.iv), stale)
        }
    }

    // -- Disabling ----------------------------------------------------------------------

    @Test
    fun `disabling removes both the keystore key and the wrapped copy`() {
        enrol()

        manager.disableBiometric()

        assertFalse(manager.isBiometricEnabled)
        assertNull(manager.loadWrappedKey())
        assertFalse(keystore.isKeyValid)
        assertEquals(1, keystore.removeCount)
    }

    @Test
    fun `disabling twice is harmless`() {
        enrol()

        manager.disableBiometric()
        manager.disableBiometric()

        assertFalse(manager.isBiometricEnabled)
    }

    @Test
    fun `disabling without an enrolment is harmless`() {
        manager.disableBiometric()

        assertFalse(manager.isBiometricEnabled)
        assertNull(manager.loadWrappedKey())
    }

    // -- Storage compatibility ------------------------------------------------------------

    @Test
    fun `wrapped key is stored under the expected names`() {
        enrol()

        assertEquals(setOf("wrapped_vault_key", "wrapped_vault_iv"), prefs.values.keys)
    }

    @Test
    fun `preferences file name is unchanged`() {
        assertEquals("biometric_prefs", BiometricAuthManager.PREFS_NAME)
    }

    @Test
    fun `re-enrolling replaces rather than accumulates`() {
        enrol()
        val first = manager.loadWrappedKey()!!

        enrol()
        val second = manager.loadWrappedKey()!!

        assertEquals(setOf("wrapped_vault_key", "wrapped_vault_iv"), prefs.values.keys)
        assertFalse("a fresh IV should be used each time", first.iv.contentEquals(second.iv))
    }

    @Test
    fun `a partially written wrap is treated as absent`() {
        enrol()
        prefs.remove(listOf("wrapped_vault_iv"))

        assertNull(manager.loadWrappedKey())
        assertFalse(manager.isBiometricEnabled)
    }
}
