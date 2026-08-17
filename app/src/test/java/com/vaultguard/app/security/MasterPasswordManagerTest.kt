package com.vaultguard.app.security

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Test
import javax.crypto.spec.SecretKeySpec

/**
 * Tests for session-key handling, focused on finding #7 — `unlockWithKey` used to adopt
 * any key handed to it without checking that it opened the vault.
 *
 * Also pins the preference keys. They are a compatibility surface: renaming one presents
 * to the user as "wrong master password" against a password that is perfectly correct.
 */
class MasterPasswordManagerTest {

    private val crypto = CryptoManager()
    private val keyDerivation = KeyDerivation()
    private lateinit var prefs: FakeSecurePrefs

    private fun manager(existing: Map<String, String> = emptyMap()): MasterPasswordManager {
        prefs = FakeSecurePrefs(existing)
        return MasterPasswordManager(prefs, crypto, keyDerivation, UnconfinedTestDispatcher())
    }

    private suspend fun setUpVault(password: String = "master-password-1"): MasterPasswordManager {
        val manager = manager()
        manager.setup(password.toCharArray())
        return manager
    }

    /** Runs a full two-phase change the way ChangeMasterPasswordUseCase does. */
    private suspend fun MasterPasswordManager.changePassword(newPassword: String) {
        val (key, pending) = prepareChange(newPassword.toCharArray())
        beginChange(pending)
        commitChange()
        adoptProvenKey(key)
    }

    // -- Setup and unlock ------------------------------------------------------------

    @Test
    fun `setup stores salt and verification data and leaves the vault unlocked`() = runTest {
        val manager = setUpVault()

        assertTrue(manager.isSetupComplete)
        assertTrue(manager.isVaultUnlocked)
        assertEquals(16, manager.getSalt().size)
        assertNotNull(manager.getVerificationData())
    }

    @Test
    fun `setup zeroes the caller's password`() = runTest {
        val password = "master-password-1".toCharArray()

        manager().setup(password)

        assertArrayEquals(CharArray(password.size) { '\u0000' }, password)
    }

    @Test
    fun `unlock succeeds with the correct password`() = runTest {
        val manager = setUpVault("correct-password")
        manager.lockVault()

        assertTrue(manager.unlock("correct-password".toCharArray()))
        assertTrue(manager.isVaultUnlocked)
    }

    @Test
    fun `unlock fails with the wrong password and leaves the vault locked`() = runTest {
        val manager = setUpVault("correct-password")
        manager.lockVault()

        assertFalse(manager.unlock("wrong-password".toCharArray()))
        assertFalse(manager.isVaultUnlocked)
    }

    @Test
    fun `a failed unlock does not disturb an already unlocked session`() = runTest {
        val manager = setUpVault("correct-password")
        val sessionKey = manager.getSessionKey()

        assertFalse(manager.unlock("wrong-password".toCharArray()))

        assertTrue(manager.isVaultUnlocked)
        assertArrayEquals(sessionKey.encoded, manager.getSessionKey().encoded)
    }

    @Test
    fun `unlock against an unconfigured vault fails without throwing`() = runTest {
        val manager = manager()

        assertFalse(manager.unlock("anything".toCharArray()))
    }

    @Test
    fun `getSessionKey throws while locked`() = runTest {
        val manager = setUpVault()
        manager.lockVault()

        assertThrows(IllegalStateException::class.java) { manager.getSessionKey() }
    }

    // -- unlockWithKey — finding #7 ---------------------------------------------------

    @Test
    fun `unlockWithKey accepts a key that opens the verification blob`() = runTest {
        val manager = setUpVault("master-password-1")
        val goodKey = manager.getSessionKey()
        manager.lockVault()

        assertTrue(manager.unlockWithKey(goodKey))
        assertTrue(manager.isVaultUnlocked)
    }

    @Test
    fun `unlockWithKey rejects an unrelated key`() = runTest {
        // The regression test for #7. This used to assign the key unconditionally, so the
        // vault "unlocked" and then failed to decrypt every row — presenting as empty.
        val manager = setUpVault("master-password-1")
        manager.lockVault()

        val strangerKey = SecretKeySpec(ByteArray(32) { 0x42 }, "AES")

        assertFalse(manager.unlockWithKey(strangerKey))
        assertFalse("vault must stay locked after a rejected key", manager.isVaultUnlocked)
    }

    @Test
    fun `unlockWithKey rejects the key derived from the previous master password`() = runTest {
        // The realistic path into #7: a master-password change leaves the biometric
        // wrapper holding the old key (finding #6).
        val manager = setUpVault("old-password")
        val oldKey = manager.getSessionKey()

        manager.changePassword("new-password")
        manager.lockVault()

        assertFalse(manager.unlockWithKey(oldKey))
        assertFalse(manager.isVaultUnlocked)
    }

    @Test
    fun `a rejected key leaves an existing session untouched`() = runTest {
        val manager = setUpVault("master-password-1")
        val goodKey = manager.getSessionKey()

        assertFalse(manager.unlockWithKey(SecretKeySpec(ByteArray(32), "AES")))

        assertTrue(manager.isVaultUnlocked)
        assertArrayEquals(goodKey.encoded, manager.getSessionKey().encoded)
    }

    @Test
    fun `verifyKey does not change session state`() = runTest {
        val manager = setUpVault("master-password-1")
        val key = manager.getSessionKey()
        manager.lockVault()

        assertTrue(manager.verifyKey(key))
        assertFalse("verifyKey must not unlock", manager.isVaultUnlocked)
    }

    @Test
    fun `verifyKey returns false when the vault is not set up`() = runTest {
        assertFalse(manager().verifyKey(SecretKeySpec(ByteArray(32), "AES")))
    }

    // -- Changing the master password -------------------------------------------------

    @Test
    fun `a committed change rotates salt and verification`() = runTest {
        val manager = setUpVault("old-password")
        val oldSalt = manager.getSalt()

        manager.changePassword("new-password")

        assertFalse(oldSalt.contentEquals(manager.getSalt()))
        manager.lockVault()
        assertTrue(manager.unlock("new-password".toCharArray()))
        manager.lockVault()
        assertFalse(manager.unlock("old-password".toCharArray()))
    }

    @Test
    fun `an aborted change leaves the current password in force`() = runTest {
        val manager = setUpVault("old-password")
        val saltBefore = manager.getSalt()

        val (_, pending) = manager.prepareChange("new-password".toCharArray())
        manager.beginChange(pending)
        manager.abortChange()

        assertNull(manager.pendingChange)
        assertArrayEquals(saltBefore, manager.getSalt())
        manager.lockVault()
        assertTrue(manager.unlock("old-password".toCharArray()))
        manager.lockVault()
        assertFalse(manager.unlock("new-password".toCharArray()))
    }

    @Test
    fun `beginChange records pending material without disturbing the current password`() = runTest {
        val manager = setUpVault("old-password")
        val saltBefore = manager.getSalt()

        val (_, pending) = manager.prepareChange("new-password".toCharArray())
        manager.beginChange(pending)

        assertEquals(pending, manager.pendingChange)
        assertArrayEquals("current salt must not move until commit", saltBefore, manager.getSalt())
        manager.lockVault()
        assertTrue("old password still opens the vault", manager.unlock("old-password".toCharArray()))
    }

    @Test
    fun `commitChange promotes pending material and clears it`() = runTest {
        val manager = setUpVault("old-password")

        val (_, pending) = manager.prepareChange("new-password".toCharArray())
        manager.beginChange(pending)
        manager.commitChange()

        assertNull(manager.pendingChange)
        assertArrayEquals(pending.salt, manager.getSalt())
        manager.lockVault()
        assertTrue(manager.unlock("new-password".toCharArray()))
    }

    @Test
    fun `commitChange with nothing pending is a no-op`() = runTest {
        val manager = setUpVault("old-password")
        val saltBefore = manager.getSalt()

        manager.commitChange()

        assertArrayEquals(saltBefore, manager.getSalt())
    }

    // -- Database passphrase -----------------------------------------------------------

    @Test
    fun `database passphrase is generated once and then reused`() = runTest {
        val manager = manager()

        val first = manager.getDatabasePassphrase()
        val second = manager.getDatabasePassphrase()

        assertEquals(32, first.size)
        assertArrayEquals(first, second)
    }

    @Test
    fun `database passphrase survives a master password change`() = runTest {
        // The SQLCipher key is independent of the master password. If a change rotated it,
        // the database would stop opening — see docs/SECURITY.md.
        val manager = setUpVault("old-password")
        val passphrase = manager.getDatabasePassphrase()

        manager.changePassword("new-password")

        assertArrayEquals(passphrase, manager.getDatabasePassphrase())
    }

    // -- Storage compatibility ----------------------------------------------------------

    @Test
    fun `persisted keys keep their names`() = runTest {
        // Renaming any of these silently orphans an existing vault.
        val manager = setUpVault()
        manager.getDatabasePassphrase()

        assertEquals(
            setOf("master_salt", "verification_ciphertext", "verification_iv", "db_passphrase"),
            prefs.values.keys
        )
    }

    @Test
    fun `preferences file name is unchanged`() = runTest {
        assertEquals("vault_secure_prefs", MasterPasswordManager.PREFS_NAME)
    }

    @Test
    fun `stored values are standard padded base64`() = runTest {
        // The class moved from android.util.Base64 NO_WRAP to java.util.Base64. They agree
        // on the standard alphabet with padding and no line breaks; this pins that so
        // values written by earlier builds stay readable.
        val manager = setUpVault()

        val salt = prefs.values.getValue("master_salt")
        assertFalse("no line wrapping", salt.contains("\n"))
        assertTrue("standard alphabet", salt.all { it.isLetterOrDigit() || it in "+/=" })
        assertArrayEquals(manager.getSalt(), java.util.Base64.getDecoder().decode(salt))
    }

    // -- Lock signalling -----------------------------------------------------------------

    @Test
    fun `lockVault clears the key and raises a one-shot event`() = runTest {
        val manager = setUpVault()

        manager.lockVault("timed out")

        assertFalse(manager.isVaultUnlocked)
        assertTrue(manager.vaultLocked.value)
        assertEquals("timed out", manager.pendingLockMessage.value)

        manager.consumeLockEvent()
        manager.consumeLockMessage()

        assertFalse(manager.vaultLocked.value)
        assertNull(manager.pendingLockMessage.value)
    }

    @Test
    fun `adoptRemoteSetup replaces salt and verification`() = runTest {
        val manager = setUpVault("local-password")
        val remote = manager()
        remote.setup("remote-password".toCharArray())
        val remoteSalt = remote.getSalt()
        val (remoteCiphertext, remoteIv) = remote.getVerificationData()

        manager.adoptRemoteSetup(remoteSalt, remoteCiphertext, remoteIv)
        manager.lockVault()

        assertTrue(manager.unlock("remote-password".toCharArray()))
        manager.lockVault()
        assertFalse(manager.unlock("local-password".toCharArray()))
    }
}
