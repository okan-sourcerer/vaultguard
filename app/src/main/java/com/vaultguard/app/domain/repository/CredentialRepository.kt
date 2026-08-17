package com.vaultguard.app.domain.repository

import com.vaultguard.app.domain.model.Credential
import com.vaultguard.app.domain.model.CredentialSummary
import kotlinx.coroutines.flow.Flow

interface CredentialRepository {

    /**
     * All credentials, together with the ids of any row that could not be decrypted.
     * See [VaultSnapshot] — the failure list is part of the contract, not a detail.
     */
    fun getAllCredentials(): Flow<VaultSnapshot<Credential>>

    fun getAllSummaries(): Flow<VaultSnapshot<CredentialSummary>>

    suspend fun getById(id: String): CredentialLookup

    suspend fun save(credential: Credential)

    suspend fun delete(id: String)

    suspend fun search(query: String): VaultSnapshot<CredentialSummary>
}
