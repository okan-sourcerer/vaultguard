package com.vaultguard.app

import android.app.Application
import com.vaultguard.app.data.local.db.VaultDatabaseHealthCheck
import com.vaultguard.app.security.VaultAutoLock
import dagger.hilt.android.HiltAndroidApp
import timber.log.Timber
import javax.inject.Inject

@HiltAndroidApp
class VaultGuardApp : Application() {

    @Inject
    lateinit var vaultAutoLock: VaultAutoLock

    @Inject
    lateinit var vaultDatabaseHealthCheck: VaultDatabaseHealthCheck

    override fun onCreate() {
        super.onCreate()

        // Load SQLCipher native library (required by sqlcipher-android)
        System.loadLibrary("sqlcipher")

        if (BuildConfig.DEBUG) {
            Timber.plant(Timber.DebugTree())
        }

        // Establish whether the vault is readable before any screen composes, so an
        // unopenable database routes to the recovery screen instead of rendering as an
        // empty vault. Inspects only — never modifies the file (finding #1).
        vaultDatabaseHealthCheck.runOnce()

        vaultAutoLock.register()
    }
}
