package com.vaultguard.app.data.remote

import android.util.Base64
import com.google.firebase.Timestamp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Query
import com.google.firebase.firestore.SetOptions
import com.vaultguard.app.data.local.db.dao.CredentialDao
import com.vaultguard.app.data.local.db.entity.CredentialEntity
import com.vaultguard.app.security.MasterPasswordManager
import kotlinx.coroutines.tasks.await
import timber.log.Timber
import java.util.UUID
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

class SyncNotEnabledException : Exception("Cloud sync is not enabled.")
class SyncNotAuthenticatedException : Exception("Sign in to sync.")

data class SyncResult(
    val pushed: Int = 0,
    val pulled: Int = 0,
    val conflicts: Int = 0,
    val purged: Int = 0
)

/**
 * Replicates encrypted credential blobs to Firestore.
 *
 * ## Consent
 *
 * Every entry point requires sync to have been switched on and a user to be signed in.
 * Nothing signs in implicitly. The previous version called `signInAnonymously()` from
 * whatever needed a user, so pulling to refresh the vault list uploaded the whole vault to
 * an anonymous account the owner never asked for (#15).
 *
 * ## Ordering
 *
 * Push is driven by a per-row dirty flag, pull by a Firestore **server** timestamp. Neither
 * compares one device's clock against another's — see [SyncMerge].
 */
@Singleton
class FirebaseSyncService @Inject constructor(
    private val firestore: FirebaseFirestore,
    private val auth: FirebaseAuth,
    private val credentialDao: CredentialDao,
    private val masterPasswordManager: MasterPasswordManager,
    private val syncPreferences: SyncPreferences
) {

    companion object {
        private const val COLLECTION_VAULTS = "vaults"
        private const val COLLECTION_CREDENTIALS = "credentials"

        private const val FIELD_SALT = "salt"
        private const val FIELD_VERIFICATION_CIPHERTEXT = "verificationCiphertext"
        private const val FIELD_VERIFICATION_IV = "verificationIv"
        private const val FIELD_VAULT_KEY_CIPHERTEXT = "vaultKeyCiphertext"
        private const val FIELD_VAULT_KEY_IV = "vaultKeyIv"

        private const val FIELD_ENCRYPTED_PAYLOAD = "encryptedPayload"
        private const val FIELD_IV = "iv"
        private const val FIELD_CREATED_AT = "createdAt"
        private const val FIELD_UPDATED_AT = "updatedAt"
        private const val FIELD_PASSWORD_CHANGED_AT = "passwordChangedAt"
        private const val FIELD_IS_DELETED = "isDeleted"

        /** Server-assigned, so every device agrees on the ordering regardless of its clock. */
        private const val FIELD_SERVER_UPDATED_AT = "serverUpdatedAt"

        private val TOMBSTONE_RETENTION_MILLIS = TimeUnit.DAYS.toMillis(30)
    }

    data class RemoteVaultConfig(
        val salt: ByteArray,
        val verificationCiphertext: ByteArray,
        val verificationIv: ByteArray,
        val vaultKeyCiphertext: ByteArray?,
        val vaultKeyIv: ByteArray?
    ) {
        override fun equals(other: Any?) = this === other ||
            (other is RemoteVaultConfig && salt.contentEquals(other.salt))
        override fun hashCode() = salt.contentHashCode()
    }

    val isSignedIn: Boolean get() = auth.currentUser != null

    val isSyncEnabled: Boolean get() = syncPreferences.isEnabled && isSignedIn

    private fun requireUserId(): String =
        auth.currentUser?.uid ?: throw SyncNotAuthenticatedException()

    private fun requireEnabled() {
        if (!syncPreferences.isEnabled) throw SyncNotEnabledException()
        requireUserId()
    }

    private fun vaultDocument(uid: String = requireUserId()) =
        firestore.collection(COLLECTION_VAULTS).document(uid)

    private fun credentialsCollection(uid: String = requireUserId()) =
        vaultDocument(uid).collection(COLLECTION_CREDENTIALS)

    /** Turns sync on and performs the first exchange. */
    suspend fun enable(): SyncResult {
        requireUserId()
        syncPreferences.isEnabled = true
        return fullSync()
    }

    /**
     * Turns sync off. With [deleteRemote], the account's vault is removed from Firestore
     * as well.
     *
     * Sign-out used to say "Cloud sync disabled" and then immediately sign in anonymously,
     * so the next sync re-uploaded everything to a fresh cloud vault (#16). There was also
     * no way to remove what had already been uploaded.
     */
    suspend fun disable(deleteRemote: Boolean): Int {
        var deleted = 0
        if (deleteRemote && isSignedIn) {
            deleted = deleteRemoteVault()
        }
        syncPreferences.isEnabled = false
        syncPreferences.clearCursors()
        return deleted
    }

    suspend fun deleteRemoteVault(): Int {
        val uid = requireUserId()
        val documents = credentialsCollection(uid).get().await().documents
        var deleted = 0
        for (chunk in SyncMerge.batched(documents)) {
            val batch = firestore.batch()
            chunk.forEach { batch.delete(it.reference) }
            batch.commit().await()
            deleted += chunk.size
        }
        vaultDocument(uid).delete().await()
        Timber.i("Deleted %d remote credentials and the vault config", deleted)
        return deleted
    }

    // -- Vault config ---------------------------------------------------------------------

    /**
     * Publishes the material another device needs to open this vault.
     *
     * Includes the wrapped vault key. Without it a second device can verify the master
     * password and still not reach the key the rows are encrypted under — the gap that made
     * adopting a remote config orphan the vault (#4).
     */
    suspend fun pushVaultConfig() {
        requireEnabled()
        val (verificationCiphertext, verificationIv) = masterPasswordManager.getVerificationData()
        val wrapped = masterPasswordManager.getWrappedVaultKey()

        val data = buildMap<String, Any> {
            put(FIELD_SALT, masterPasswordManager.getSalt().toBase64())
            put(FIELD_VERIFICATION_CIPHERTEXT, verificationCiphertext.toBase64())
            put(FIELD_VERIFICATION_IV, verificationIv.toBase64())
            if (wrapped != null) {
                put(FIELD_VAULT_KEY_CIPHERTEXT, wrapped.ciphertext.toBase64())
                put(FIELD_VAULT_KEY_IV, wrapped.iv.toBase64())
            }
        }
        vaultDocument().set(data, SetOptions.merge()).await()
    }

    suspend fun pullVaultConfig(): RemoteVaultConfig? {
        requireUserId()
        val doc = vaultDocument().get().await()
        val salt = doc.getString(FIELD_SALT)?.fromBase64() ?: return null
        val ciphertext = doc.getString(FIELD_VERIFICATION_CIPHERTEXT)?.fromBase64() ?: return null
        val iv = doc.getString(FIELD_VERIFICATION_IV)?.fromBase64() ?: return null
        return RemoteVaultConfig(
            salt = salt,
            verificationCiphertext = ciphertext,
            verificationIv = iv,
            vaultKeyCiphertext = doc.getString(FIELD_VAULT_KEY_CIPHERTEXT)?.fromBase64(),
            vaultKeyIv = doc.getString(FIELD_VAULT_KEY_IV)?.fromBase64()
        )
    }

    // -- Sync ------------------------------------------------------------------------------

    suspend fun fullSync(): SyncResult {
        requireEnabled()

        val remoteConfig = runCatching { pullVaultConfig() }.getOrNull()

        // The decision lives in SyncMerge so it can be exercised without Firestore, which
        // is the same reason the row-merge rules live there. It has three outcomes, not
        // two: a configuration can exist, match, and still be missing the wrapped vault
        // key (#64).
        val action = SyncMerge.configAction(
            remoteSalt = remoteConfig?.salt,
            remoteHasWrappedKey = remoteConfig?.vaultKeyCiphertext != null,
            localSalt = masterPasswordManager.getSalt(),
            localHasWrappedKey = masterPasswordManager.hasWrappedVaultKey
        )

        when (action) {
            SyncMerge.ConfigAction.Publish ->
                runCatching { pushVaultConfig() }
                    .onFailure { Timber.w(it, "Could not publish vault config") }

            SyncMerge.ConfigAction.Refuse -> {
                // A vault from elsewhere. Uploading local rows now would put blobs encrypted
                // under this device's key into a vault keyed differently, which nothing could
                // ever read — the poisoning half of #4. Refuse rather than guess.
                syncPreferences.isEnabled = false
                throw IllegalStateException(
                    "This account already holds a different vault. Sync has been turned off to " +
                        "avoid mixing the two. Export a backup, then either delete the cloud " +
                        "vault from Settings or import into a fresh install."
                )
            }

            SyncMerge.ConfigAction.Proceed -> Unit
        }

        val pushed = pushPending()
        val pull = pullChanges()
        val purged = purgeTombstones()

        return SyncResult(pushed, pull.pulled, pull.conflicts, purged)
    }

    /** Uploads every row whose local edit has not reached the server. */
    private suspend fun pushPending(): Int {
        val uid = requireUserId()
        val pending = credentialDao.getPendingPush()
        if (pending.isEmpty()) return 0

        var pushed = 0
        for (chunk in SyncMerge.batched(pending)) {
            val batch = firestore.batch()
            chunk.forEach { entity ->
                batch.set(credentialsCollection(uid).document(entity.id), entity.toRemoteMap())
            }
            batch.commit().await()

            // syncedAt records the value that was actually uploaded, not "now". A row
            // edited while the batch was in flight has a higher updatedAt and stays dirty.
            credentialDao.upsertAll(chunk.map { it.copy(syncedAt = it.updatedAt) })
            pushed += chunk.size
        }
        Timber.i("Pushed %d credentials", pushed)
        return pushed
    }

    private data class PullOutcome(val pulled: Int, val conflicts: Int)

    private suspend fun pullChanges(): PullOutcome {
        val uid = requireUserId()
        val cursor = syncPreferences.pullCursor(uid)

        val snapshot = credentialsCollection(uid)
            .whereGreaterThan(FIELD_SERVER_UPDATED_AT, Timestamp(cursor / 1000, 0))
            .orderBy(FIELD_SERVER_UPDATED_AT, Query.Direction.ASCENDING)
            .get()
            .await()

        val toWrite = mutableListOf<CredentialEntity>()
        var conflicts = 0
        var highest = cursor

        for (doc in snapshot.documents) {
            val serverTime = doc.getTimestamp(FIELD_SERVER_UPDATED_AT)
                ?: continue // still pending on the server; it will arrive next time
            val remote = doc.toEntity() ?: continue

            val local = credentialDao.getById(remote.id)
            val decision = SyncMerge.decide(
                local = local?.let {
                    SyncMerge.LocalState(it.updatedAt, it.syncedAt, it.isDeleted)
                },
                remoteIsDeleted = remote.isDeleted
            )

            when (decision) {
                SyncMerge.Decision.TakeRemote ->
                    toWrite += remote.copy(syncedAt = remote.updatedAt)

                SyncMerge.Decision.KeepLocal -> Unit

                SyncMerge.Decision.Conflict -> {
                    // Keep both. The remote copy lands under a fresh id and will be pushed
                    // back on the next round, so neither edit is lost.
                    conflicts++
                    toWrite += remote.copy(id = UUID.randomUUID().toString(), syncedAt = null)
                    Timber.w("Sync conflict on %s; kept both copies", remote.id)
                }
            }

            highest = maxOf(highest, serverTime.toDate().time)
        }

        if (toWrite.isNotEmpty()) credentialDao.upsertAll(toWrite)
        // Advanced only after the rows are committed, so a crash re-pulls rather than skips.
        if (highest > cursor) syncPreferences.setPullCursor(uid, highest)

        if (toWrite.isNotEmpty()) Timber.i("Pulled %d credentials", toWrite.size)
        return PullOutcome(toWrite.size, conflicts)
    }

    /** Drops tombstones both sides have seen and that are older than the retention window. */
    private suspend fun purgeTombstones(): Int {
        val uid = requireUserId()
        val cutoff = System.currentTimeMillis() - TOMBSTONE_RETENTION_MILLIS
        val stale = credentialDao.getPurgeableTombstones(cutoff)
        if (stale.isEmpty()) return 0

        for (chunk in SyncMerge.batched(stale)) {
            val batch = firestore.batch()
            chunk.forEach { batch.delete(credentialsCollection(uid).document(it.id)) }
            batch.commit().await()
            credentialDao.deleteByIds(chunk.map { it.id })
        }
        Timber.i("Purged %d tombstones", stale.size)
        return stale.size
    }

    // -- Mapping ------------------------------------------------------------------------------

    private fun CredentialEntity.toRemoteMap(): Map<String, Any?> = mapOf(
        FIELD_ENCRYPTED_PAYLOAD to encryptedPayload.toBase64(),
        FIELD_IV to iv.toBase64(),
        FIELD_CREATED_AT to createdAt,
        FIELD_UPDATED_AT to updatedAt,
        FIELD_PASSWORD_CHANGED_AT to passwordChangedAt,
        FIELD_IS_DELETED to isDeleted,
        FIELD_SERVER_UPDATED_AT to FieldValue.serverTimestamp()
    )

    private fun com.google.firebase.firestore.DocumentSnapshot.toEntity(): CredentialEntity? =
        try {
            val payload = getString(FIELD_ENCRYPTED_PAYLOAD)?.fromBase64()
            val iv = getString(FIELD_IV)?.fromBase64()
            if (payload == null || iv == null) {
                null
            } else {
                val updatedAt = getLong(FIELD_UPDATED_AT) ?: 0L
                CredentialEntity(
                    id = id,
                    encryptedPayload = payload,
                    iv = iv,
                    createdAt = getLong(FIELD_CREATED_AT) ?: updatedAt,
                    updatedAt = updatedAt,
                    passwordChangedAt = getLong(FIELD_PASSWORD_CHANGED_AT) ?: updatedAt,
                    isDeleted = getBoolean(FIELD_IS_DELETED) ?: false
                )
            }
        } catch (e: Exception) {
            Timber.e(e, "Skipping malformed remote credential %s", id)
            null
        }

    private fun ByteArray.toBase64(): String = Base64.encodeToString(this, Base64.NO_WRAP)

    private fun String.fromBase64(): ByteArray = Base64.decode(this, Base64.NO_WRAP)
}
