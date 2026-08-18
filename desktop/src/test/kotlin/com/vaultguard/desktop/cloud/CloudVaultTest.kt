package com.vaultguard.desktop.cloud

import com.vaultguard.app.data.repository.CredentialPayloadCodec
import com.vaultguard.app.domain.model.Credential
import com.vaultguard.app.security.CryptoManager
import com.vaultguard.app.security.FakeSecurePrefs
import com.vaultguard.app.security.KeyDerivation
import com.vaultguard.app.security.MasterPasswordManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import javax.crypto.SecretKey

/**
 * The whole read chain, offline.
 *
 * The vault under test is published the way the phone publishes one: a real
 * [MasterPasswordManager] is set up, and the values that go into Firestore are read back
 * through the same accessors `FirebaseSyncService.pushVaultConfig` uses — `getSalt`,
 * `getVerificationData`, `getWrappedVaultKey`. Nothing about the key hierarchy is restated
 * here, so a change to it fails this test rather than being quietly mirrored.
 *
 * What this cannot check is the field *names* Firestore stores them under. Those are pinned
 * against docs/DATA-FORMATS.md in [RemoteVaultCodec] and are shared with a private set of
 * constants in `:app`, so a rename on one side would still need catching by hand.
 */
class CloudVaultTest {

    private val password = "correct horse battery staple"

    /** Everything the phone would have put in `vaults/{uid}`, plus the key to seal rows. */
    private class PublishedVault(val document: String, val vaultKey: SecretKey)

    private fun publish(withWrappedKey: Boolean = true): PublishedVault {
        val manager = MasterPasswordManager(
            prefs = FakeSecurePrefs(),
            cryptoManager = CryptoManager(),
            keyDerivation = KeyDerivation(),
            cryptoDispatcher = Dispatchers.Default
        )
        runBlocking { manager.setup(password.toCharArray()) }

        val salt = manager.getSalt().encode()
        val (verificationCiphertext, verificationIv) = manager.getVerificationData()
        val wrapped = manager.getWrappedVaultKey()!!

        val fields = buildString {
            append(""""salt":{"stringValue":"$salt"},""")
            append(""""verificationCiphertext":{"stringValue":"${verificationCiphertext.encode()}"},""")
            append(""""verificationIv":{"stringValue":"${verificationIv.encode()}"}""")
            if (withWrappedKey) {
                append(""","vaultKeyCiphertext":{"stringValue":"${wrapped.ciphertext.encode()}"},""")
                append(""""vaultKeyIv":{"stringValue":"${wrapped.iv.encode()}"}""")
            }
        }

        return PublishedVault(
            document = """{"name":"projects/p/databases/(default)/documents/vaults/uid","fields":{$fields}}""",
            vaultKey = manager.getSessionKey()
        )
    }

    private fun row(
        id: String,
        credential: Credential,
        vaultKey: SecretKey,
        isDeleted: Boolean = false
    ): RemoteCredentialRow {
        val sealed = CryptoManager().encrypt(
            CredentialPayloadCodec.encode(credential).toByteArray(Charsets.UTF_8), vaultKey
        )
        return RemoteCredentialRow(
            id = id,
            encryptedPayload = sealed.ciphertext,
            iv = sealed.iv,
            createdAt = 1_700_000_000_000L,
            updatedAt = 1_710_000_000_000L,
            passwordChangedAt = 1_705_000_000_000L,
            isDeleted = isDeleted
        )
    }

    private fun credential(name: String, secret: String) = Credential(
        id = "ignored - the row id wins",
        siteName = name,
        url = "https://$name.example",
        username = "okan",
        password = secret,
        notes = "note for $name"
    )

    private fun ByteArray.encode(): String = java.util.Base64.getEncoder().encodeToString(this)

    private fun configOf(published: PublishedVault) =
        RemoteVaultCodec.readVaultConfig(org.json.JSONObject(published.document))!!

    @Test
    fun `a published vault unlocks and its rows decrypt`() {
        val published = publish()
        val config = configOf(published)

        val vaultKey = CloudVault().unlock(config, password.toCharArray())
        val snapshot = CloudVault().decrypt(
            listOf(
                row("row-a", credential("github", "hunter2"), published.vaultKey),
                row("row-b", credential("bank", "s3cr3t"), published.vaultKey)
            ),
            vaultKey
        )

        assertEquals(2, snapshot.items.size)
        assertFalse(snapshot.hasUndecryptable)
        assertEquals(listOf("bank", "github"), snapshot.items.map { it.siteName })
        assertEquals("hunter2", snapshot.items.single { it.siteName == "github" }.password)
    }

    @Test
    fun `the row id wins over whatever the payload carried`() {
        val published = publish()
        val vaultKey = CloudVault().unlock(configOf(published), password.toCharArray())

        val snapshot = CloudVault().decrypt(
            listOf(row("the-real-id", credential("github", "x"), published.vaultKey)), vaultKey
        )

        assertEquals("the-real-id", snapshot.items.single().id)
    }

    @Test
    fun `metadata comes off the row, not the payload`() {
        val published = publish()
        val vaultKey = CloudVault().unlock(configOf(published), password.toCharArray())

        val credential = CloudVault().decrypt(
            listOf(row("r", credential("github", "x"), published.vaultKey)), vaultKey
        ).items.single()

        assertEquals(1_700_000_000_000L, credential.createdAt)
        assertEquals(1_710_000_000_000L, credential.updatedAt)
        assertEquals(1_705_000_000_000L, credential.passwordChangedAt)
    }

    @Test
    fun `the wrong master password is reported as such`() {
        val config = configOf(publish())

        assertThrows(WrongMasterPasswordException::class.java) {
            CloudVault().unlock(config, "not the password".toCharArray())
        }
    }

    @Test
    fun `a config with no wrapped vault key is refused, not half-opened`() {
        // Finding #4: adopting a salt without the wrapped key leaves a vault whose master
        // password verifies and whose contents are unreachable. Refusing early is the fix.
        val config = configOf(publish(withWrappedKey = false))

        val thrown = assertThrows(UnreachableVaultKeyException::class.java) {
            CloudVault().unlock(config, password.toCharArray())
        }
        assertTrue(thrown.message!!.contains("wrapped vault key"))
    }

    @Test
    fun `a row that will not decrypt is reported, never dropped`() {
        val published = publish()
        val vaultKey = CloudVault().unlock(configOf(published), password.toCharArray())

        val good = row("good", credential("github", "x"), published.vaultKey)
        val damaged = good.copy(id = "damaged", encryptedPayload = good.encryptedPayload.clone().also { it[0] = (it[0] + 1).toByte() })

        val snapshot = CloudVault().decrypt(listOf(good, damaged), vaultKey)

        // The failure that finding #40 is about: silently dropping this row would present
        // a vault that is missing an entry as a vault that simply has fewer.
        assertEquals(1, snapshot.items.size)
        assertEquals(listOf("damaged"), snapshot.undecryptableIds)
        assertTrue(snapshot.hasUndecryptable)
        assertFalse(snapshot.isGenuinelyEmpty)
    }

    @Test
    fun `an entirely unreadable vault is not an empty one`() {
        val published = publish()
        val vaultKey = CloudVault().unlock(configOf(published), password.toCharArray())

        val good = row("only", credential("github", "x"), published.vaultKey)
        val damaged = good.copy(encryptedPayload = ByteArray(good.encryptedPayload.size))

        val snapshot = CloudVault().decrypt(listOf(damaged), vaultKey)

        assertTrue(snapshot.items.isEmpty())
        assertFalse("an unreadable vault must not report as empty", snapshot.isGenuinelyEmpty)
    }

    @Test
    fun `tombstoned rows are not listed and are not failures`() {
        val published = publish()
        val vaultKey = CloudVault().unlock(configOf(published), password.toCharArray())

        val snapshot = CloudVault().decrypt(
            listOf(
                row("live", credential("github", "x"), published.vaultKey),
                row("gone", credential("deleted", "y"), published.vaultKey, isDeleted = true)
            ),
            vaultKey
        )

        assertEquals(listOf("github"), snapshot.items.map { it.siteName })
        assertFalse(snapshot.hasUndecryptable)
    }

    @Test
    fun `an empty vault opens and says so honestly`() {
        val published = publish()
        val vaultKey = CloudVault().unlock(configOf(published), password.toCharArray())

        val snapshot = CloudVault().decrypt(emptyList(), vaultKey)

        assertTrue(snapshot.isGenuinelyEmpty)
    }

    // -- Writing ------------------------------------------------------------------------

    @Test
    fun `an encrypted credential decrypts back to itself`() {
        val published = publish()
        val vaultKey = CloudVault().unlock(configOf(published), password.toCharArray())

        val source = Credential(
            id = "new-entry",
            siteName = "Fastmail",
            appName = "Fastmail",
            url = "https://fastmail.com",
            username = "okan",
            password = "generated-p@ssw0rd",
            notes = "line one\nline two\ttabbed",
            category = "Email",
            tags = listOf("personal"),
            isPinned = false,
            createdAt = 1_760_000_000_000L,
            updatedAt = 1_760_000_000_000L,
            passwordChangedAt = 1_760_000_000_000L,
            contentChangedAt = 1_760_000_000_000L
        )

        val row = CloudVault().encrypt(source, vaultKey)
        val restored = CloudVault().decrypt(listOf(row), vaultKey).items.single()

        assertEquals(source, restored)
    }

    @Test
    fun `a new row is never a tombstone`() {
        val published = publish()
        val vaultKey = CloudVault().unlock(configOf(published), password.toCharArray())

        val row = CloudVault().encrypt(credential("x", "y").copy(id = "n"), vaultKey)

        assertFalse(row.isDeleted)
    }

    @Test
    fun `each encryption draws a fresh iv`() {
        val published = publish()
        val vaultKey = CloudVault().unlock(configOf(published), password.toCharArray())
        val source = credential("github", "hunter2").copy(id = "same-id")

        val first = CloudVault().encrypt(source, vaultKey)
        val second = CloudVault().encrypt(source, vaultKey)

        // Reusing an IV under one key is the failure that breaks GCM outright. Identical
        // input must not produce identical output.
        assertFalse("an IV was reused", first.iv.contentEquals(second.iv))
        assertFalse(first.encryptedPayload.contentEquals(second.encryptedPayload))
    }

    @Test
    fun `the row carries the credential's own clocks`() {
        val published = publish()
        val vaultKey = CloudVault().unlock(configOf(published), password.toCharArray())
        val source = credential("x", "y").copy(
            id = "n",
            createdAt = 111L,
            updatedAt = 222L,
            passwordChangedAt = 333L
        )

        val row = CloudVault().encrypt(source, vaultKey)

        assertEquals(111L, row.createdAt)
        assertEquals(222L, row.updatedAt)
        assertEquals(333L, row.passwordChangedAt)
    }

    @Test
    fun `a written row survives the full wire round-trip`() {
        // encrypt -> Firestore fields -> document -> decode -> decrypt. Every layer this
        // client owns, in one pass.
        val published = publish()
        val vaultKey = CloudVault().unlock(configOf(published), password.toCharArray())
        val source = credential("github", "hunter2").copy(id = "wire-1")

        val row = CloudVault().encrypt(source, vaultKey)
        val document = org.json.JSONObject()
            .put("name", "projects/p/databases/(default)/documents/vaults/uid/credentials/wire-1")
            .put("fields", FirestoreWrites.credentialFields(row))

        val decoded = RemoteVaultCodec.readCredentialRow(document)!!
        val restored = CloudVault().decrypt(listOf(decoded), vaultKey).items.single()

        assertEquals("wire-1", restored.id)
        assertEquals("hunter2", restored.password)
        assertEquals("github", restored.siteName)
    }
}
