package com.vaultguard.app.data.local.db.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import com.vaultguard.app.data.local.db.entity.CredentialEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface CredentialDao {

    @Query("SELECT * FROM credentials WHERE isDeleted = 0 ORDER BY updatedAt DESC")
    fun getAllCredentials(): Flow<List<CredentialEntity>>

    @Query("SELECT * FROM credentials WHERE id = :id")
    suspend fun getById(id: String): CredentialEntity?

    @Upsert
    suspend fun upsert(credential: CredentialEntity)

    /**
     * Writes many rows in one transaction. Room wraps collection-valued DAO methods in a
     * transaction, so this is all-or-nothing.
     *
     * Used by the master-password change: re-encrypting row by row left the vault split
     * across two keys when a sweep failed part-way, with no key that opened all of it
     * (finding #5).
     */
    @Upsert
    suspend fun upsertAll(credentials: List<CredentialEntity>)

    @Query("UPDATE credentials SET isDeleted = 1, updatedAt = :now WHERE id = :id")
    suspend fun softDelete(id: String, now: Long = System.currentTimeMillis())

    /**
     * Rows changed since they were last pushed.
     *
     * A per-row dirty flag rather than a global "modified since" cursor. The cursor version
     * was stamped after the network round-trip, so anything saved while a sync was in
     * flight fell between the query and the new cursor and was never pushed (#19). A row
     * edited mid-sync is simply still dirty here.
     */
    @Query("SELECT * FROM credentials WHERE syncedAt IS NULL OR updatedAt > syncedAt")
    suspend fun getPendingPush(): List<CredentialEntity>

    /**
     * Tombstones old enough to drop, and already confirmed pushed.
     *
     * Soft-deleted rows were never purged, so the vault and its Firestore mirror grew
     * without bound (#24). The syncedAt condition means a tombstone is only forgotten once
     * the other side has definitely seen it.
     */
    @Query("SELECT * FROM credentials WHERE isDeleted = 1 AND syncedAt IS NOT NULL AND updatedAt < :before")
    suspend fun getPurgeableTombstones(before: Long): List<CredentialEntity>

    @Query("DELETE FROM credentials WHERE id IN (:ids)")
    suspend fun deleteByIds(ids: List<String>)

    @Query("SELECT * FROM credentials")
    suspend fun getAll(): List<CredentialEntity>

    @Query("SELECT * FROM credentials WHERE isDeleted = 0")
    fun getAllBlocking(): List<CredentialEntity>
}
