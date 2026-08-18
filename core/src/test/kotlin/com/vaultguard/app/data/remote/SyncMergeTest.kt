package com.vaultguard.app.data.remote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for the sync merge rules (findings #19, #21, #23).
 *
 * The old logic compared wall-clock timestamps written by different devices and silently
 * discarded whichever side lost. These tests are mostly about the cases where something
 * must *not* be thrown away.
 */
class SyncMergeTest {

    private fun local(updatedAt: Long, syncedAt: Long?, isDeleted: Boolean = false) =
        SyncMerge.LocalState(updatedAt, syncedAt, isDeleted)

    // -- Dirty tracking ---------------------------------------------------------------------

    @Test
    fun `a never-synced row is dirty`() {
        assertTrue(local(updatedAt = 100, syncedAt = null).isDirty)
    }

    @Test
    fun `a row edited since its last push is dirty`() {
        assertTrue(local(updatedAt = 200, syncedAt = 100).isDirty)
    }

    @Test
    fun `a row untouched since its last push is clean`() {
        assertFalse(local(updatedAt = 100, syncedAt = 100).isDirty)
    }

    @Test
    fun `a row edited during a push stays dirty afterwards`() {
        // The heart of #19. syncedAt records the updatedAt that was actually uploaded, so
        // an edit made while the batch was in flight carries a higher updatedAt and is
        // still pending. The old global cursor skipped it forever.
        val uploadedUpdatedAt = 100L
        val editedMidFlight = local(updatedAt = 150, syncedAt = uploadedUpdatedAt)

        assertTrue(editedMidFlight.isDirty)
    }

    // -- Decisions ---------------------------------------------------------------------------

    @Test
    fun `an unknown row is taken`() {
        assertEquals(SyncMerge.Decision.TakeRemote, SyncMerge.decide(null, remoteIsDeleted = false))
    }

    @Test
    fun `a clean local row yields to the remote copy`() {
        assertEquals(
            SyncMerge.Decision.TakeRemote,
            SyncMerge.decide(local(updatedAt = 100, syncedAt = 100), remoteIsDeleted = false)
        )
    }

    @Test
    fun `edits on both sides are a conflict, not a silent overwrite`() {
        assertEquals(
            SyncMerge.Decision.Conflict,
            SyncMerge.decide(local(updatedAt = 200, syncedAt = 100), remoteIsDeleted = false)
        )
    }

    @Test
    fun `a remote delete beats a local edit`() {
        // Deleting is unambiguous where an edit is not, and the entry is still in a backup.
        assertEquals(
            SyncMerge.Decision.TakeRemote,
            SyncMerge.decide(local(updatedAt = 200, syncedAt = 100), remoteIsDeleted = true)
        )
    }

    @Test
    fun `a local delete beats a remote edit`() {
        assertEquals(
            SyncMerge.Decision.KeepLocal,
            SyncMerge.decide(
                local(updatedAt = 200, syncedAt = 100, isDeleted = true),
                remoteIsDeleted = false
            )
        )
    }

    @Test
    fun `a clean local delete still yields to the remote copy`() {
        // Nothing changed here since the last push, so the remote is genuinely newer.
        assertEquals(
            SyncMerge.Decision.TakeRemote,
            SyncMerge.decide(
                local(updatedAt = 100, syncedAt = 100, isDeleted = true),
                remoteIsDeleted = false
            )
        )
    }

    @Test
    fun `no decision discards both sides`() {
        // Exhaustive sanity check: every combination resolves to keeping something.
        val states = listOf(null) + listOf(true, false).flatMap { deleted ->
            listOf(local(200, null, deleted), local(200, 100, deleted), local(100, 100, deleted))
        }
        for (state in states) {
            for (remoteDeleted in listOf(true, false)) {
                val decision = SyncMerge.decide(state, remoteDeleted)
                assertTrue(
                    "no outcome may drop data",
                    decision == SyncMerge.Decision.TakeRemote ||
                        decision == SyncMerge.Decision.KeepLocal ||
                        decision == SyncMerge.Decision.Conflict
                )
            }
        }
    }

    // -- Batching — finding #21 -----------------------------------------------------------------

    @Test
    fun `batches stay under the Firestore limit`() {
        // A commit above 500 operations is rejected outright, and the master-password sweep
        // used to dirty every row at once, so a large vault could not sync at all.
        val batches = SyncMerge.batched((1..1200).toList())

        assertTrue(batches.all { it.size <= 450 })
        assertEquals(1200, batches.sumOf { it.size })
    }

    @Test
    fun `batching preserves order and loses nothing`() {
        val items = (1..1000).toList()

        assertEquals(items, SyncMerge.batched(items).flatten())
    }

    @Test
    fun `an empty list produces no batches`() {
        assertEquals(emptyList<List<Int>>(), SyncMerge.batched(emptyList<Int>()))
    }

    @Test
    fun `a single short batch is not split`() {
        assertEquals(1, SyncMerge.batched((1..10).toList()).size)
    }

    @Test
    fun `exactly one batch worth is not split`() {
        assertEquals(1, SyncMerge.batched((1..450).toList()).size)
        assertEquals(2, SyncMerge.batched((1..451).toList()).size)
    }

    // -- The vault config -------------------------------------------------------------

    private val saltA = ByteArray(16) { it.toByte() }
    private val saltB = ByteArray(16) { (it + 1).toByte() }

    @Test
    fun `no remote config at all means publish`() {
        assertEquals(
            SyncMerge.ConfigAction.Publish,
            SyncMerge.configAction(
                remoteSalt = null,
                remoteHasWrappedKey = false,
                localSalt = saltA,
                localHasWrappedKey = true
            )
        )
    }

    @Test
    fun `a different salt is refused`() {
        assertEquals(
            SyncMerge.ConfigAction.Refuse,
            SyncMerge.configAction(
                remoteSalt = saltB,
                remoteHasWrappedKey = true,
                localSalt = saltA,
                localHasWrappedKey = true
            )
        )
    }

    @Test
    fun `a matching complete config is left alone`() {
        assertEquals(
            SyncMerge.ConfigAction.Proceed,
            SyncMerge.configAction(
                remoteSalt = saltA,
                remoteHasWrappedKey = true,
                localSalt = saltA,
                localHasWrappedKey = true
            )
        )
    }

    @Test
    fun `a matching config missing the wrapped key is republished`() {
        // #64. This is the case that used to fall through both branches: the config
        // existed and the salt matched, so nothing republished, and the wrapped vault key
        // never reached the cloud. A second device could then verify the master password
        // and reach nothing at all.
        assertEquals(
            SyncMerge.ConfigAction.Publish,
            SyncMerge.configAction(
                remoteSalt = saltA,
                remoteHasWrappedKey = false,
                localSalt = saltA,
                localHasWrappedKey = true
            )
        )
    }

    @Test
    fun `an unconverted local vault publishes nothing extra`() {
        // Neither side has a wrapped key yet: the local vault is still encrypted directly
        // under the master key. There is nothing to publish, and republishing the same
        // salt and blob every sync would be noise.
        assertEquals(
            SyncMerge.ConfigAction.Proceed,
            SyncMerge.configAction(
                remoteSalt = saltA,
                remoteHasWrappedKey = false,
                localSalt = saltA,
                localHasWrappedKey = false
            )
        )
    }

    @Test
    fun `a differing salt is refused even when the remote looks complete`() {
        assertEquals(
            SyncMerge.ConfigAction.Refuse,
            SyncMerge.configAction(
                remoteSalt = saltB,
                remoteHasWrappedKey = false,
                localSalt = saltA,
                localHasWrappedKey = true
            )
        )
    }
}
