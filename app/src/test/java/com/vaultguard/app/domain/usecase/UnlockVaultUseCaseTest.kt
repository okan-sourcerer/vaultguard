package com.vaultguard.app.domain.usecase

import com.vaultguard.app.data.local.db.dao.CredentialDao
import com.vaultguard.app.data.local.db.entity.CredentialEntity
import com.vaultguard.app.data.repository.CredentialPayloadCodec
import com.vaultguard.app.domain.model.Credential
import com.vaultguard.app.security.CryptoManager
import com.vaultguard.app.security.EncryptedData
import com.vaultguard.app.security.FakeSecurePrefs
import com.vaultguard.app.security.KeyDerivation
import com.vaultguard.app.security.MasterPasswordManager
import com.vaultguard.app.security.UnlockThrottle
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import javax.crypto.SecretKey

/**
 * Tests for unlocking, including conversion of a legacy vault to the vault-key layout.
 *
 * A "legacy" vault here is one whose payloads are encrypted directly under the
 * master-derived key — the layout every vault had before the indirection. Conversion runs
 * on the next successful unlock, and has to be safe against being interrupted, because it
 * rewrites every row.
 */
class UnlockVaultUseCaseTest {

    private val crypto = CryptoManager()
    private val keyDerivation = KeyDerivation()
    private val dao = mockk<CredentialDao>()
    private val dispatcher = UnconfinedTestDispatcher()

    private lateinit var prefs: FakeSecurePrefs
    private lateinit var manager: MasterPasswordManager
    private lateinit var throttle: UnlockThrottle
    private lateinit var useCase: UnlockVaultUseCase
    private lateinit var stored: MutableList<CredentialEntity>

    @Before
    fun setUp() = runBlocking {
        prefs = FakeSecurePrefs()
        manager = MasterPasswordManager(prefs, crypto, keyDerivation, dispatcher)
        stored = mutableListOf()
        coEvery { dao.getAll() } answers { stored.toList() }
        coEvery { dao.upsertAll(any()) } answers {
            firstArg<List<CredentialEntity>>().forEach { entity ->
                stored.removeAll { it.id == entity.id }
                stored += entity
            }
        }
        throttle = UnlockThrottle(prefs)
        useCase = UnlockVaultUseCase(manager, dao, crypto, throttle, dispatcher)
        Unit
    }

    private fun rowsUnder(key: SecretKey, count: Int = 3, startAt: Int = 0) {
        repeat(count) { i ->
            val index = startAt + i
            val payload = CredentialPayloadCodec
                .encode(Credential(id = "id-$index", siteName = "Site $index", password = "pw-$index"))
                .toByteArray()
            val sealed = crypto.encrypt(payload, key)
            stored += CredentialEntity(
                "id-$index", sealed.ciphertext, sealed.iv,
                createdAt = 100, updatedAt = 200, passwordChangedAt = 150
            )
        }
    }

    /** Builds a vault in the pre-indirection layout: rows sealed under the master key. */
    private suspend fun givenLegacyVault(password: String = "master-password", rows: Int = 3): SecretKey {
        manager.setup(password.toCharArray())
        val masterKey = manager.deriveMasterKey(password.toCharArray())!!
        // Strip the wrapped vault key so the vault looks like one created before it existed.
        prefs.remove(listOf("vault_key_ciphertext", "vault_key_iv", "vault_key_check_ciphertext", "vault_key_check_iv"))
        stored.clear()
        rowsUnder(masterKey, rows)
        manager.lockVault()
        return masterKey
    }

    private suspend fun givenModernVault(password: String = "master-password", rows: Int = 3) {
        manager.setup(password.toCharArray())
        rowsUnder(manager.getSessionKey(), rows)
        manager.lockVault()
    }

    private fun readAll(): List<Credential> {
        val key = manager.getSessionKey()
        return stored.map {
            val plaintext = crypto.decrypt(EncryptedData(it.encryptedPayload, it.iv), key)
            CredentialPayloadCodec.decode(String(plaintext), it.id, it.createdAt, it.updatedAt, it.passwordChangedAt)
        }
    }

    // -- Normal path --------------------------------------------------------------------------

    @Test
    fun `unlocks a modern vault with the right password`() = runTest {
        givenModernVault()

        assertEquals(UnlockVaultUseCase.Result.Success, useCase("master-password".toCharArray()))
        assertTrue(manager.isVaultUnlocked)
        assertEquals(3, readAll().size)
    }

    @Test
    fun `rejects the wrong password`() = runTest {
        givenModernVault()

        assertEquals(UnlockVaultUseCase.Result.WrongPassword, useCase("nope".toCharArray()))
        assertFalse(manager.isVaultUnlocked)
    }

    @Test
    fun `unlocking a modern vault rewrites nothing`() = runTest {
        givenModernVault()
        val before = stored.map { it.encryptedPayload.toList() }

        useCase("master-password".toCharArray())

        assertEquals(before, stored.map { it.encryptedPayload.toList() })
    }

    // -- Legacy conversion ----------------------------------------------------------------------

    @Test
    fun `converts a legacy vault on first unlock`() = runTest {
        givenLegacyVault()
        assertFalse(manager.hasWrappedVaultKey)

        assertEquals(UnlockVaultUseCase.Result.Success, useCase("master-password".toCharArray()))

        assertTrue("a wrapped vault key must now exist", manager.hasWrappedVaultKey)
        assertEquals(3, readAll().size)
        assertEquals(setOf("pw-0", "pw-1", "pw-2"), readAll().map { it.password }.toSet())
    }

    @Test
    fun `after conversion the rows no longer open with the master key`() = runTest {
        val masterKey = givenLegacyVault()

        useCase("master-password".toCharArray())

        val row = stored.first()
        assertFalse(opens(masterKey, row))
        assertTrue(opens(manager.getSessionKey(), row))
    }

    @Test
    fun `conversion preserves passwordChangedAt`() = runTest {
        // Re-encryption is not a password rotation (finding #29).
        givenLegacyVault()

        useCase("master-password".toCharArray())

        assertTrue(stored.all { it.passwordChangedAt == 150L })
    }

    @Test
    fun `conversion bumps updatedAt so sync sees the new ciphertext`() = runTest {
        givenLegacyVault()

        useCase("master-password".toCharArray())

        assertTrue(stored.all { it.updatedAt > 200L })
    }

    @Test
    fun `a converted vault unlocks normally next time`() = runTest {
        givenLegacyVault()
        useCase("master-password".toCharArray())
        manager.lockVault()
        val afterConversion = stored.map { it.encryptedPayload.toList() }

        assertEquals(UnlockVaultUseCase.Result.Success, useCase("master-password".toCharArray()))

        assertEquals("second unlock must not convert again", afterConversion, stored.map { it.encryptedPayload.toList() })
    }

    @Test
    fun `a legacy vault with no rows just gains a vault key`() = runTest {
        givenLegacyVault(rows = 0)

        assertEquals(UnlockVaultUseCase.Result.Success, useCase("master-password".toCharArray()))

        assertTrue(manager.hasWrappedVaultKey)
        assertTrue(manager.verifyVaultKey(manager.getSessionKey()))
    }

    @Test
    fun `conversion is abandoned if any row cannot be re-encrypted`() = runTest {
        val masterKey = givenLegacyVault()
        stored[1] = stored[1].copy(
            encryptedPayload = stored[1].encryptedPayload.copyOf().also { it[0]++ }
        )
        val before = stored.map { it.encryptedPayload.toList() }

        val result = useCase("master-password".toCharArray())

        assertTrue(result is UnlockVaultUseCase.Result.VaultUnreadable)
        assertEquals("nothing may be rewritten", before, stored.map { it.encryptedPayload.toList() })
        assertTrue("the readable rows must still open the old way", opens(masterKey, stored[0]))
    }

    // -- Interrupted conversion ------------------------------------------------------------------

    @Test
    fun `resumes a conversion interrupted after the key was stored`() = runTest {
        // The wrapped vault key is written before the rows, so a crash in between leaves a
        // stored key that does not yet open anything. The next unlock must resume with that
        // same key rather than mint a new one.
        val masterKey = givenLegacyVault()
        val vaultKey = manager.generateVaultKey()
        manager.storeVaultKey(vaultKey, masterKey)

        assertEquals(UnlockVaultUseCase.Result.Success, useCase("master-password".toCharArray()))

        assertEquals(
            "must reuse the already-stored key",
            vaultKey.encoded.toList(),
            manager.getSessionKey().encoded.toList()
        )
        assertEquals(3, readAll().size)
    }

    @Test
    fun `a half-written conversion completes on the next unlock`() = runTest {
        // Some rows converted, some not — the state a crash mid-write would leave if the
        // transaction were not atomic. Recovery must still be possible.
        val masterKey = givenLegacyVault(rows = 0)
        val vaultKey = manager.generateVaultKey()
        manager.storeVaultKey(vaultKey, masterKey)
        rowsUnder(masterKey, count = 2, startAt = 0)

        assertEquals(UnlockVaultUseCase.Result.Success, useCase("master-password".toCharArray()))

        assertEquals(2, readAll().size)
        assertTrue(stored.all { opens(vaultKey, it) })
    }

    @Test
    fun `reports unreadable when no key opens the rows`() = runTest {
        givenModernVault()
        // Simulate rows belonging to a different vault entirely.
        val stranger = manager.generateVaultKey()
        stored.clear()
        rowsUnder(stranger, 2)

        val result = useCase("master-password".toCharArray())

        assertTrue(result is UnlockVaultUseCase.Result.VaultUnreadable)
        assertFalse("must not unlock into an unreadable vault", manager.isVaultUnlocked)
    }

    private fun opens(key: SecretKey, entity: CredentialEntity): Boolean =
        try {
            crypto.decrypt(EncryptedData(entity.encryptedPayload, entity.iv), key)
            true
        } catch (_: Exception) {
            false
        }

    // -- Throttling — finding #12 ------------------------------------------------------------

    @Test
    fun `repeated wrong passwords eventually lock the attempt out`() = runTest {
        givenModernVault()

        assertEquals(UnlockVaultUseCase.Result.WrongPassword, useCase("nope".toCharArray()))
        assertEquals(UnlockVaultUseCase.Result.WrongPassword, useCase("nope".toCharArray()))

        val third = useCase("nope".toCharArray())
        assertTrue(third is UnlockVaultUseCase.Result.Throttled)
        assertEquals(3, (third as UnlockVaultUseCase.Result.Throttled).failedAttempts)
    }

    @Test
    fun `a throttled attempt is refused even with the correct password`() = runTest {
        givenModernVault()
        repeat(3) { useCase("nope".toCharArray()) }

        val result = useCase("master-password".toCharArray())

        assertTrue(result is UnlockVaultUseCase.Result.Throttled)
        assertFalse(manager.isVaultUnlocked)
    }

    @Test
    fun `a successful unlock clears the throttle`() = runTest {
        givenModernVault()
        repeat(2) { useCase("nope".toCharArray()) }

        assertEquals(UnlockVaultUseCase.Result.Success, useCase("master-password".toCharArray()))

        assertEquals(0, throttle.failedAttempts)
    }

    @Test
    fun `an unreadable vault is not counted as a wrong password`() = runTest {
        // The password was right; penalising it would lock the user out of their own
        // recovery attempts.
        givenModernVault()
        val stranger = manager.generateVaultKey()
        stored.clear()
        rowsUnder(stranger, 2)

        useCase("master-password".toCharArray())

        assertEquals(0, throttle.failedAttempts)
    }
}
