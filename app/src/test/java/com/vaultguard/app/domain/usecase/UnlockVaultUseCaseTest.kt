package com.vaultguard.app.domain.usecase

import com.vaultguard.app.data.local.db.dao.CredentialDao
import com.vaultguard.app.data.local.db.entity.CredentialEntity
import com.vaultguard.app.data.repository.CredentialPayloadCodec
import com.vaultguard.app.domain.model.Credential
import com.vaultguard.app.security.CryptoManager
import com.vaultguard.app.security.FakeSecurePrefs
import com.vaultguard.app.security.KeyDerivation
import com.vaultguard.app.security.MasterPasswordManager
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import javax.crypto.SecretKey

/**
 * Tests for recovery from an interrupted master-password change (finding #5).
 *
 * A change writes to two stores that cannot share a transaction — the salt in encrypted
 * preferences, the rows in SQLCipher. Ordering and rollback handle exceptions, but process
 * death between the two commits cannot be ordered away. These tests simulate exactly that
 * by leaving a pending marker in place with the rows on one side or the other, and assert
 * the next unlock resolves it rather than locking the user out.
 */
class UnlockVaultUseCaseTest {

    private val crypto = CryptoManager()
    private val keyDerivation = KeyDerivation()
    private val dao = mockk<CredentialDao>()
    private val dispatcher = UnconfinedTestDispatcher()

    private lateinit var prefs: FakeSecurePrefs
    private lateinit var manager: MasterPasswordManager
    private lateinit var useCase: UnlockVaultUseCase
    private lateinit var stored: MutableList<CredentialEntity>

    @Before
    fun setUp() = runBlocking {
        prefs = FakeSecurePrefs()
        manager = MasterPasswordManager(prefs, crypto, keyDerivation, dispatcher)
        manager.setup("old-password".toCharArray())
        stored = mutableListOf()
        coEvery { dao.getAll() } answers { stored.toList() }
        useCase = UnlockVaultUseCase(manager, dao, crypto)
        Unit
    }

    private fun rowsEncryptedWith(key: SecretKey, count: Int = 3) {
        stored.clear()
        repeat(count) { index ->
            val payload = CredentialPayloadCodec
                .encode(Credential(id = "id-$index", siteName = "Site $index"))
                .toByteArray()
            val sealed = crypto.encrypt(payload, key)
            stored += CredentialEntity("id-$index", sealed.ciphertext, sealed.iv, 1, 2)
        }
    }

    /**
     * Reproduces a crash between the two commits.
     *
     * @param rowsMigrated whether the row write landed before the process died.
     */
    private suspend fun interruptChange(newPassword: String, rowsMigrated: Boolean): SecretKey {
        val oldKey = manager.getSessionKey()
        val (newKey, pending) = manager.prepareChange(newPassword.toCharArray())
        manager.beginChange(pending)
        rowsEncryptedWith(if (rowsMigrated) newKey else oldKey)
        manager.lockVault()
        return newKey
    }

    // -- No pending change: ordinary behaviour ------------------------------------------

    @Test
    fun `unlocks normally with the right password`() = runTest {
        rowsEncryptedWith(manager.getSessionKey())
        manager.lockVault()

        assertEquals(UnlockVaultUseCase.Result.Success, useCase("old-password".toCharArray()))
        assertTrue(manager.isVaultUnlocked)
    }

    @Test
    fun `rejects the wrong password`() = runTest {
        manager.lockVault()

        assertEquals(UnlockVaultUseCase.Result.WrongPassword, useCase("nope".toCharArray()))
        assertFalse(manager.isVaultUnlocked)
    }

    // -- Interrupted after the rows were written ------------------------------------------

    @Test
    fun `new password completes a change whose rows already landed`() = runTest {
        interruptChange("new-password", rowsMigrated = true)

        val result = useCase("new-password".toCharArray())

        assertEquals(UnlockVaultUseCase.Result.Success, result)
        assertTrue(manager.isVaultUnlocked)
        assertNull("the marker must be resolved, not left behind", manager.pendingChange)
        // And the change is now genuinely complete.
        manager.lockVault()
        assertTrue(manager.unlock("new-password".toCharArray()))
    }

    @Test
    fun `old password is redirected when the rows already moved`() = runTest {
        interruptChange("new-password", rowsMigrated = true)

        val result = useCase("old-password".toCharArray())

        assertTrue(result is UnlockVaultUseCase.Result.NeedsOtherPassword)
        assertTrue((result as UnlockVaultUseCase.Result.NeedsOtherPassword).message.contains("NEW"))
        assertFalse(manager.isVaultUnlocked)
        // Nothing resolved yet, so recovery is still possible.
        assertTrue(manager.pendingChange != null)
    }

    // -- Interrupted before the rows were written -------------------------------------------

    @Test
    fun `old password undoes a change whose rows never landed`() = runTest {
        interruptChange("new-password", rowsMigrated = false)

        val result = useCase("old-password".toCharArray())

        assertEquals(UnlockVaultUseCase.Result.Success, result)
        assertTrue(manager.isVaultUnlocked)
        assertNull(manager.pendingChange)
        // The old password is in force again.
        manager.lockVault()
        assertTrue(manager.unlock("old-password".toCharArray()))
        manager.lockVault()
        assertFalse(manager.unlock("new-password".toCharArray()))
    }

    @Test
    fun `new password is redirected when the rows never moved`() = runTest {
        interruptChange("new-password", rowsMigrated = false)

        val result = useCase("new-password".toCharArray())

        assertTrue(result is UnlockVaultUseCase.Result.NeedsOtherPassword)
        assertTrue((result as UnlockVaultUseCase.Result.NeedsOtherPassword).message.contains("PREVIOUS"))
        assertFalse(manager.isVaultUnlocked)
    }

    @Test
    fun `an unrelated password is still just wrong`() = runTest {
        interruptChange("new-password", rowsMigrated = true)

        assertEquals(UnlockVaultUseCase.Result.WrongPassword, useCase("unrelated".toCharArray()))
        assertFalse(manager.isVaultUnlocked)
    }

    // -- Degenerate cases ---------------------------------------------------------------------

    @Test
    fun `an empty vault completes the change on the new password`() = runTest {
        val (_, pending) = manager.prepareChange("new-password".toCharArray())
        manager.beginChange(pending)
        manager.lockVault()

        assertEquals(UnlockVaultUseCase.Result.Success, useCase("new-password".toCharArray()))
        assertNull(manager.pendingChange)
        manager.lockVault()
        assertTrue(manager.unlock("new-password".toCharArray()))
    }

    @Test
    fun `an empty vault undoes the change on the old password`() = runTest {
        val (_, pending) = manager.prepareChange("new-password".toCharArray())
        manager.beginChange(pending)
        manager.lockVault()

        assertEquals(UnlockVaultUseCase.Result.Success, useCase("old-password".toCharArray()))
        assertNull(manager.pendingChange)
        manager.lockVault()
        assertTrue(manager.unlock("old-password".toCharArray()))
    }

    @Test
    fun `tombstones alone do not count as a probe row`() = runTest {
        val newKey = interruptChange("new-password", rowsMigrated = true)
        stored.replaceAll { it.copy(isDeleted = true) }

        // With no readable row to test, the typed password decides — and it must still
        // resolve rather than leaving the marker in place forever.
        assertEquals(UnlockVaultUseCase.Result.Success, useCase("new-password".toCharArray()))
        assertNull(manager.pendingChange)
        assertTrue(manager.verifyKey(newKey))
    }

    @Test
    fun `resolution survives a second interruption`() = runTest {
        // Recovery must be idempotent: if the app dies again mid-recovery, the next
        // attempt should behave identically.
        interruptChange("new-password", rowsMigrated = true)

        assertEquals(UnlockVaultUseCase.Result.Success, useCase("new-password".toCharArray()))
        manager.lockVault()
        assertEquals(UnlockVaultUseCase.Result.Success, useCase("new-password".toCharArray()))
    }
}
