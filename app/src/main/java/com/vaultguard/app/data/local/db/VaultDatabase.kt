package com.vaultguard.app.data.local.db

import androidx.room.Database
import androidx.room.RoomDatabase
import com.vaultguard.app.data.local.db.dao.CredentialDao
import com.vaultguard.app.data.local.db.entity.CredentialEntity

@Database(
    entities = [CredentialEntity::class],
    version = 1,
    exportSchema = false
)
abstract class VaultDatabase : RoomDatabase() {
    abstract fun credentialDao(): CredentialDao
}
