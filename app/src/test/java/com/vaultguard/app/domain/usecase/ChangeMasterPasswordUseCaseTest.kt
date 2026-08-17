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
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Tests for changing the master password, centred on finding #6 — the biometric wrapper
 * kept holding a key derived from the *old* password, so fingerprint unlock afterwards
 * succeeded and then decrypted nothing.
 */
class ChangeMasterPasswordUseCaseTest {

    private val crypto = CryptoManager()
    private val keyDerivation = KeyDerivation()
    private val dao = mockk<CredentialDao>()
    private val syncService = mockk<FirebaseSyncService>(relaxed = true)
    private val dispatcher = UnconfinedTestDispatcher()

    private lateinit var masterPasswordManager: MasterPasswordManager
    private lateinit var biometricKeystore: FakeBiometricKeystore
    private lateinit var biometricAuthManager: BiometricAuthManager
    private lateinit var useCase: ChangeMasterPasswordUseCase
    private lateinit var stored: MutableList<CredentialEntity>

    @Before
    fun setUp() = runBlocking {
        masterPasswordManager = MasterPasswordManager(FakeSecurePrefs(), crypto, keyDerivation, dispatcher)
        masterPasswordManager.setup("old-password".toCharArray())

        biometricKeystore = FakeBiometricKeystore()
        biometricAuthManager = BiometricAuthManager(
            mockk<Context>(relaxed = true), biometricKeystore, FakeSecurePrefs()
        )

        stored = mutableListOf()
        coEvery { dao.getAll() } answers { stored.toList() }
        coEvery { dao.upsert(any()) } answers {
            val entity = firstArg<CredentialEntity>()
            stored.removeAll { it.id == entity.id }
            stored += entity
        }
        // Room runs collection-valued DAO methods in one transaction; the fake mirrors
        // that by applying the whole list or none of it.
        coEvery { dao.upsertAll(any()) } answers {
            val entities = firstArg<List<CredentialEntity>>()
            entities.forEach { entity ->
                stored.removeAll { it.id == entity.id }
                stored += entity
            }
        }

        useCase = ChangeMasterPasswordUseCase(
            dao, crypto, masterPasswordManager, biometricAuthManager, syncService, dispatcher
        )
        Unit
    }

    private fun givenCredentials(count: Int) {
        val key = masterPasswordManager.getSessionKey()
        repeat(count) { index ->
            val payload = CredentialPayloadCodec
                .encode(Credential(id = "id-$index", siteName = "Site $index", password = "pw-$index"))
                .toByteArray()
            val encrypted = crypto.encrypt(payload, key)
            stored += CredentialEntity("id-$index", encrypted.ciphertext, encrypted.iv, 1, 2)
        }
    }

    private fun enrolBiometric() {
        biometricAuthManager.completeEnrolment(
            biometricAuthManager.prepareEnrolment(),
            masterPasswordManager.getSessionKey()
        )
    }

    private fun decryptAllWithSessionKey(): List<Credential> {
        val key = masterPasswordManager.getSessionKey()
        return stored.map { entity ->
            val plaintext = crypto.decrypt(EncryptedData(entity.encryptedPayload, entity.iv), key)
            CredentialPayloadCodec.decode(String(plaintext), entity.id, entity.createdAt, entity.updatedAt)
        }
    }

    // -- Finding #6 ---------------------------------------------------------------------

    @Test
    fun `changing the password disables biometric unlock`() = runTest {
        givenCredentials(3)
        enrolBiometric()
        assertTrue(biometricAuthManager.isBiometricEnabled)

        val result = useCase("old-password".toCharArray(), "new-password".toCharArray())

        assertTrue(result.succeeded)
        assertTrue(result.biometricWasDisabled)
        assertFalse(
            "a stale wrap would unlock into an apparently empty vault",
            biometricAuthManager.isBiometricEnabled
        )
    }

    @Test
    fun `biometric is reported as untouched when it was never enabled`() = runTest {
        givenCredentials(1)

        val result = useCase("old-password".toCharArray(), "new-password".toCharArray())

        assertTrue(result.succeeded)
        assertFalse(result.biometricWasDisabled)
    }

    @Test
    fun `a rejected current password leaves biometric alone`() = runTest {
        givenCredentials(1)
        enrolBiometric()

        val result = useCase("wrong-password".toCharArray(), "new-password".toCharArray())

        assertFalse(result.succeeded)
        assertTrue("nothing changed, so biometric must survive", biometricAuthManager.isBiometricEnabled)
    }

    // -- Re-encryption --------------------------------------------------------------------

    @Test
    fun `every credential is readable with the new password afterwards`() = runTest {
        givenCredentials(5)

        val result = useCase("old-password".toCharArray(), "new-password".toCharArray())

        assertEquals(5, result.reEncryptedCount)
        val credentials = decryptAllWithSessionKey()
        assertEquals(5, credentials.size)
        assertEquals(
            (0 until 5).map { "pw-$it" }.toSet(),
            credentials.map { it.password }.toSet()
        )
    }

    @Test
    fun `the vault reopens with the new password and not the old one`() = runTest {
        givenCredentials(2)

        useCase("old-password".toCharArray(), "new-password".toCharArray())
        masterPasswordManager.lockVault()

        assertFalse(masterPasswordManager.unlock("old-password".toCharArray()))
        assertTrue(masterPasswordManager.unlock("new-password".toCharArray()))
        assertEquals(2, decryptAllWithSessionKey().size)
    }

    @Test
    fun `soft-deleted rows are left alone`() = runTest {
        givenCredentials(2)
        stored += stored.first().copy(id = "tombstone", isDeleted = true)

        val result = useCase("old-password".toCharArray(), "new-password".toCharArray())

        assertEquals(2, result.reEncryptedCount)
    }

    @Test
    fun `an empty vault changes password without incident`() = runTest {
        val result = useCase("old-password".toCharArray(), "new-password".toCharArray())

        assertTrue(result.succeeded)
        assertEquals(0, result.reEncryptedCount)
    }

    @Test
    fun `a failure pushing the new config does not fail the change`() = runTest {
        givenCredentials(1)
        coEvery { syncService.pushVaultConfig(any(), any(), any()) } throws IllegalStateException("offline")

        val result = useCase("old-password".toCharArray(), "new-password".toCharArray())

        assertTrue("sync is best-effort and must not block a local change", result.succeeded)
        assertEquals(1, decryptAllWithSessionKey().size)
    }

    // -- Finding #5: atomicity ------------------------------------------------------------

    @Test
    fun `a failure writing rows leaves the vault fully readable with the old password`() = runTest {
        givenCredentials(4)
        val before = stored.map { it.id to it.encryptedPayload.toList() }.toMap()
        coEvery { dao.upsertAll(any()) } throws IllegalStateException("disk full")

        val result = useCase("old-password".toCharArray(), "new-password".toCharArray())

        assertFalse(result.succeeded)
        // Salt untouched, so the old password still derives the key the rows are under.
        masterPasswordManager.lockVault()
        assertTrue(masterPasswordManager.unlock("old-password".toCharArray()))
        assertEquals(4, decryptAllWithSessionKey().size)
        assertEquals(before, stored.map { it.id to it.encryptedPayload.toList() }.toMap())
    }

    @Test
    fun `a failure writing rows clears the pending marker`() = runTest {
        givenCredentials(2)
        coEvery { dao.upsertAll(any()) } throws IllegalStateException("disk full")

        useCase("old-password".toCharArray(), "new-password".toCharArray())

        assertNull("a resolved failure must not leave recovery state behind",
            masterPasswordManager.pendingChange)
    }

    @Test
    fun `an unreadable row aborts the change instead of stranding it`() = runTest {
        // Re-encrypting the readable rows would move them to the new key and leave this
        // one behind forever. Better to change nothing.
        givenCredentials(3)
        stored[1] = stored[1].copy(
            encryptedPayload = stored[1].encryptedPayload.copyOf().also { it[0]++ }
        )

        val result = useCase("old-password".toCharArray(), "new-password".toCharArray())

        assertFalse(result.succeeded)
        assertNotNull(result.failureReason)
        masterPasswordManager.lockVault()
        assertTrue(masterPasswordManager.unlock("old-password".toCharArray()))
        assertNull(masterPasswordManager.pendingChange)
    }

    @Test
    fun `rows are written in a single call, not one at a time`() = runTest {
        // Row-by-row writes are what allowed a partial sweep to split the vault across
        // two keys in the first place.
        givenCredentials(5)

        useCase("old-password".toCharArray(), "new-password".toCharArray())

        coVerify(exactly = 1) { dao.upsertAll(any()) }
        coVerify(exactly = 0) { dao.upsert(any()) }
    }

    @Test
    fun `a successful change leaves no pending marker`() = runTest {
        givenCredentials(3)

        useCase("old-password".toCharArray(), "new-password".toCharArray())

        assertNull(masterPasswordManager.pendingChange)
    }

    @Test
    fun `key derivation does not run on the calling thread`() = runTest {
        // Argon2id blocks for hundreds of milliseconds; on the main thread that is an ANR
        // (finding #17). The dispatcher is injected so this can be asserted.
        givenCredentials(1)
        var used = false
        val recording = object : kotlinx.coroutines.CoroutineDispatcher() {
            override fun dispatch(context: kotlin.coroutines.CoroutineContext, block: Runnable) {
                used = true
                block.run()
            }
        }
        val manager = MasterPasswordManager(FakeSecurePrefs(), crypto, keyDerivation, recording)
        manager.setup("old-password".toCharArray())

        assertTrue("derivation must be dispatched, not run inline", used)
    }
}
