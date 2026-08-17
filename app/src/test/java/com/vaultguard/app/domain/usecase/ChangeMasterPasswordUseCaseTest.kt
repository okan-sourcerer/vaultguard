package com.vaultguard.app.domain.usecase

import android.content.Context
import com.vaultguard.app.data.local.db.dao.CredentialDao
import com.vaultguard.app.data.local.db.entity.CredentialEntity
import com.vaultguard.app.data.remote.FirebaseSyncService
import com.vaultguard.app.data.repository.CredentialPayloadCodec
import com.vaultguard.app.domain.model.Credential
import com.vaultguard.app.security.BiometricAuthManager
import com.vaultguard.app.security.CryptoManager
import com.vaultguard.app.security.EncryptedData
import com.vaultguard.app.security.FakeBiometricKeystore
import com.vaultguard.app.security.FakeSecurePrefs
import com.vaultguard.app.security.KeyDerivation
import com.vaultguard.app.security.MasterPasswordManager
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Tests for changing the master password after the vault-key indirection.
 *
 * Most of the behaviour under test is what *doesn't* happen: no credential is read,
 * decrypted, or written, and biometric enrolment survives. Before the indirection this
 * rewrote every row across two stores that could not share a transaction (finding #5) and
 * invalidated the biometric wrapper (finding #6).
 */
class ChangeMasterPasswordUseCaseTest {

    private val crypto = CryptoManager()
    private val keyDerivation = KeyDerivation()
    private val dao = mockk<CredentialDao>(relaxed = true)
    private val syncService = mockk<FirebaseSyncService>(relaxed = true)
    private val dispatcher = UnconfinedTestDispatcher()

    private lateinit var manager: MasterPasswordManager
    private lateinit var biometricKeystore: FakeBiometricKeystore
    private lateinit var biometricAuthManager: BiometricAuthManager
    private lateinit var useCase: ChangeMasterPasswordUseCase
    private lateinit var stored: MutableList<CredentialEntity>

    @Before
    fun setUp() = runBlocking {
        manager = MasterPasswordManager(FakeSecurePrefs(), crypto, keyDerivation, dispatcher)
        manager.setup("old-password".toCharArray())

        biometricKeystore = FakeBiometricKeystore()
        biometricAuthManager = BiometricAuthManager(
            mockk<Context>(relaxed = true), biometricKeystore, FakeSecurePrefs()
        )

        stored = mutableListOf()
        coEvery { dao.getAll() } answers { stored.toList() }

        useCase = ChangeMasterPasswordUseCase(manager, syncService)
        Unit
    }

    private fun givenCredentials(count: Int) {
        val key = manager.getSessionKey()
        repeat(count) { index ->
            val payload = CredentialPayloadCodec
                .encode(Credential(id = "id-$index", siteName = "Site $index", password = "pw-$index"))
                .toByteArray()
            val sealed = crypto.encrypt(payload, key)
            stored += CredentialEntity("id-$index", sealed.ciphertext, sealed.iv, 1, 2)
        }
    }

    private fun enrolBiometric() {
        biometricAuthManager.completeEnrolment(
            biometricAuthManager.prepareEnrolment(), manager.getSessionKey()
        )
    }

    private suspend fun unlock(password: String): Boolean {
        val masterKey = manager.deriveMasterKey(password.toCharArray()) ?: return false
        if (!manager.verifyMasterKey(masterKey)) return false
        val vaultKey = manager.unwrapVaultKey(masterKey) ?: return false
        manager.adoptVaultKey(vaultKey)
        return true
    }

    private fun decryptAll(): List<Credential> {
        val key = manager.getSessionKey()
        return stored.map { entity ->
            val plaintext = crypto.decrypt(EncryptedData(entity.encryptedPayload, entity.iv), key)
            CredentialPayloadCodec.decode(String(plaintext), entity.id, entity.createdAt, entity.updatedAt)
        }
    }

    // -- The payoff -------------------------------------------------------------------------

    @Test
    fun `no credential row is touched`() = runTest {
        givenCredentials(5)
        val before = stored.map { it.id to it.encryptedPayload.toList() }

        assertTrue(useCase("old-password".toCharArray(), "new-password".toCharArray()).succeeded)

        assertEquals(before, stored.map { it.id to it.encryptedPayload.toList() })
        coVerify(exactly = 0) { dao.upsertAll(any()) }
        coVerify(exactly = 0) { dao.upsert(any()) }
    }

    @Test
    fun `biometric enrolment survives`() = runTest {
        givenCredentials(2)
        enrolBiometric()

        assertTrue(useCase("old-password".toCharArray(), "new-password".toCharArray()).succeeded)

        assertTrue(biometricAuthManager.isBiometricEnabled)
    }

    @Test
    fun `the biometric-wrapped key still unlocks after the change`() = runTest {
        givenCredentials(2)
        enrolBiometric()
        val wrapped = biometricAuthManager.loadWrappedKey()!!

        useCase("old-password".toCharArray(), "new-password".toCharArray())
        manager.lockVault()

        // Unwrap exactly as the biometric path does, then present it to the manager.
        val keyBytes = biometricKeystore.decryptWith(
            biometricKeystore.decryptCipher(wrapped.iv), wrapped
        )
        val unwrapped = javax.crypto.spec.SecretKeySpec(keyBytes, "AES")

        assertTrue("the wrapper must still hold the current vault key", manager.unlockWithKey(unwrapped))
        assertEquals(2, decryptAll().size)
    }

    @Test
    fun `credentials remain readable with the new password`() = runTest {
        givenCredentials(4)

        useCase("old-password".toCharArray(), "new-password".toCharArray())
        manager.lockVault()

        assertTrue(unlock("new-password"))
        assertEquals(
            (0 until 4).map { "pw-$it" }.toSet(),
            decryptAll().map { it.password }.toSet()
        )
    }

    @Test
    fun `the old password stops working`() = runTest {
        givenCredentials(1)

        useCase("old-password".toCharArray(), "new-password".toCharArray())
        manager.lockVault()

        assertFalse(unlock("old-password"))
    }

    @Test
    fun `the vault key is unchanged by the rotation`() = runTest {
        givenCredentials(1)
        val before = manager.getSessionKey().encoded.toList()

        useCase("old-password".toCharArray(), "new-password".toCharArray())
        manager.lockVault()
        unlock("new-password")

        assertEquals(before, manager.getSessionKey().encoded.toList())
    }

    // -- Rejections ---------------------------------------------------------------------------

    @Test
    fun `a wrong current password changes nothing`() = runTest {
        givenCredentials(2)
        val saltBefore = manager.getSalt()

        val result = useCase("not-the-password".toCharArray(), "new-password".toCharArray())

        assertFalse(result.succeeded)
        assertArrayEquals(saltBefore, manager.getSalt())
        manager.lockVault()
        assertTrue(unlock("old-password"))
    }

    @Test
    fun `a locked vault is refused`() = runTest {
        givenCredentials(1)
        manager.lockVault()

        val result = useCase("old-password".toCharArray(), "new-password".toCharArray())

        assertFalse(result.succeeded)
        assertTrue(result.failureReason!!.contains("unlocked"))
    }

    @Test
    fun `a failure pushing the new config does not fail the change`() = runTest {
        givenCredentials(1)
        coEvery { syncService.pushVaultConfig(any(), any(), any()) } throws IllegalStateException("offline")

        val result = useCase("old-password".toCharArray(), "new-password".toCharArray())

        assertTrue("sync is best-effort and must not block a local change", result.succeeded)
        manager.lockVault()
        assertTrue(unlock("new-password"))
    }

    @Test
    fun `an empty vault changes password without incident`() = runTest {
        assertTrue(useCase("old-password".toCharArray(), "new-password".toCharArray()).succeeded)

        manager.lockVault()
        assertTrue(unlock("new-password"))
    }
}
