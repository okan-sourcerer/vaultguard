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

    @Query("SELECT * FROM credentials WHERE updatedAt > :since")
    suspend fun getModifiedSince(since: Long): List<CredentialEntity>

    @Query("SELECT * FROM credentials")
    suspend fun getAll(): List<CredentialEntity>

    @Query("SELECT * FROM credentials WHERE isDeleted = 0")
    fun getAllBlocking(): List<CredentialEntity>
}
