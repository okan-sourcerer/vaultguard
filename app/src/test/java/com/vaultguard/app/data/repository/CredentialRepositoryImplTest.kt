package com.vaultguard.app.data.repository

import com.vaultguard.app.data.local.db.dao.CredentialDao
import com.vaultguard.app.data.local.db.entity.CredentialEntity
import com.vaultguard.app.domain.model.Credential
import com.vaultguard.app.domain.repository.CredentialLookup
import com.vaultguard.app.security.CryptoManager
import com.vaultguard.app.security.FakeSecurePrefs
import com.vaultguard.app.security.KeyDerivation
import com.vaultguard.app.security.MasterPasswordManager
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Tests for finding #40 — the repository used to drop undecryptable rows with
 * `mapNotNull { ... catch { null } }`, so a vault nothing could read was indistinguishable
 * from an empty one.
 *
 * The load-bearing assertions here are the ones checking that a failure is *reported*, not
 * merely that the good rows still come back.
 */
class CredentialRepositoryImplTest {

    private val crypto = CryptoManager()
    private val keyDerivation = KeyDerivation()
    private val dao = mockk<CredentialDao>()
    private val dispatcher = UnconfinedTestDispatcher()

    private lateinit var masterPasswordManager: MasterPasswordManager
    private lateinit var repository: CredentialRepositoryImpl

    @Before
    fun setUp() = runBlocking {
        masterPasswordManager = MasterPasswordManager(FakeSecurePrefs(), crypto, keyDerivation, dispatcher)
        masterPasswordManager.setup("master-password".toCharArray())
        repository = CredentialRepositoryImpl(dao, crypto, masterPasswordManager)
    }

    private fun entity(id: String, credential: Credential = credential(id)): CredentialEntity {
        val payload = CredentialPayloadCodec.encode(credential).toByteArray()
        val encrypted = crypto.encrypt(payload, masterPasswordManager.getSessionKey())
        return CredentialEntity(
            id = id,
            encryptedPayload = encrypted.ciphertext,
            iv = encrypted.iv,
            createdAt = 1_000,
            updatedAt = 2_000
        )
    }

    /** A row whose ciphertext cannot be opened with the current key. */
    private fun corruptEntity(id: String) = entity(id).let {
        it.copy(encryptedPayload = it.encryptedPayload.copyOf().also { bytes -> bytes[0]++ })
    }

    private fun credential(id: String, siteName: String = "Site $id") =
        Credential(id = id, siteName = siteName, username = "user-$id", password = "pw-$id")

    /** contentChangedAt lives in the payload, so reading it back means decrypting (#58). */
    private fun payloadOf(entity: CredentialEntity): Credential {
        val plaintext = crypto.decrypt(
            com.vaultguard.app.security.EncryptedData(entity.encryptedPayload, entity.iv),
            masterPasswordManager.getSessionKey()
        )
        return CredentialPayloadCodec.decode(
            String(plaintext), entity.id, entity.createdAt, entity.updatedAt, entity.passwordChangedAt
        )
    }

    // -- The regression ---------------------------------------------------------------

    @Test
    fun `a vault where nothing decrypts is not reported as empty`() = runTest {
        every { dao.getAllCredentials() } returns
            flowOf(listOf(corruptEntity("a"), corruptEntity("b"), corruptEntity("c")))

        val snapshot = repository.getAllCredentials().first()

        assertTrue(snapshot.items.isEmpty())
        assertEquals(3, snapshot.undecryptableCount)
        assertTrue(snapshot.hasUndecryptable)
        assertFalse("must not look like an empty vault", snapshot.isGenuinelyEmpty)
    }

    @Test
    fun `an actually empty vault is reported as genuinely empty`() = runTest {
        every { dao.getAllCredentials() } returns flowOf(emptyList())

        val snapshot = repository.getAllCredentials().first()

        assertTrue(snapshot.isGenuinelyEmpty)
        assertFalse(snapshot.hasUndecryptable)
    }

    @Test
    fun `readable rows survive alongside unreadable ones`() = runTest {
        every { dao.getAllCredentials() } returns
            flowOf(listOf(entity("good-1"), corruptEntity("bad"), entity("good-2")))

        val snapshot = repository.getAllCredentials().first()

        assertEquals(listOf("good-1", "good-2"), snapshot.items.map { it.id })
        assertEquals(listOf("bad"), snapshot.undecryptableIds)
    }

    @Test
    fun `summaries report failures too`() = runTest {
        every { dao.getAllCredentials() } returns flowOf(listOf(entity("good"), corruptEntity("bad")))

        val snapshot = repository.getAllSummaries().first()

        assertEquals(listOf("good"), snapshot.items.map { it.id })
        assertEquals(1, snapshot.undecryptableCount)
    }

    // -- Locked is not the same as damaged ---------------------------------------------

    @Test
    fun `a locked vault reports locked rather than a pile of failures`() = runTest {
        every { dao.getAllCredentials() } returns flowOf(listOf(entity("a"), entity("b")))
        masterPasswordManager.lockVault()

        val snapshot = repository.getAllCredentials().first()

        assertTrue(snapshot.isLocked)
        assertTrue(snapshot.items.isEmpty())
        assertFalse("locking is expected, not damage", snapshot.hasUndecryptable)
        assertFalse(snapshot.isGenuinelyEmpty)
    }

    @Test
    fun `a locked vault reports locked from getById`() = runTest {
        masterPasswordManager.lockVault()

        assertEquals(CredentialLookup.Locked, repository.getById("anything"))
    }

    // -- Single lookups -----------------------------------------------------------------

    @Test
    fun `getById returns the credential when it decrypts`() = runTest {
        coEvery { dao.getById("a") } returns entity("a", credential("a", siteName = "GitHub"))

        val lookup = repository.getById("a")

        assertTrue(lookup is CredentialLookup.Found)
        assertEquals("GitHub", (lookup as CredentialLookup.Found).credential.siteName)
    }

    @Test
    fun `getById distinguishes missing from unreadable`() = runTest {
        coEvery { dao.getById("missing") } returns null
        coEvery { dao.getById("corrupt") } returns corruptEntity("corrupt")

        assertEquals(CredentialLookup.NotFound, repository.getById("missing"))

        val corrupt = repository.getById("corrupt")
        assertTrue(corrupt is CredentialLookup.Undecryptable)
        assertEquals("corrupt", (corrupt as CredentialLookup.Undecryptable).id)
    }

    @Test
    fun `getById treats a soft-deleted row as missing`() = runTest {
        coEvery { dao.getById("gone") } returns entity("gone").copy(isDeleted = true)

        assertEquals(CredentialLookup.NotFound, repository.getById("gone"))
    }

    // -- Round-trip through save --------------------------------------------------------

    @Test
    fun `a saved credential decrypts back to the same values`() = runTest {
        val stored = mutableListOf<CredentialEntity>()
        coEvery { dao.getById(any()) } returns null
        coEvery { dao.upsert(any()) } answers { stored += firstArg<CredentialEntity>() }

        val source = Credential(
            id = "round-trip",
            siteName = "GitHub",
            username = "okan",
            password = """tricky",\password""",
            notes = "line one\nline two",
            tags = listOf("work", "2fa")
        )
        repository.save(source)

        every { dao.getAllCredentials() } returns flowOf(stored)
        val restored = repository.getAllCredentials().first().items.single()

        assertEquals(source.siteName, restored.siteName)
        assertEquals(source.password, restored.password)
        assertEquals(source.notes, restored.notes)
        assertEquals(source.tags, restored.tags)
    }

    // -- Snapshot semantics -----------------------------------------------------------------

    @Test
    fun `mapping a snapshot preserves the failure list`() = runTest {
        every { dao.getAllCredentials() } returns flowOf(listOf(entity("good"), corruptEntity("bad")))

        val mapped = repository.getAllCredentials().first().map { it.siteName }

        assertEquals(listOf("Site good"), mapped.items)
        assertEquals(listOf("bad"), mapped.undecryptableIds)
    }

    // -- Finding #58: content age vs row age -----------------------------------------------

    @Test
    fun `pinning does not count as changing the entry`() = runTest {
        // The bug: the detail screen read updatedAt, which every write moves, so pinning
        // reported the credential as updated today.
        val original = entity("a", credential("a").copy(contentChangedAt = 1_000))
        val stored = mutableListOf<CredentialEntity>()
        coEvery { dao.getById("a") } returns original
        coEvery { dao.upsert(any()) } answers { stored += firstArg<CredentialEntity>() }

        repository.save(credential("a").copy(isPinned = true))

        val saved = stored.single()
        assertEquals("content did not change", 1_000L, payloadOf(saved).contentChangedAt)
        assertTrue("but the row must still be pushed", saved.updatedAt > 1_000L)
    }

    @Test
    fun `editing the notes does count as changing the entry`() = runTest {
        val original = entity("a", credential("a").copy(contentChangedAt = 1_000))
        val stored = mutableListOf<CredentialEntity>()
        coEvery { dao.getById("a") } returns original
        coEvery { dao.upsert(any()) } answers { stored += firstArg<CredentialEntity>() }

        repository.save(credential("a").copy(notes = "edited"))

        assertTrue(payloadOf(stored.single()).contentChangedAt > 1_000L)
    }

    @Test
    fun `changing the autofill links counts as changing the entry`() = runTest {
        val original = entity("a", credential("a").copy(contentChangedAt = 1_000))
        val stored = mutableListOf<CredentialEntity>()
        coEvery { dao.getById("a") } returns original
        coEvery { dao.upsert(any()) } answers { stored += firstArg<CredentialEntity>() }

        repository.save(credential("a").copy(linkedDomains = listOf("example.com")))

        assertTrue(payloadOf(stored.single()).contentChangedAt > 1_000L)
    }

    @Test
    fun `a new credential stamps contentChangedAt`() = runTest {
        val stored = mutableListOf<CredentialEntity>()
        coEvery { dao.getById(any()) } returns null
        coEvery { dao.upsert(any()) } answers { stored += firstArg<CredentialEntity>() }

        repository.save(credential("new"))

        assertTrue(payloadOf(stored.single()).contentChangedAt > 0)
    }

    @Test
    fun `a payload written before the field existed falls back to the row clock`() = runTest {
        // Everything already in the live vault is in this state, and it must not read as
        // 1970 on the detail screen.
        val legacy = """{"siteName":"Old","password":"pw"}"""
        val decoded = CredentialPayloadCodec.decode(legacy, "a", 1_000, 7_000)

        assertEquals(7_000L, decoded.contentChangedAt)
    }

    // -- Finding #29: password age vs row age ---------------------------------------------

    @Test
    fun `a new credential stamps passwordChangedAt`() = runTest {
        val stored = mutableListOf<CredentialEntity>()
        coEvery { dao.getById(any()) } returns null
        coEvery { dao.upsert(any()) } answers { stored += firstArg<CredentialEntity>() }

        repository.save(credential("new"))

        val saved = stored.single()
        assertTrue(saved.passwordChangedAt > 0)
        assertEquals(saved.updatedAt, saved.passwordChangedAt)
    }

    @Test
    fun `editing a non-password field preserves passwordChangedAt`() = runTest {
        // The bug: pinning an entry or fixing a typo in its notes reset the reported
        // password age to "Today" and cleared it from the stale-password count.
        val original = entity("a", credential("a")).copy(
            updatedAt = 1_000, passwordChangedAt = 1_000
        )
        val stored = mutableListOf<CredentialEntity>()
        coEvery { dao.getById("a") } returns original
        coEvery { dao.upsert(any()) } answers { stored += firstArg<CredentialEntity>() }

        // Same password, different notes.
        repository.save(credential("a").copy(notes = "edited", isPinned = true))

        val saved = stored.single()
        assertEquals("password did not change", 1_000L, saved.passwordChangedAt)
        assertTrue("but the row was written", saved.updatedAt > 1_000L)
    }

    @Test
    fun `changing the password advances passwordChangedAt`() = runTest {
        val original = entity("a", credential("a")).copy(
            updatedAt = 1_000, passwordChangedAt = 1_000
        )
        val stored = mutableListOf<CredentialEntity>()
        coEvery { dao.getById("a") } returns original
        coEvery { dao.upsert(any()) } answers { stored += firstArg<CredentialEntity>() }

        repository.save(credential("a").copy(password = "a-brand-new-password"))

        val saved = stored.single()
        assertTrue("rotation must be recorded", saved.passwordChangedAt > 1_000L)
        assertEquals(saved.updatedAt, saved.passwordChangedAt)
    }

    @Test
    fun `an unreadable existing row is treated as a password change`() = runTest {
        // We cannot compare against ciphertext we cannot open, so carrying the old
        // timestamp forward would be asserting something unverified.
        val original = corruptEntity("a").copy(updatedAt = 1_000, passwordChangedAt = 1_000)
        val stored = mutableListOf<CredentialEntity>()
        coEvery { dao.getById("a") } returns original
        coEvery { dao.upsert(any()) } answers { stored += firstArg<CredentialEntity>() }

        repository.save(credential("a"))

        assertTrue(stored.single().passwordChangedAt > 1_000L)
    }

    @Test
    fun `passwordChangedAt reaches the domain model`() = runTest {
        every { dao.getAllCredentials() } returns flowOf(
            listOf(entity("a").copy(updatedAt = 9_000, passwordChangedAt = 4_000))
        )

        val credential = repository.getAllCredentials().first().items.single()

        assertEquals(4_000L, credential.passwordChangedAt)
        assertEquals(9_000L, credential.updatedAt)
    }
}
