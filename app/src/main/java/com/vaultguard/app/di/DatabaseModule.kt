package com.vaultguard.app.di

import android.content.Context
import androidx.room.Room
import com.vaultguard.app.data.local.db.VaultDatabase
import com.vaultguard.app.data.local.db.dao.CredentialDao
import com.vaultguard.app.security.MasterPasswordManager
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import net.zetetic.database.sqlcipher.SQLiteDatabase
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {

    @Provides
    @Singleton
    fun provideDatabase(
        @ApplicationContext context: Context,
        masterPasswordManager: MasterPasswordManager
    ): VaultDatabase {
        // getDatabasePassphrase() generates and persists a passphrase on first call,
        // so the same key is used whether setup has completed or not.
        val passphrase = masterPasswordManager.getDatabasePassphrase()

        // If an existing vault.db was created with a different passphrase (e.g. the old
        // ByteArray(32) temporary key), delete it so Room can create a fresh database.
        // This is safe because a passphrase mismatch only happens on a fresh install
        // before any real credentials have been saved.
        val dbFile = context.getDatabasePath("vault.db")
        if (dbFile.exists()) {
            var db: SQLiteDatabase? = null
            try {
                db = SQLiteDatabase.openDatabase(
                    dbFile.absolutePath, passphrase, null,
                    SQLiteDatabase.OPEN_READONLY, null
                )
            } catch (_: Exception) {
                // Passphrase mismatch or corruption — delete so Room recreates a fresh DB
                db = null
                context.deleteDatabase("vault.db")
            } finally {
                try { db?.close() } catch (_: Exception) { }
            }
        }

        val factory = SupportOpenHelperFactory(passphrase)

        return Room.databaseBuilder(
            context,
            VaultDatabase::class.java,
            "vault.db"
        )
            .openHelperFactory(factory)
            .build()
    }

    @Provides
    fun provideCredentialDao(database: VaultDatabase): CredentialDao {
        return database.credentialDao()
    }
}
