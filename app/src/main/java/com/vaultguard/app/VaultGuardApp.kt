package com.vaultguard.app

import android.app.Application
import com.vaultguard.app.security.VaultAutoLock
import dagger.hilt.android.HiltAndroidApp
import timber.log.Timber
import javax.inject.Inject

@HiltAndroidApp
class VaultGuardApp : Application() {

    @Inject
    lateinit var vaultAutoLock: VaultAutoLock

    override fun onCreate() {
        super.onCreate()

        // Load SQLCipher native library (required by sqlcipher-android)
        System.loadLibrary("sqlcipher")

        if (BuildConfig.DEBUG) {
            Timber.plant(Timber.DebugTree())
        }

        vaultAutoLock.register()
    }
}
