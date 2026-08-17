package com.vaultguard.app.data.local.db

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Outcome of opening `vault.db` at startup.
 *
 * Deliberately carries no reference to the database itself, so the recovery UI can be
 * built and shown when the database is exactly what is broken.
 */
sealed interface VaultDatabaseStatus {

    /** Opened and decrypted successfully. */
    data object Healthy : VaultDatabaseStatus

    /** No database file yet — Room will create one on first use. Normal on a fresh install. */
    data object Absent : VaultDatabaseStatus

    /**
     * The file exists but could not be opened. **The file has not been touched.**
     *
     * [sizeBytes] is surfaced to the user as evidence the data is still on disk: the
     * previous implementation deleted it here (finding #1), so showing that the bytes
     * survive is the point.
     */
    data class Unreadable(
        val reason: Reason,
        val path: String,
        val sizeBytes: Long,
        val detail: String?
    ) : VaultDatabaseStatus

    enum class Reason {
        /**
         * SQLCipher rejected the file: wrong passphrase, or genuine corruption. The usual
         * cause is a lost `vault_secure_prefs`, which makes `getDatabasePassphrase()` mint
         * a fresh random passphrase that cannot open the existing database.
         */
        WRONG_PASSPHRASE_OR_CORRUPT,

        /**
         * Something environmental — I/O error, disk full, file locked. The database is
         * probably fine and a retry may succeed.
         *
         * This is the default for unrecognised failures **on purpose**: it is the reading
         * under which the app does not encourage the user to replace a healthy vault.
         */
        TRANSIENT
    }
}

/**
 * Holds the startup database status so the UI can react without depending on the DAO.
 */
@Singleton
class VaultDatabaseStatusHolder @Inject constructor() {

    private val _status = MutableStateFlow<VaultDatabaseStatus>(VaultDatabaseStatus.Healthy)
    val status: StateFlow<VaultDatabaseStatus> = _status.asStateFlow()

    val isUnreadable: Boolean get() = _status.value is VaultDatabaseStatus.Unreadable

    fun record(status: VaultDatabaseStatus) {
        _status.value = status
    }
}
