package com.vaultguard.desktop.service

import com.vaultguard.app.domain.model.Credential
import com.vaultguard.app.domain.repository.VaultSnapshot
import com.vaultguard.desktop.cloud.CloudConnect
import com.vaultguard.desktop.cloud.DesktopConfig
import com.vaultguard.desktop.cloud.OpenVault
import com.vaultguard.desktop.cloud.RemoteVaultCodec
import com.vaultguard.desktop.cloud.SavedSession
import java.util.concurrent.TimeUnit

/**
 * How long an unlocked vault may sit unused.
 *
 * Idle here means *since the vault was last used*, not since the user last touched a
 * keyboard: a background service has no interaction to measure, and the meaningful question
 * is how long a key has been sitting in memory unread.
 *
 * Measured on a monotonic clock. Wall-clock time can jump — daylight saving, an NTP
 * correction, a user changing it — and this codebase has already been bitten by comparing
 * device clocks (#20) and by a lockout that could be escaped by winding one back.
 */
data class AutoLockPolicy(val timeoutMillis: Long) {

    val isEnabled: Boolean get() = timeoutMillis > 0

    fun shouldLock(idleMillis: Long): Boolean = isEnabled && idleMillis >= timeoutMillis

    fun remainingMillis(idleMillis: Long): Long =
        if (!isEnabled) Long.MAX_VALUE else (timeoutMillis - idleMillis).coerceAtLeast(0)

    companion object {
        /**
         * Fifteen minutes. Android's five exists because phones get lost and pocketed; a
         * desktop that locks that aggressively trains the user to pick a shorter password.
         */
        val DEFAULT = AutoLockPolicy(TimeUnit.MINUTES.toMillis(15))

        val NEVER = AutoLockPolicy(0)
    }
}

/** What the service can be doing, and what the menu offers in each case. */
enum class ServiceState {
    /** No saved sign-in. Getting in needs a browser. */
    SIGNED_OUT,

    /** A saved sign-in exists; the vault is sealed. Getting in needs the master password. */
    LOCKED,

    /** The vault key is in memory. */
    UNLOCKED
}

/**
 * The resident half of the desktop client: holds an unlocked vault so something else can
 * use it, and takes it away again.
 *
 * This is the piece the browser extension will talk to, and the reason auto-locking had to
 * be answered rather than deferred. The CLI could hold a key for a whole session because a
 * session was a thing the user was sitting in front of and closed; a service is not.
 *
 * Deliberately free of any UI. Prompting and reporting are passed in, so the same service
 * runs under a tray icon, a test, or whatever replaces the tray on a desktop where AWT
 * cannot draw one.
 */
class VaultService(
    private val config: DesktopConfig,
    private val policy: AutoLockPolicy = AutoLockPolicy.DEFAULT,
    /** Monotonic by default: wall-clock time can jump, and an auto-lock must not. */
    private val clock: () -> Long = { System.nanoTime() / 1_000_000 },
    private val sessionFile: java.io.File = SavedSession.defaultPath
) {
    private var open: OpenVault? = null
    private var snapshot: VaultSnapshot<Credential> = VaultSnapshot(emptyList())
    private var lastUsedAt: Long = clock()

    /** Fired whenever the state changes, so a UI can redraw without polling. */
    var onStateChanged: (ServiceState) -> Unit = {}

    val state: ServiceState
        get() = when {
            open != null -> ServiceState.UNLOCKED
            SavedSession.saltOf(sessionFile) != null -> ServiceState.LOCKED
            else -> ServiceState.SIGNED_OUT
        }

    val entryCount: Int get() = snapshot.items.size

    val undecryptableCount: Int get() = snapshot.undecryptableCount

    val email: String? get() = open?.email

    val idleMillis: Long get() = clock() - lastUsedAt

    /**
     * Reads the vault, and counts as use.
     *
     * Everything that reaches the credentials goes through here rather than touching
     * [snapshot], so a consumer cannot read the vault without resetting the idle timer —
     * which would let it be locked out from under an extension mid-use.
     */
    fun credentials(): List<Credential> {
        touch()
        return snapshot.items
    }

    /** Marks the vault as used now. */
    fun touch() {
        lastUsedAt = clock()
    }

    /**
     * Signs in if needed, unlocks, and fetches.
     *
     * @return true if the vault is now open.
     */
    fun unlock(
        askPassword: (String) -> CharArray?,
        say: (String) -> Unit,
        warn: (String) -> Unit
    ): Boolean {
        if (open != null) return true

        val opened = CloudConnect.open(config, askPassword, say, warn) ?: return false
        open = opened
        touch()
        val fetched = refresh(warn)
        onStateChanged(state)
        return fetched
    }

    /** Re-reads the vault. Requires it to be unlocked. */
    fun refresh(warn: (String) -> Unit): Boolean {
        val opened = open ?: return false
        return try {
            val rows = opened.client.credentialDocuments()
                .mapNotNull { RemoteVaultCodec.readCredentialRow(it) }
            snapshot = opened.vault.decrypt(rows, opened.vaultKey)
            touch()

            // Never presented as absence. A row that will not open is the situation #40 is
            // about, and a service that quietly serves fewer credentials than the vault
            // holds is the worst version of it.
            if (snapshot.hasUndecryptable) {
                warn(
                    "${snapshot.undecryptableCount} entries could not be decrypted and are " +
                        "not being served. Check the phone."
                )
            }
            true
        } catch (e: Exception) {
            warn(e.message ?: "Could not refresh the vault.")
            false
        }
    }

    /**
     * Drops the vault key and everything decrypted with it, keeping the saved sign-in.
     *
     * The next unlock needs the master password but not a browser.
     */
    fun lock() {
        open?.vault?.lock()
        open = null
        snapshot = VaultSnapshot(emptyList())
        onStateChanged(state)
    }

    /**
     * Locks, and forgets the saved sign-in as well. The next unlock needs a browser.
     *
     * The saved session goes first, so the single notification [lock] fires already reports
     * SIGNED_OUT. Locking first announced a LOCKED state that was true for microseconds and
     * made a UI flash through it on the way out.
     */
    fun signOut() {
        SavedSession.clear(sessionFile)
        lock()
    }

    /**
     * Locks if the vault has sat unused past the policy.
     *
     * @return true if it locked on this call, so a caller can say so once rather than every
     *         time it checks.
     */
    fun lockIfIdle(): Boolean {
        if (open == null || !policy.shouldLock(idleMillis)) return false
        lock()
        return true
    }

    fun remainingBeforeLock(): Long = policy.remainingMillis(idleMillis)
}
