package com.vaultguard.app.di

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import javax.inject.Qualifier

/**
 * Argon2id at 64 MiB blocks for hundreds of milliseconds. Injecting the dispatcher rather
 * than hard-coding it keeps that off the main thread (finding #17) and lets tests assert
 * it stays off.
 */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class CryptoDispatcher

@Module
@InstallIn(SingletonComponent::class)
object DispatcherModule {

    /** CPU- and memory-bound work: key derivation and bulk re-encryption. */
    @Provides
    @CryptoDispatcher
    fun provideCryptoDispatcher(): CoroutineDispatcher = Dispatchers.Default
}
