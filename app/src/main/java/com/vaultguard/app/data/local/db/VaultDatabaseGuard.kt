package com.vaultguard.app.data.local.db

import java.io.File
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/**
 * Decides what to do about an unopenable `vault.db`, without ever destroying it.
 *
 * This class exists because the previous implementation responded to *any* exception
 * while probing the database by calling `deleteDatabase()` — justified by a comment
 * claiming a passphrase mismatch could only happen on a fresh install (finding #1). That
 * premise was false, and the failure it most plausibly fires on is a restored device,
 * where the vault is intact and only the Keystore-held passphrase is gone.
 *
 * Two rules follow, and both are load-bearing:
 *
 *  1. **Inspection never modifies anything.** [inspect] opens and closes. That is all.
 *  2. **[quarantine] renames, never deletes,** and is only ever called from an explicit,
 *     confirmed user action in the recovery screen.
 *
 * Kept free of Android and SQLCipher types so the file handling is testable on the host
 * JVM; the caller supplies the actual open attempt as a lambda.
 */
class VaultDatabaseGuard(
    private val databaseFile: File,
    private val clock: () -> Long = System::currentTimeMillis
) {

    companion object {
        /** SQLite writes these alongside the main file; they must travel with it. */
        val SIDECAR_SUFFIXES = listOf("", "-journal", "-wal", "-shm")

        const val QUARANTINE_INFIX = ".quarantine-"

        private val TIMESTAMP: DateTimeFormatter =
            DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").withZone(ZoneOffset.UTC)

        /**
         * Substrings SQLCipher and SQLite use when a file cannot be decrypted or parsed.
         * Anything not matching stays [VaultDatabaseStatus.Reason.TRANSIENT] so the app
         * never nudges the user toward replacing a database that is merely locked or on a
         * full disk.
         */
        private val CIPHER_FAILURE_MARKERS = listOf(
            "file is not a database",
            "file is encrypted",
            "not a database",
            "hmac",
            "malformed",
            "database disk image is malformed",
            "encrypted or is not a database"
        )
    }

    /**
     * Attempts [openProbe] against the database and classifies the result.
     * Makes no changes to the filesystem under any outcome.
     */
    fun inspect(openProbe: (File) -> Unit): VaultDatabaseStatus {
        if (!databaseFile.exists()) return VaultDatabaseStatus.Absent

        return try {
            openProbe(databaseFile)
            VaultDatabaseStatus.Healthy
        } catch (t: Throwable) {
            VaultDatabaseStatus.Unreadable(
                reason = classify(t),
                path = databaseFile.absolutePath,
                sizeBytes = runCatching { databaseFile.length() }.getOrDefault(0L),
                detail = t.message ?: t::class.java.simpleName
            )
        }
    }

    internal fun classify(t: Throwable): VaultDatabaseStatus.Reason {
        var cause: Throwable? = t
        while (cause != null) {
            val message = cause.message?.lowercase()
            if (message != null && CIPHER_FAILURE_MARKERS.any { message.contains(it) }) {
                return VaultDatabaseStatus.Reason.WRONG_PASSPHRASE_OR_CORRUPT
            }
            cause = cause.cause
        }
        return VaultDatabaseStatus.Reason.TRANSIENT
    }

    /**
     * Moves the database and its sidecar files aside so Room can create a fresh one,
     * preserving every byte under a timestamped name.
     *
     * Only ever invoked from a confirmed user action. Never overwrites an existing
     * quarantine — a second failure must not destroy the evidence from the first.
     *
     * @return the files that were moved, as (from, to) pairs.
     * @throws IllegalStateException if a file could not be renamed, so the caller reports
     *         failure rather than proceeding as though the vault were safely set aside.
     */
    fun quarantine(): List<Pair<File, File>> {
        val stamp = uniqueStamp()
        val parent = databaseFile.parentFile

        // Plan every move first. A half-quarantined database — main file moved, WAL left
        // behind — is worse than either outcome, because the leftover sidecar would then
        // be picked up by the fresh database Room creates next.
        val planned = SIDECAR_SUFFIXES
            .map { suffix ->
                File(parent, databaseFile.name + suffix) to
                    File(parent, databaseFile.name + QUARANTINE_INFIX + stamp + suffix)
            }
            .filter { (source, _) -> source.exists() }

        planned.firstOrNull { (_, target) -> target.exists() }?.let { (_, target) ->
            error("Quarantine target already exists: ${target.name}")
        }

        val moved = mutableListOf<Pair<File, File>>()
        for ((source, target) in planned) {
            if (source.renameTo(target)) {
                moved += source to target
                continue
            }
            // Put back whatever already moved, so a failure leaves the vault exactly as
            // it was rather than scattered across two names.
            moved.asReversed().forEach { (originalSource, movedTarget) ->
                movedTarget.renameTo(originalSource)
            }
            error("Could not move ${source.name} aside; vault left unchanged")
        }
        return moved
    }

    /** Existing quarantined copies, newest name first. */
    fun existingQuarantines(): List<File> {
        val parent = databaseFile.parentFile ?: return emptyList()
        val prefix = databaseFile.name + QUARANTINE_INFIX
        return parent.listFiles()
            ?.filter { it.name.startsWith(prefix) && SIDECAR_SUFFIXES.none { s -> s.isNotEmpty() && it.name.endsWith(s) } }
            ?.sortedByDescending { it.name }
            ?: emptyList()
    }

    /**
     * A base timestamp, with a numeric suffix if that second already has a quarantine.
     * Guarantees [quarantine] cannot clobber an earlier one.
     *
     * Checks every sidecar name, not just the main file: a leftover `-wal` from an
     * earlier quarantine is enough to make the whole move unsafe.
     */
    private fun uniqueStamp(): String {
        val base = TIMESTAMP.format(Instant.ofEpochMilli(clock()))
        val parent = databaseFile.parentFile

        fun taken(candidate: String) = SIDECAR_SUFFIXES.any { suffix ->
            File(parent, databaseFile.name + QUARANTINE_INFIX + candidate + suffix).exists()
        }

        var candidate = base
        var counter = 1
        while (taken(candidate)) {
            candidate = "$base-$counter"
            counter++
        }
        return candidate
    }
}
