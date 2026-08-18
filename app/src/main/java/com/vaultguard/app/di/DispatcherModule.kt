package com.vaultguard.app.di

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers

@Module
@InstallIn(SingletonComponent::class)
object DispatcherModule {

    /** CPU- and memory-bound work: key derivation and bulk re-encryption. */
    @Provides
    @CryptoDispatcher
    fun provideCryptoDispatcher(): CoroutineDispatcher = Dispatchers.Default
}
