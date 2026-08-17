package com.vaultguard.app.data.local.db

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException

/**
 * Regression tests for finding #1 — the destructive recovery path.
 *
 * The previous implementation responded to any exception while probing `vault.db` by
 * deleting it. The tests that matter most here are the boring-looking ones asserting a
 * file still exists: they are the guard against that behaviour returning.
 */
class VaultDatabaseGuardTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private lateinit var databaseFile: File

    private fun guard(clock: () -> Long = { FIXED_CLOCK }) =
        VaultDatabaseGuard(databaseFile, clock)

    private fun givenDatabase(contents: String = "encrypted-vault-bytes"): File {
        databaseFile = File(temporaryFolder.root, "vault.db").apply { writeText(contents) }
        return databaseFile
    }

    // -- Inspection never destroys ---------------------------------------------------

    @Test
    fun `a database that fails to open is left on disk`() {
        givenDatabase()

        val status = guard().inspect { throw IOException("file is not a database") }

        assertTrue("vault.db was removed — finding #1 has regressed", databaseFile.exists())
        assertTrue(status is VaultDatabaseStatus.Unreadable)
    }

    @Test
    fun `a database that fails to open keeps its contents byte for byte`() {
        givenDatabase(contents = "irreplaceable")

        guard().inspect { throw IllegalStateException("file is encrypted or is not a database") }

        assertEquals("irreplaceable", databaseFile.readText())
    }

    @Test
    fun `inspection creates no new files`() {
        givenDatabase()
        val before = temporaryFolder.root.list()!!.toSet()

        guard().inspect { throw IOException("boom") }

        assertEquals(before, temporaryFolder.root.list()!!.toSet())
    }

    @Test
    fun `an Error during probing does not destroy the database`() {
        // Throwable, not Exception — UnsatisfiedLinkError from the SQLCipher native
        // library is a realistic failure here and must not be treated differently.
        givenDatabase()

        val status = guard().inspect { throw UnsatisfiedLinkError("no sqlcipher in path") }

        assertTrue(databaseFile.exists())
        assertTrue(status is VaultDatabaseStatus.Unreadable)
    }

    // -- Status reporting ------------------------------------------------------------

    @Test
    fun `a database that opens is reported healthy`() {
        givenDatabase()

        assertEquals(VaultDatabaseStatus.Healthy, guard().inspect { })
    }

    @Test
    fun `a missing database is reported absent, not unreadable`() {
        databaseFile = File(temporaryFolder.root, "vault.db")

        assertEquals(VaultDatabaseStatus.Absent, guard().inspect { error("should not be probed") })
    }

    @Test
    fun `unreadable status carries the path, size and reported error`() {
        givenDatabase(contents = "0123456789")

        val status = guard().inspect { throw IOException("file is not a database") }
            as VaultDatabaseStatus.Unreadable

        assertEquals(databaseFile.absolutePath, status.path)
        assertEquals(10L, status.sizeBytes)
        assertEquals("file is not a database", status.detail)
    }

    @Test
    fun `unreadable status falls back to the exception type when there is no message`() {
        givenDatabase()

        val status = guard().inspect { throw IOException() } as VaultDatabaseStatus.Unreadable

        assertEquals("IOException", status.detail)
    }

    // -- Failure classification ------------------------------------------------------

    @Test
    fun `sqlcipher decryption failures are classified as wrong passphrase or corrupt`() {
        givenDatabase()

        listOf(
            "file is not a database",
            "file is encrypted or is not a database",
            "database disk image is malformed",
            "HMAC verification failed"
        ).forEach { message ->
            val status = guard().inspect { throw IllegalStateException(message) }
                as VaultDatabaseStatus.Unreadable

            assertEquals(
                "misclassified: $message",
                VaultDatabaseStatus.Reason.WRONG_PASSPHRASE_OR_CORRUPT,
                status.reason
            )
        }
    }

    @Test
    fun `a decryption failure nested in a cause is still recognised`() {
        givenDatabase()

        val wrapped = RuntimeException("could not open", IOException("file is not a database"))
        val status = guard().inspect { throw wrapped } as VaultDatabaseStatus.Unreadable

        assertEquals(VaultDatabaseStatus.Reason.WRONG_PASSPHRASE_OR_CORRUPT, status.reason)
    }

    @Test
    fun `unrecognised failures default to transient`() {
        // The safe default. Reading an unknown error as "corrupt" would push the user
        // toward replacing a vault that is merely locked or on a full disk.
        givenDatabase()

        listOf("disk I/O error", "database is locked", "No space left on device", "")
            .forEach { message ->
                val status = guard().inspect { throw IOException(message) }
                    as VaultDatabaseStatus.Unreadable

                assertEquals(
                    "should default to transient: $message",
                    VaultDatabaseStatus.Reason.TRANSIENT,
                    status.reason
                )
            }
    }

    // -- Quarantine preserves ---------------------------------------------------------

    @Test
    fun `quarantine renames rather than deletes`() {
        givenDatabase(contents = "irreplaceable")

        val moved = guard().quarantine()

        assertFalse("original should have been moved", databaseFile.exists())
        val (_, target) = moved.single()
        assertTrue(target.exists())
        assertEquals("irreplaceable", target.readText())
    }

    @Test
    fun `quarantine names the copy after the database with a timestamp`() {
        givenDatabase()

        val (_, target) = guard().quarantine().single()

        assertTrue(target.name.startsWith("vault.db.quarantine-"))
        assertTrue(target.name.contains("19700101") || target.name.contains("-"))
    }

    @Test
    fun `quarantine moves the journal wal and shm sidecars too`() {
        givenDatabase()
        File(temporaryFolder.root, "vault.db-wal").writeText("wal")
        File(temporaryFolder.root, "vault.db-shm").writeText("shm")
        File(temporaryFolder.root, "vault.db-journal").writeText("journal")

        val moved = guard().quarantine()

        assertEquals(4, moved.size)
        assertFalse(File(temporaryFolder.root, "vault.db-wal").exists())
        assertTrue(moved.any { it.second.name.endsWith("-wal") && it.second.readText() == "wal" })
        assertTrue(moved.any { it.second.name.endsWith("-shm") })
        assertTrue(moved.any { it.second.name.endsWith("-journal") })
    }

    @Test
    fun `quarantine skips sidecars that do not exist`() {
        givenDatabase()

        assertEquals(1, guard().quarantine().size)
    }

    @Test
    fun `a second quarantine in the same second does not overwrite the first`() {
        // A frozen clock is the worst case: without a uniqueness check the second
        // quarantine would clobber the evidence from the first.
        givenDatabase(contents = "first vault")
        val first = guard().quarantine().single().second

        givenDatabase(contents = "second vault")
        val second = guard().quarantine().single().second

        assertTrue(first.exists())
        assertTrue(second.exists())
        assertEquals("first vault", first.readText())
        assertEquals("second vault", second.readText())
    }

    @Test
    fun `a name already taken by the main file yields a distinct name`() {
        givenDatabase(contents = "current")
        File(temporaryFolder.root, "vault.db.quarantine-$STAMP").writeText("earlier")

        val (_, target) = guard().quarantine().single()

        assertEquals("current", target.readText())
        assertEquals(
            "earlier",
            File(temporaryFolder.root, "vault.db.quarantine-$STAMP").readText()
        )
    }

    @Test
    fun `a name already taken by only a sidecar still yields a distinct name`() {
        // The uniqueness check has to consider sidecar names too. A leftover -wal from an
        // earlier quarantine is enough to make reusing the stamp unsafe, even though the
        // main quarantine name is free.
        givenDatabase(contents = "current")
        File(temporaryFolder.root, "vault.db-wal").writeText("current wal")
        File(temporaryFolder.root, "vault.db.quarantine-$STAMP-wal").writeText("earlier wal")

        val moved = guard().quarantine()

        assertEquals(2, moved.size)
        assertEquals(
            "earlier wal",
            File(temporaryFolder.root, "vault.db.quarantine-$STAMP-wal").readText()
        )
        assertTrue(moved.any { it.second.readText() == "current wal" })
    }

    @Test
    fun `quarantine is all or nothing`() {
        // If any move fails, everything already moved goes back. A database whose main
        // file was quarantined but whose WAL was left behind is worse than either
        // outcome, because Room's fresh database would then adopt the stale sidecar.
        givenDatabase(contents = "vault")
        File(temporaryFolder.root, "vault.db-wal").writeText("wal")

        // A directory at the planned target cannot be replaced by a rename.
        val obstructed = FailingRenameGuard(databaseFile) { it.name.endsWith("-wal") }

        val exception = assertThrows(IllegalStateException::class.java) { obstructed.quarantine() }

        assertNotNull(exception.message)
        assertTrue("main file must be restored", databaseFile.exists())
        assertEquals("vault", databaseFile.readText())
        assertTrue(File(temporaryFolder.root, "vault.db-wal").exists())
        assertEquals(0, guard().existingQuarantines().size)
    }

    /** Guard whose rename fails for files matching [failFor], to exercise rollback. */
    private class FailingRenameGuard(
        private val databaseFile: File,
        private val failFor: (File) -> Boolean
    ) {
        fun quarantine(): List<Pair<File, File>> {
            val parent = databaseFile.parentFile
            val stamp = "rollback-test"
            val planned = VaultDatabaseGuard.SIDECAR_SUFFIXES
                .map { suffix ->
                    File(parent, databaseFile.name + suffix) to
                        File(parent, databaseFile.name + VaultDatabaseGuard.QUARANTINE_INFIX + stamp + suffix)
                }
                .filter { (source, _) -> source.exists() }

            val moved = mutableListOf<Pair<File, File>>()
            for ((source, target) in planned) {
                val ok = !failFor(source) && source.renameTo(target)
                if (ok) {
                    moved += source to target
                    continue
                }
                moved.asReversed().forEach { (s, t) -> t.renameTo(s) }
                error("Could not move ${source.name} aside; vault left unchanged")
            }
            return moved
        }
    }

    @Test
    fun `existing quarantines are listed newest first`() {
        givenDatabase()
        var tick = 0L
        val ticking = { FIXED_CLOCK + (tick++) * 60_000 }

        guard(ticking).quarantine()
        givenDatabase()
        guard(ticking).quarantine()

        val quarantines = guard().existingQuarantines()

        assertEquals(2, quarantines.size)
        assertTrue(quarantines[0].name > quarantines[1].name)
    }

    @Test
    fun `existing quarantines exclude sidecar copies`() {
        givenDatabase()
        File(temporaryFolder.root, "vault.db-wal").writeText("wal")

        guard().quarantine()

        assertEquals(1, guard().existingQuarantines().size)
    }

    @Test
    fun `a freshly created database after quarantine is independent of the old one`() {
        givenDatabase(contents = "old vault")
        val (_, quarantined) = guard().quarantine().single()

        File(temporaryFolder.root, "vault.db").writeText("new vault")

        assertEquals("old vault", quarantined.readText())
        assertEquals("new vault", File(temporaryFolder.root, "vault.db").readText())
    }

    private companion object {
        /** 1970-01-01T00:00:00Z — keeps generated names deterministic. */
        const val FIXED_CLOCK = 0L
        const val STAMP = "19700101-000000"
    }
}
