package com.vaultguard.desktop

import com.vaultguard.app.domain.model.Credential
import com.vaultguard.app.domain.usecase.backup.VaultBackupFormat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * These are slow by construction: every case pays for a real Argon2id derivation at
 * 64 MiB. Weakening the parameters to speed the suite up would mean testing something
 * other than the format the phone writes.
 */
class BackupFileTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private val password get() = "a backup password".toCharArray()

    private fun credential(name: String, password: String) = Credential(
        id = "id-$name",
        siteName = name,
        url = "https://$name.example",
        username = "okan",
        password = password,
        createdAt = 1_750_000_000_000L,
        updatedAt = 1_755_000_000_000L,
        passwordChangedAt = 1_750_000_000_000L,
        contentChangedAt = 1_755_000_000_000L
    )

    @Test
    fun `round-trips a vault through a file`() {
        val file = temporaryFolder.newFile("vault.json")
        val original = BackupVault(
            listOf(credential("github", "hunter2"), credential("bank", "s3cr3t"))
        )

        BackupFile.write(file, original, password)
        val reopened = BackupFile.read(file, password)

        assertEquals(2, reopened.size)
        assertEquals(original.credentials.map { it.id }, reopened.credentials.map { it.id })
        assertEquals(original.credentials.map { it.password }, reopened.credentials.map { it.password })
    }

    @Test
    fun `every field survives the trip`() {
        val file = temporaryFolder.newFile("vault.json")
        val source = Credential(
            id = "full",
            siteName = "GitHub",
            appName = "GitHub Mobile",
            url = "https://github.com",
            username = "okan",
            password = "p@ssw0rd with spaces",
            notes = "line one\nline two\ttabbed",
            category = "Development",
            tags = listOf("work", "2fa"),
            isPinned = true,
            linkedPackages = listOf("com.github.android"),
            linkedDomains = listOf("github.com"),
            createdAt = 1_700_000_000_000L,
            updatedAt = 1_710_000_000_000L,
            passwordChangedAt = 1_705_000_000_000L,
            contentChangedAt = 1_708_000_000_000L
        )

        BackupFile.write(file, BackupVault(listOf(source)), password)
        val restored = BackupFile.read(file, password).credentials.single()

        assertEquals(source, restored)
    }

    @Test
    fun `unicode in a password survives`() {
        // The derivation encodes the *master* password as UTF-16BE, which is the frozen
        // oddity; a credential's own password is ordinary payload JSON. Both directions
        // are worth pinning, because a desktop client is where an encoding assumption
        // would first diverge.
        val file = temporaryFolder.newFile("vault.json")
        val awkward = "gizli-şifre-密码-🔐"

        BackupFile.write(file, BackupVault(listOf(credential("unicode", awkward))), password)

        assertEquals(awkward, BackupFile.read(file, password).credentials.single().password)
    }

    @Test
    fun `a non-ascii backup password opens its own file`() {
        val file = temporaryFolder.newFile("vault.json")
        val awkward = { "parola-şifre-密码".toCharArray() }

        BackupFile.write(file, BackupVault(listOf(credential("x", "y"))), awkward())

        assertEquals(1, BackupFile.read(file, awkward()).size)
    }

    @Test
    fun `the wrong password is reported, not swallowed`() {
        val file = temporaryFolder.newFile("vault.json")
        BackupFile.write(file, BackupVault(listOf(credential("x", "y"))), password)

        assertThrows(WrongBackupPasswordException::class.java) {
            BackupFile.read(file, "not the password".toCharArray())
        }
    }

    @Test
    fun `a file that is not a backup is reported as such`() {
        val file = temporaryFolder.newFile("notes.txt")
        file.writeText("just some text")

        assertThrows(VaultBackupFormat.UnsupportedBackupException::class.java) {
            BackupFile.read(file, password)
        }
    }

    @Test
    fun `writing an empty vault is refused`() {
        val file = temporaryFolder.newFile("vault.json")

        // Same refusal as ExportVaultUseCase: someone may delete their other copies on the
        // strength of a backup that turns out to hold nothing.
        assertThrows(IllegalArgumentException::class.java) {
            BackupFile.write(file, BackupVault(), password)
        }
    }

    @Test
    fun `a rewrite leaves no partial file behind`() {
        val file = temporaryFolder.newFile("vault.json")
        BackupFile.write(file, BackupVault(listOf(credential("first", "a"))), password)
        BackupFile.write(file, BackupVault(listOf(credential("second", "b"))), password)

        val strays = file.parentFile.listFiles { candidate: File -> candidate.name.contains(".part") }
        assertEquals(0, strays?.size)
        assertEquals("id-second", BackupFile.read(file, password).credentials.single().id)
    }

    @Test
    fun `each write draws a fresh salt`() {
        val first = temporaryFolder.newFile("one.json")
        val second = temporaryFolder.newFile("two.json")
        val vault = BackupVault(listOf(credential("x", "y")))

        BackupFile.write(first, vault, password)
        BackupFile.write(second, vault, password)

        val saltOf = { file: File ->
            VaultBackupFormat.readEnvelope(file.readText()).kdf.salt.toList()
        }
        assertTrue("two exports reused a salt", saltOf(first) != saltOf(second))
    }

    /**
     * The committed fixture was written by this CLI and is the end-to-end guard: it pins
     * the Argon2id parameters, the UTF-16BE password encoding, the AES-GCM layer, the
     * envelope shape and the payload JSON in one artefact.
     *
     * If this stops opening, a real backup on disk has stopped opening too. Do not
     * regenerate it to make it pass — that is the same mistake as updating the golden
     * vector, and it is documented in CLAUDE.md for the same reason.
     */
    @Test
    fun `the committed fixture still opens`() {
        val fixture = File(javaClass.getResource("/sample-vault.vgbackup")!!.toURI())

        val vault = BackupFile.read(fixture, "correct horse battery staple".toCharArray())

        assertEquals(2, vault.size)
        val github = vault.credentials.single { it.siteName == "GitHub" }
        assertEquals("okan", github.username)
        assertEquals("https://github.com", github.url)
        assertEquals("fixture-password-one", github.password)

        val bank = vault.credentials.single { it.siteName == "Bank" }
        assertEquals("fixture-password-two", bank.password)
    }
}
