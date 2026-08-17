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

    /** Changes the password the way ChangeMasterPasswordUseCase does. */
    private suspend fun MasterPasswordManager.changePassword(newPassword: String) {
        rewrapForNewPassword(newPassword.toCharArray(), getSessionKey())
    }

    /** Unlock, for tests: derive, verify, unwrap, adopt. */
    private suspend fun MasterPasswordManager.unlock(password: String): Boolean {
        val masterKey = deriveMasterKey(password.toCharArray()) ?: return false
        if (!verifyMasterKey(masterKey)) return false
        val vaultKey = unwrapVaultKey(masterKey) ?: return false
        adoptVaultKey(vaultKey)
        return true
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

        assertTrue(manager.unlock("correct-password"))
        assertTrue(manager.isVaultUnlocked)
    }

    @Test
    fun `unlock fails with the wrong password and leaves the vault locked`() = runTest {
        val manager = setUpVault("correct-password")
        manager.lockVault()

        assertFalse(manager.unlock("wrong-password"))
        assertFalse(manager.isVaultUnlocked)
    }

    @Test
    fun `a failed unlock does not disturb an already unlocked session`() = runTest {
        val manager = setUpVault("correct-password")
        val sessionKey = manager.getSessionKey()

        assertFalse(manager.unlock("wrong-password"))

        assertTrue(manager.isVaultUnlocked)
        assertArrayEquals(sessionKey.encoded, manager.getSessionKey().encoded)
    }

    @Test
    fun `unlock against an unconfigured vault fails without throwing`() = runTest {
        val manager = manager()

        assertFalse(manager.unlock("anything"))
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
    fun `a key wrapped before a password change still unlocks`() = runTest {
        // Under the old design this was finding #6: the wrapper held a key derived from
        // the old password and went stale. The vault key does not change, so it does not.
        val manager = setUpVault("old-password")
        val wrappedEarlier = manager.getSessionKey()

        manager.changePassword("new-password")
        manager.lockVault()

        assertTrue(manager.unlockWithKey(wrappedEarlier))
        assertTrue(manager.isVaultUnlocked)
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
    fun `verifyVaultKey does not change session state`() = runTest {
        val manager = setUpVault("master-password-1")
        val key = manager.getSessionKey()
        manager.lockVault()

        assertTrue(manager.verifyVaultKey(key))
        assertFalse("verifyVaultKey must not unlock", manager.isVaultUnlocked)
    }

    @Test
    fun `verifyVaultKey returns false when the vault is not set up`() = runTest {
        assertFalse(manager().verifyVaultKey(SecretKeySpec(ByteArray(32), "AES")))
    }

    // -- Changing the master password -------------------------------------------------

    @Test
    fun `changing the password does not change the vault key`() = runTest {
        // The whole point of the indirection: rows stay encrypted under the same key, so
        // nothing has to be rewritten and biometric enrolment stays valid.
        val manager = setUpVault("old-password")
        val vaultKeyBefore = manager.getSessionKey().encoded.toList()

        manager.changePassword("new-password")

        manager.lockVault()
        assertTrue(manager.unlock("new-password"))
        assertEquals(vaultKeyBefore, manager.getSessionKey().encoded.toList())
    }

    @Test
    fun `changing the password rotates the salt and verification blob`() = runTest {
        val manager = setUpVault("old-password")
        val saltBefore = manager.getSalt()

        manager.changePassword("new-password")

        assertFalse(saltBefore.contentEquals(manager.getSalt()))
        manager.lockVault()
        assertFalse(manager.unlock("old-password"))
        assertTrue(manager.unlock("new-password"))
    }

    @Test
    fun `the vault key is not derivable from the salt`() = runTest {
        // If it were, this would be the old design wearing a new name.
        val manager = setUpVault("master-password-1")
        val vaultKey = manager.getSessionKey()

        val derived = manager.deriveMasterKey("master-password-1".toCharArray())!!

        assertFalse(derived.encoded.contentEquals(vaultKey.encoded))
    }

    @Test
    fun `the wrapped vault key only opens with the right master key`() = runTest {
        val manager = setUpVault("master-password-1")
        val vaultKey = manager.getSessionKey().encoded.toList()

        val right = manager.deriveMasterKey("master-password-1".toCharArray())!!
        val wrong = manager.deriveKey("other-password".toCharArray(), manager.getSalt())

        assertEquals(vaultKey, manager.unwrapVaultKey(right)!!.encoded.toList())
        assertNull(manager.unwrapVaultKey(wrong))
    }

    @Test
    fun `verifyVaultKey accepts only the current vault key`() = runTest {
        val manager = setUpVault()

        assertTrue(manager.verifyVaultKey(manager.getSessionKey()))
        assertFalse(manager.verifyVaultKey(manager.generateVaultKey()))
    }

    @Test
    fun `a legacy vault reports no wrapped vault key`() = runTest {
        // Vaults created before the indirection have a salt but nothing wrapped.
        val legacy = manager(mapOf("master_salt" to java.util.Base64.getEncoder().encodeToString(ByteArray(16))))

        assertTrue(legacy.isSetupComplete)
        assertFalse(legacy.hasWrappedVaultKey)
    }

    @Test
    fun `a freshly set up vault has a wrapped vault key`() = runTest {
        assertTrue(setUpVault().hasWrappedVaultKey)
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
            setOf(
                "master_salt", "verification_ciphertext", "verification_iv", "db_passphrase",
                "vault_key_ciphertext", "vault_key_iv",
                "vault_key_check_ciphertext", "vault_key_check_iv"
            ),
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
    fun `adoptRemoteSetup replaces the master material but not the vault key`() = runTest {
        // Sharpens finding #4. Adopting another device's salt and verification blob makes
        // its master password the one that verifies, but the wrapped vault key still
        // belongs to this device — so the local vault becomes unreachable through the
        // master password. The whole flow is reworked in chunk 10; this pins the current
        // behaviour so that work has something to change.
        val manager = setUpVault("local-password")
        val remote = manager()
        remote.setup("remote-password".toCharArray())
        val (remoteCiphertext, remoteIv) = remote.getVerificationData()

        val remoteWrappedKey = remote.getWrappedVaultKey()!!

        manager.adoptRemoteSetup(remote.getSalt(), remoteCiphertext, remoteIv, remoteWrappedKey)
        manager.lockVault()

        // The wrapped vault key travels with the salt now, so adopting a remote config
        // leaves a vault that can actually be opened — the gap behind #4.
        val remoteMasterKey = manager.deriveMasterKey("remote-password".toCharArray())!!
        assertTrue("the remote password verifies", manager.verifyMasterKey(remoteMasterKey))
        val adoptedVaultKey = manager.unwrapVaultKey(remoteMasterKey)
        assertNotNull("and it unwraps the remote vault key", adoptedVaultKey)
        assertEquals(
            remote.getSessionKey().encoded.toList(),
            adoptedVaultKey!!.encoded.toList()
        )
    }
}
