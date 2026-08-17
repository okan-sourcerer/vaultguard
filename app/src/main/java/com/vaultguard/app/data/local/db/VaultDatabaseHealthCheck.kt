package com.vaultguard.app.data.local.db

import android.content.Context
import com.vaultguard.app.security.MasterPasswordManager
import dagger.hilt.android.qualifiers.ApplicationContext
import net.zetetic.database.sqlcipher.SQLiteDatabase
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Probes `vault.db` once per process and publishes the result to
 * [VaultDatabaseStatusHolder].
 *
 * Runs before the UI touches the DAO so that an unopenable database produces a recovery
 * screen rather than an exception buried inside a Room `Flow` — where
 * `CredentialRepositoryImpl` would swallow it into an empty list (finding #40).
 */
@Singleton
class VaultDatabaseHealthCheck @Inject constructor(
    @ApplicationContext private val context: Context,
    private val masterPasswordManager: MasterPasswordManager,
    private val statusHolder: VaultDatabaseStatusHolder
) {
    companion object {
        const val DATABASE_NAME = "vault.db"
    }

    private val guard by lazy { VaultDatabaseGuard(context.getDatabasePath(DATABASE_NAME)) }

    @Volatile
    private var result: VaultDatabaseStatus? = null

    /** Idempotent — the probe runs once per process, however many callers ask. */
    @Synchronized
    fun runOnce(): VaultDatabaseStatus {
        result?.let { return it }

        val status = guard.inspect { file -> openAndClose(file) }
        result = status
        statusHolder.record(status)
        return status
    }

    /**
     * Moves the unreadable database aside, preserving it, so a fresh one can be created.
     * Only called from a confirmed user action in the recovery screen.
     */
    fun quarantine(): List<Pair<File, File>> {
        val moved = guard.quarantine()
        result = null
        statusHolder.record(VaultDatabaseStatus.Absent)
        return moved
    }

    fun existingQuarantines(): List<File> = guard.existingQuarantines()

    private fun openAndClose(file: File) {
        var db: SQLiteDatabase? = null
        try {
            db = SQLiteDatabase.openDatabase(
                file.absolutePath,
                masterPasswordManager.getDatabasePassphrase(),
                null,
                SQLiteDatabase.OPEN_READONLY,
                null
            )
            // Touching the schema forces SQLCipher to actually decrypt a page. Opening
            // alone can succeed lazily against a file it cannot read.
            db.rawQuery("SELECT count(*) FROM sqlite_master", null).use { it.moveToFirst() }
        } finally {
            runCatching { db?.close() }
        }
    }
}
