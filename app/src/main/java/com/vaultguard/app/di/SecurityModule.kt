package com.vaultguard.app.di

import android.content.Context
import com.vaultguard.app.security.BiometricAuthManager
import com.vaultguard.app.security.BiometricKeystore
import com.vaultguard.app.security.EncryptedSharedPrefs
import com.vaultguard.app.security.KeystoreManager
import com.vaultguard.app.security.MasterPasswordManager
import com.vaultguard.app.security.SecurePrefs
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Named
import javax.inject.Singleton

/**
 * Keystore-encrypted preference stores.
 *
 * The file names are frozen — they decide whether an existing install can still read its
 * own salt and wrapped key. See `docs/SECURITY.md`.
 */
@Module
@InstallIn(SingletonComponent::class)
object SecurityModule {

    @Provides
    @Singleton
    fun provideBiometricKeystore(keystoreManager: KeystoreManager): BiometricKeystore =
        keystoreManager

    @Provides
    @Singleton
    @Named(MasterPasswordManager.VAULT_PREFS)
    fun provideVaultPrefs(@ApplicationContext context: Context): SecurePrefs =
        EncryptedSharedPrefs(context, MasterPasswordManager.PREFS_NAME)

    @Provides
    @Singleton
    @Named(BiometricAuthManager.BIOMETRIC_PREFS)
    fun provideBiometricPrefs(@ApplicationContext context: Context): SecurePrefs =
        EncryptedSharedPrefs(context, BiometricAuthManager.PREFS_NAME)
}
