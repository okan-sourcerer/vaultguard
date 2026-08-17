package com.vaultguard.app.data.local.db

import androidx.room.Database
import androidx.room.RoomDatabase
import com.vaultguard.app.data.local.db.dao.CredentialDao
import com.vaultguard.app.data.local.db.entity.CredentialEntity

@Database(
    entities = [CredentialEntity::class],
    version = 2,
    // Schemas are committed under app/schemas so migrations can be tested against the
    // real historical shape rather than a reconstruction.
    exportSchema = true
)
abstract class VaultDatabase : RoomDatabase() {
    abstract fun credentialDao(): CredentialDao
}
