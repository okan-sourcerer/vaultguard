package com.vaultguard.app.domain.repository

import com.vaultguard.app.domain.model.Credential
import com.vaultguard.app.domain.model.CredentialSummary
import kotlinx.coroutines.flow.Flow

interface CredentialRepository {
    fun getAllCredentials(): Flow<List<Credential>>
    /** Returns summaries without passwords — use for list display. */
    fun getAllSummaries(): Flow<List<CredentialSummary>>
    suspend fun getById(id: String): Credential?
    suspend fun save(credential: Credential)
    suspend fun delete(id: String)
    suspend fun search(query: String): List<CredentialSummary>
}
