package com.vaultguard.app.data.repository

import com.vaultguard.app.data.local.db.dao.CredentialDao
import com.vaultguard.app.data.local.db.entity.CredentialEntity
import com.vaultguard.app.domain.model.Credential
import com.vaultguard.app.domain.model.CredentialSummary
import com.vaultguard.app.domain.model.toSummary
import com.vaultguard.app.domain.repository.CredentialRepository
import com.vaultguard.app.security.CryptoManager
import com.vaultguard.app.security.EncryptedData
import com.vaultguard.app.security.MasterPasswordManager
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class CredentialRepositoryImpl @Inject constructor(
    private val credentialDao: CredentialDao,
    private val cryptoManager: CryptoManager,
    private val masterPasswordManager: MasterPasswordManager
) : CredentialRepository {

    override fun getAllCredentials(): Flow<List<Credential>> {
        return credentialDao.getAllCredentials().map { entities ->
            entities.mapNotNull { decryptEntity(it) }
        }
    }

    override fun getAllSummaries(): Flow<List<CredentialSummary>> {
        return credentialDao.getAllCredentials().map { entities ->
            entities.mapNotNull { decryptEntity(it)?.toSummary() }
        }
    }

    override suspend fun getById(id: String): Credential? {
        val entity = credentialDao.getById(id) ?: return null
        return decryptEntity(entity)
    }

    override suspend fun save(credential: Credential) {
        val now = System.currentTimeMillis()
        val existing = credentialDao.getById(credential.id)
        val json = credentialToJson(credential)
        val key = masterPasswordManager.getSessionKey()
        val plaintextBytes = json.toByteArray(Charsets.UTF_8)
        val encrypted = cryptoManager.encrypt(plaintextBytes, key)
        plaintextBytes.fill(0)

        val entity = CredentialEntity(
            id = credential.id.ifEmpty { UUID.randomUUID().toString() },
            encryptedPayload = encrypted.ciphertext,
            iv = encrypted.iv,
            createdAt = existing?.createdAt ?: now,
            updatedAt = now
        )
        credentialDao.upsert(entity)
    }

    override suspend fun delete(id: String) {
        credentialDao.softDelete(id)
    }

    override suspend fun search(query: String): List<CredentialSummary> {
        val allEntities = credentialDao.getAll()
        val lowerQuery = query.lowercase()
        return allEntities
            .filter { !it.isDeleted }
            .mapNotNull { decryptEntity(it) }
            .filter { credential ->
                credential.siteName.lowercase().contains(lowerQuery) ||
                credential.appName.lowercase().contains(lowerQuery) ||
                credential.username.lowercase().contains(lowerQuery) ||
                credential.category.lowercase().contains(lowerQuery) ||
                credential.tags.any { it.lowercase().contains(lowerQuery) }
            }
            .map { it.toSummary() }
    }

    private fun decryptEntity(entity: CredentialEntity): Credential? {
        return try {
            val key = masterPasswordManager.getSessionKey()
            val decrypted = cryptoManager.decrypt(
                EncryptedData(entity.encryptedPayload, entity.iv),
                key
            )
            val json = String(decrypted, Charsets.UTF_8)
            decrypted.fill(0)
            CredentialPayloadCodec.decode(json, entity.id, entity.createdAt, entity.updatedAt)
        } catch (e: Exception) {
            // TODO(#40): swallowing this makes an unreadable vault look like an empty one.
            //  Chunk 3 replaces it with a surfaced decrypt-failure count.
            null
        }
    }

    private fun credentialToJson(credential: Credential): String =
        CredentialPayloadCodec.encode(credential)
}
