package com.vaultguard.app.data.remote

import android.content.Context
import android.util.Base64
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.vaultguard.app.data.local.db.dao.CredentialDao
import com.vaultguard.app.data.local.db.entity.CredentialEntity
import com.vaultguard.app.security.MasterPasswordManager
import kotlinx.coroutines.tasks.await
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class FirebaseSyncService @Inject constructor(
    @dagger.hilt.android.qualifiers.ApplicationContext private val context: Context,
    private val firestore: FirebaseFirestore,
    private val auth: FirebaseAuth,
    private val credentialDao: CredentialDao,
    private val masterPasswordManager: MasterPasswordManager
) {

    companion object {
        private const val COLLECTION_VAULTS = "vaults"
        private const val COLLECTION_CREDENTIALS = "credentials"
        private const val DOC_CONFIG = "config"
        private const val FIELD_SALT = "salt"
        private const val FIELD_VERIFICATION_CIPHERTEXT = "verificationCiphertext"
        private const val FIELD_VERIFICATION_IV = "verificationIv"
        private const val FIELD_ENCRYPTED_PAYLOAD = "encryptedPayload"
        private const val FIELD_IV = "iv"
        private const val FIELD_CREATED_AT = "createdAt"
        private const val FIELD_UPDATED_AT = "updatedAt"
        private const val FIELD_IS_DELETED = "isDeleted"
    }

    data class RemoteVaultConfig(
        val salt: ByteArray,
        val verificationCiphertext: ByteArray,
        val verificationIv: ByteArray
    )

    private fun getUserId(): String {
        return auth.currentUser?.uid ?: throw IllegalStateException("User not authenticated")
    }

    private fun vaultDocument() =
        firestore.collection(COLLECTION_VAULTS).document(getUserId())

    private fun credentialsCollection() =
        vaultDocument().collection(COLLECTION_CREDENTIALS)

    /**
     * Uploads the vault config (salt + verification data) to Firestore.
     * The salt alone is useless without the master password — safe to store remotely.
     */
    /**
     * Writes vault config (salt + verification data) directly onto the vault document.
     * Path: vaults/{uid}  — same document the security rules already protect.
     */
    suspend fun pushVaultConfig(salt: ByteArray, verificationCiphertext: ByteArray, verificationIv: ByteArray) {
        ensureAuthenticated()
        val data = mapOf(
            FIELD_SALT to Base64.encodeToString(salt, Base64.NO_WRAP),
            FIELD_VERIFICATION_CIPHERTEXT to Base64.encodeToString(verificationCiphertext, Base64.NO_WRAP),
            FIELD_VERIFICATION_IV to Base64.encodeToString(verificationIv, Base64.NO_WRAP)
        )
        vaultDocument().set(data, com.google.firebase.firestore.SetOptions.merge()).await()
    }

    /**
     * Reads vault config from the vault document.
     * Returns null if no config has been pushed yet (first device).
     */
    suspend fun pullVaultConfig(): RemoteVaultConfig? {
        ensureAuthenticated()
        val doc = vaultDocument().get().await()
        val saltStr = doc.getString(FIELD_SALT) ?: return null
        val ciphertextStr = doc.getString(FIELD_VERIFICATION_CIPHERTEXT) ?: return null
        val ivStr = doc.getString(FIELD_VERIFICATION_IV) ?: return null
        return RemoteVaultConfig(
            salt = Base64.decode(saltStr, Base64.NO_WRAP),
            verificationCiphertext = Base64.decode(ciphertextStr, Base64.NO_WRAP),
            verificationIv = Base64.decode(ivStr, Base64.NO_WRAP)
        )
    }

    suspend fun pushChanges(since: Long) {
        val modified = credentialDao.getModifiedSince(since)
        if (modified.isEmpty()) return

        val batch = firestore.batch()
        for (entity in modified) {
            val docRef = credentialsCollection().document(entity.id)
            batch.set(docRef, entityToMap(entity))
        }
        batch.commit().await()

        val now = System.currentTimeMillis()
        for (entity in modified) {
            credentialDao.upsert(entity.copy(syncedAt = now))
        }
    }

    suspend fun pullChanges(since: Long): List<CredentialEntity> {
        val snapshot = credentialsCollection()
            .whereGreaterThan(FIELD_UPDATED_AT, since)
            .get()
            .await()

        val remoteEntities = snapshot.documents.mapNotNull { doc ->
            mapToEntity(doc.id, doc.data ?: return@mapNotNull null)
        }

        for (remote in remoteEntities) {
            val local = credentialDao.getById(remote.id)
            if (local == null || remote.updatedAt > local.updatedAt ||
                (remote.updatedAt == local.updatedAt && local.syncedAt != null)
            ) {
                credentialDao.upsert(remote.copy(syncedAt = System.currentTimeMillis()))
            }
        }

        return remoteEntities
    }

    suspend fun fullSync() {
        ensureAuthenticated()

        // Ensure vault config is on Firestore (idempotent — only writes if missing)
        try {
            val remoteConfig = pullVaultConfig()
            if (remoteConfig == null && masterPasswordManager.isSetupComplete) {
                val (ciphertext, iv) = masterPasswordManager.getVerificationData()
                pushVaultConfig(masterPasswordManager.getSalt(), ciphertext, iv)
            }
        } catch (_: Exception) { }

        val lastSync = getLastSyncTime()

        pushChanges(lastSync)
        pullChanges(lastSync)

        saveLastSyncTime(System.currentTimeMillis())
    }

    fun isAuthenticated(): Boolean = auth.currentUser != null

    suspend fun ensureAuthenticated() {
        if (auth.currentUser == null) {
            auth.signInAnonymously().await()
        }
    }

    private fun entityToMap(entity: CredentialEntity): Map<String, Any?> {
        return mapOf(
            FIELD_ENCRYPTED_PAYLOAD to Base64.encodeToString(entity.encryptedPayload, Base64.NO_WRAP),
            FIELD_IV to Base64.encodeToString(entity.iv, Base64.NO_WRAP),
            FIELD_CREATED_AT to entity.createdAt,
            FIELD_UPDATED_AT to entity.updatedAt,
            FIELD_IS_DELETED to entity.isDeleted
        )
    }

    private fun mapToEntity(id: String, data: Map<String, Any>): CredentialEntity? {
        return try {
            CredentialEntity(
                id = id,
                encryptedPayload = Base64.decode(data[FIELD_ENCRYPTED_PAYLOAD] as String, Base64.NO_WRAP),
                iv = Base64.decode(data[FIELD_IV] as String, Base64.NO_WRAP),
                createdAt = (data[FIELD_CREATED_AT] as Number).toLong(),
                updatedAt = (data[FIELD_UPDATED_AT] as Number).toLong(),
                isDeleted = data[FIELD_IS_DELETED] as? Boolean ?: false
            )
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Migrates all credentials and config from an old anonymous UID to the current user's vault.
     * Called when anonymous account linking fails and we sign in with a separate Google account.
     */
    suspend fun migrateFromAnonymousUser(oldAnonymousUid: String) {
        ensureAuthenticated()
        val newUid = getUserId()
        if (oldAnonymousUid == newUid) return // same user, nothing to migrate

        val oldCredentials = firestore.collection(COLLECTION_VAULTS)
            .document(oldAnonymousUid)
            .collection(COLLECTION_CREDENTIALS)
            .get()
            .await()

        if (oldCredentials.isEmpty) return

        val batch = firestore.batch()
        for (doc in oldCredentials.documents) {
            val data = doc.data ?: continue
            val newDocRef = credentialsCollection().document(doc.id)
            batch.set(newDocRef, data)
        }
        batch.commit().await()

        // Only migrate config if the destination vault has none yet.
        // If a config already exists (another device's vault), preserve it — it is canonical.
        try {
            val destDoc = vaultDocument().get().await()
            if (destDoc.getString(FIELD_SALT) == null) {
                val oldDoc = firestore.collection(COLLECTION_VAULTS)
                    .document(oldAnonymousUid).get().await()
                val salt = oldDoc.getString(FIELD_SALT)
                val ciphertext = oldDoc.getString(FIELD_VERIFICATION_CIPHERTEXT)
                val iv = oldDoc.getString(FIELD_VERIFICATION_IV)
                if (salt != null && ciphertext != null && iv != null) {
                    vaultDocument().set(
                        mapOf(
                            FIELD_SALT to salt,
                            FIELD_VERIFICATION_CIPHERTEXT to ciphertext,
                            FIELD_VERIFICATION_IV to iv
                        ),
                        com.google.firebase.firestore.SetOptions.merge()
                    ).await()
                }
            }
        } catch (_: Exception) { }
    }

    private val prefs by lazy {
        context.getSharedPreferences("sync_prefs", android.content.Context.MODE_PRIVATE)
    }

    private fun getLastSyncTime(): Long = prefs.getLong("last_sync_time_${getUserIdOrNull()}", 0L)

    private fun saveLastSyncTime(time: Long) {
        prefs.edit().putLong("last_sync_time_${getUserIdOrNull()}", time).apply()
    }

    private fun getUserIdOrNull(): String? = auth.currentUser?.uid
}
