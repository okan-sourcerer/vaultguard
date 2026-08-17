package com.vaultguard.app.di

import com.vaultguard.app.data.repository.CredentialRepositoryImpl
import com.vaultguard.app.domain.repository.CredentialRepository
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class AppModule {

    @Binds
    @Singleton
    @Suppress("unused")
    abstract fun bindCredentialRepository(
        impl: CredentialRepositoryImpl
    ): CredentialRepository
}
