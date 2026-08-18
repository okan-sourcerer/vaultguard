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

    /**
     * Puts back an entry deleted a moment ago, while the undo offer is still up (#59).
     */
    suspend fun undoDelete(id: String)

    /**
     * Called once the undo offer has gone, to stop a deleted entry lingering as a row the
     * user can neither see nor reach. Only removes it outright when the cloud has never
     * seen it; a pushed row keeps its tombstone until sync has carried the deletion.
     */
    suspend fun finaliseDelete(id: String)

}
