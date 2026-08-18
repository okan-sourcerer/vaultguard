package com.vaultguard.app.data.remote

/**
 * Decides what to do when a remote row meets a local one.
 *
 * Pure, so the rules can be exercised directly. The old logic was three conditions inlined
 * in a loop and comparing wall-clock timestamps written by different devices, which is what
 * made findings #19, #20 and #23 possible at all.
 *
 * ## Dirty rather than a cursor
 *
 * A row is dirty when it has changed since it was last pushed: `syncedAt == null ||
 * updatedAt > syncedAt`. That is a per-row fact, so nothing depends on a global "last sync"
 * timestamp — which is what lost writes made *during* a sync (#19). A row modified while a
 * push was in flight is simply still dirty afterwards.
 *
 * ## Server time rather than device time
 *
 * Ordering across devices uses the Firestore server timestamp, never `updatedAt`. Two
 * phones disagreeing about the time is normal, and comparing their clocks against each
 * other silently dropped changes (#20). `updatedAt` remains meaningful *within* one device,
 * for the dirty check and for display.
 */
object SyncMerge {

    /** The local side of the comparison. Null when the row is only known remotely. */
    data class LocalState(
        val updatedAt: Long,
        val syncedAt: Long?,
        val isDeleted: Boolean
    ) {
        val isDirty: Boolean get() = syncedAt == null || updatedAt > syncedAt
    }

    sealed interface Decision {
        /** Overwrite the local row, or insert it if absent. */
        data object TakeRemote : Decision

        /** The local row is newer and already queued for push; leave it alone. */
        data object KeepLocal : Decision

        /**
         * Both sides changed since the last sync. Neither is discarded — the remote copy
         * is written alongside the local one under a fresh id, and the user reconciles.
         *
         * Last-write-wins used to pick one by comparing device clocks and drop the other
         * without a word (#23). Duplication is recoverable; a silently discarded password
         * is not.
         */
        data object Conflict : Decision
    }

    fun decide(local: LocalState?, remoteIsDeleted: Boolean): Decision {
        // Never seen here before: nothing to lose by taking it.
        if (local == null) return Decision.TakeRemote

        // Locally untouched since its last push, so the remote copy is strictly newer —
        // the caller only offers rows the server has stamped after our cursor.
        if (!local.isDirty) return Decision.TakeRemote

        // A tombstone arriving for a row edited locally: honour the delete rather than
        // resurrect it as a duplicate. Deleting is unambiguous in a way an edit is not,
        // and the entry remains recoverable from a backup.
        if (remoteIsDeleted) return Decision.TakeRemote

        // A local delete against a remote edit: the delete stands.
        if (local.isDeleted) return Decision.KeepLocal

        return Decision.Conflict
    }

    // -- The vault config ---------------------------------------------------------------

    /** What a sync should do about `vaults/{uid}` before it starts exchanging rows. */
    sealed interface ConfigAction {
        /** Write the local configuration to the cloud. */
        data object Publish : ConfigAction

        /** The account holds a different vault; do not mix them. */
        data object Refuse : ConfigAction

        /** The cloud copy is complete and matches. */
        data object Proceed : ConfigAction
    }

    /**
     * Decides whether the remote vault configuration needs (re)publishing.
     *
     * The third case is the one that is easy to miss, and did get missed. Publishing used
     * to happen only when the cloud held no configuration at all. A vault whose config was
     * published *before* it was converted to the vault-key layout therefore carried a salt
     * and a verification blob and no wrapped vault key — and conversion does not change the
     * salt, so every later sync saw a configuration that existed and matched, and never
     * republished. The wrapped key could not arrive by any route short of a master-password
     * change (#64).
     *
     * The visible consequence was a second device that could verify the master password and
     * reach nothing, which is finding #4 arriving by a different road.
     *
     * @param remoteSalt null when the cloud holds no configuration at all.
     */
    fun configAction(
        remoteSalt: ByteArray?,
        remoteHasWrappedKey: Boolean,
        localSalt: ByteArray,
        localHasWrappedKey: Boolean
    ): ConfigAction = when {
        remoteSalt == null -> ConfigAction.Publish

        // Uploading local rows into a vault keyed differently would fill it with blobs
        // nothing could ever read.
        !remoteSalt.contentEquals(localSalt) -> ConfigAction.Refuse

        // Same vault, incomplete copy: publish what the cloud is missing. The write merges,
        // so this adds the wrapped key rather than rewriting anything already there.
        !remoteHasWrappedKey && localHasWrappedKey -> ConfigAction.Publish

        else -> ConfigAction.Proceed
    }

    /**
     * Firestore rejects a batch above 500 operations. Pushing every row in one commit meant
     * that any vault over that size failed to sync outright, and the master-password sweep
     * used to dirty every row at once, so it was reachable (#21).
     */
    const val MAX_BATCH_SIZE = 450

    fun <T> batched(items: List<T>): List<List<T>> =
        if (items.isEmpty()) emptyList() else items.chunked(MAX_BATCH_SIZE)
}
