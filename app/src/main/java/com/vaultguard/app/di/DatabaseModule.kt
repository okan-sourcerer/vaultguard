package com.vaultguard.app.di

import android.content.Context
import androidx.room.Room
import com.vaultguard.app.data.local.db.VaultDatabase
import com.vaultguard.app.data.local.db.VaultDatabaseHealthCheck
import com.vaultguard.app.data.local.db.VaultMigrations
import com.vaultguard.app.data.local.db.dao.CredentialDao
import com.vaultguard.app.security.MasterPasswordManager
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {

    @Provides
    @Singleton
    fun provideDatabase(
        @ApplicationContext context: Context,
        masterPasswordManager: MasterPasswordManager,
        healthCheck: VaultDatabaseHealthCheck
    ): VaultDatabase {
        // Probe before handing out a database handle, so an unopenable vault becomes a
        // recovery screen instead of an exception surfacing later inside a Room Flow.
        //
        // Idempotent — VaultGuardApp already ran this at startup.
        //
        // This used to delete vault.db whenever the probe threw, on the reasoning that a
        // passphrase mismatch could only happen on a fresh install before any credential
        // had been saved (finding #1). That reasoning was wrong. The mismatch's most
        // likely real cause is a restored or migrated device, where vault_secure_prefs is
        // unreadable because its Keystore master key did not come along — the vault itself
        // is intact and the passphrase is simply gone. Deleting is the one response that
        // turns a recoverable situation into permanent loss.
        //
        // Nothing here modifies the filesystem. Moving a database aside happens only via
        // VaultDatabaseHealthCheck.quarantine(), from a confirmed user action, and renames
        // rather than deletes.
        healthCheck.runOnce()

        val factory = SupportOpenHelperFactory(masterPasswordManager.getDatabasePassphrase())

        return Room.databaseBuilder(
            context,
            VaultDatabase::class.java,
            VaultDatabaseHealthCheck.DATABASE_NAME
        )
            .openHelperFactory(factory)
            .addMigrations(*VaultMigrations.ALL)
            // No destructive migration fallback: a failed migration must fail loudly
            // rather than silently recreating an empty vault.
            .build()
    }

    @Provides
    fun provideCredentialDao(database: VaultDatabase): CredentialDao {
        return database.credentialDao()
    }
}
