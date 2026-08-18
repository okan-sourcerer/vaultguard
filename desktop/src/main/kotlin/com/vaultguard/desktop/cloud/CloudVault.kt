package com.vaultguard.desktop.cloud

import com.vaultguard.app.data.repository.CredentialPayloadCodec
import com.vaultguard.app.domain.model.Credential
import com.vaultguard.app.domain.repository.VaultSnapshot
import com.vaultguard.app.security.CryptoManager
import com.vaultguard.app.security.EncryptedData
import com.vaultguard.app.security.KeyDerivation
import com.vaultguard.app.security.MasterPasswordManager
import com.vaultguard.app.security.SecurePrefs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import javax.crypto.SecretKey

class WrongMasterPasswordException :
    Exception("Wrong master password for this vault.")

class UnreachableVaultKeyException(message: String) : Exception(message)

/**
 * [SecurePrefs] that keeps everything in memory and is dropped with the process.
 *
 * No vault material is persisted. There is no Keystore here to protect a stored salt or a
 * wrapped key with, so rather than inventing a weaker at-rest story none is written: the
 * configuration is fetched from Firestore each run, the master key exists for the length of
 * an unlock, and the vault key lives only in this process.
 *
 * The one thing that does survive a run is the Firebase refresh token, and it is sealed
 * under the master key — see [SavedSession]. It reaches Firestore; it opens nothing.
 *
 * The consequence worth stating plainly: every run costs one Argon2id derivation, and
 * closing the CLI discards every key.
 */
private class EphemeralPrefs : SecurePrefs {
    private val values = mutableMapOf<String, String>()
    override fun getString(key: String): String? = values[key]
    override fun putAll(values: Map<String, String>) { this.values.putAll(values) }
    override fun remove(keys: Collection<String>) { keys.forEach { values.remove(it) } }
}

/**
 * Opens a vault fetched from Firestore.
 *
 * This does not reimplement the key hierarchy — it feeds the remote configuration into
 * [MasterPasswordManager.adoptRemoteSetup], the entry point that exists for exactly this
 * case, and then follows the same path the phone follows: derive the master key, verify
 * it, unwrap the vault key.
 *
 * `adoptRemoteSetup` was written because adopting a salt *without* the wrapped vault key
 * left a vault whose master password verified and whose contents were unreachable (#4).
 * That is why [unlock] refuses a configuration with no wrapped key instead of proceeding
 * to a vault it could never open.
 */
class CloudVault {

    private val cryptoManager = CryptoManager()
    private val masterPasswordManager = MasterPasswordManager(
        prefs = EphemeralPrefs(),
        cryptoManager = cryptoManager,
        keyDerivation = KeyDerivation(),
        cryptoDispatcher = Dispatchers.Default
    )

    /**
     * Derives the master key from [password] and unwraps the vault key with it.
     *
     * Consumes [password]: [KeyDerivation.deriveKey] zeroes the array it is given.
     *
     * @return the vault key — the `sessionKey` everything above the security layer uses.
     */
    fun unlock(config: RemoteVaultConfig, password: CharArray): SecretKey =
        unlockWith(config, deriveMasterKey(config.salt, password))

    /**
     * Derives the master key against a salt, and nothing else.
     *
     * Separate from [unlockWith] because a saved session has to be opened *before* the
     * vault document can be fetched: the refresh token is what reaches Firestore, and it is
     * sealed under this key. Splitting the two means one Argon2id run serves both, rather
     * than paying 64 MiB twice per launch.
     *
     * Consumes [password].
     */
    fun deriveMasterKey(salt: ByteArray, password: CharArray): SecretKey =
        runBlocking { masterPasswordManager.deriveKey(password, salt) }

    /** Verifies [masterKey] against the remote configuration and unwraps the vault key. */
    fun unlockWith(config: RemoteVaultConfig, masterKey: SecretKey): SecretKey {
        if (!config.hasWrappedVaultKey) {
            throw UnreachableVaultKeyException(
                "This cloud vault predates the wrapped vault key, so its rows cannot be " +
                    "opened from another device. Sync once from the phone to publish it."
            )
        }

        masterPasswordManager.adoptRemoteSetup(
            salt = config.salt,
            verificationCiphertext = config.verificationCiphertext,
            verificationIv = config.verificationIv,
            wrappedVaultKey = EncryptedData(config.vaultKeyCiphertext!!, config.vaultKeyIv!!)
        )

        if (!masterPasswordManager.verifyMasterKey(masterKey)) throw WrongMasterPasswordException()

        // Verified above, so a failure here is a damaged or mismatched wrapped key rather
        // than a wrong password, and saying so is the difference between a user retrying
        // their password forever and understanding what is actually wrong.
        val vaultKey = masterPasswordManager.unwrapVaultKey(masterKey)
            ?: throw UnreachableVaultKeyException(
                "The master password is correct, but the stored vault key did not unwrap. " +
                    "The cloud copy may be damaged."
            )

        masterPasswordManager.adoptVaultKey(vaultKey)
        return vaultKey
    }

    /**
     * Decrypts what was fetched, carrying the failures rather than dropping them.
     *
     * Tombstoned rows are filtered out before decryption — they are deletions the phone has
     * not yet purged, not entries. Anything else that will not open is reported through
     * [VaultSnapshot.undecryptableIds]: a row that fails here is exactly the situation
     * finding #40 describes, where `mapNotNull` over a swallowed exception made four
     * separate data-loss bugs all look like an empty vault.
     */
    fun decrypt(rows: List<RemoteCredentialRow>, vaultKey: SecretKey): VaultSnapshot<Credential> {
        val credentials = mutableListOf<Credential>()
        val failed = mutableListOf<String>()

        for (row in rows.filterNot { it.isDeleted }) {
            try {
                val plaintext = cryptoManager.decrypt(
                    EncryptedData(row.encryptedPayload, row.iv), vaultKey
                )
                val payload = String(plaintext, Charsets.UTF_8)
                plaintext.fill(0)

                credentials += CredentialPayloadCodec.decode(
                    json = payload,
                    id = row.id,
                    createdAt = row.createdAt,
                    updatedAt = row.updatedAt,
                    passwordChangedAt = row.passwordChangedAt
                )
            } catch (e: Exception) {
                failed += row.id
            }
        }

        return VaultSnapshot(
            items = credentials.sortedBy { it.displayName.lowercase() },
            undecryptableIds = failed
        )
    }

    /**
     * Seals a credential under the vault key, ready to be written.
     *
     * A fresh IV per encryption, as everywhere else — the frozen AES-256-GCM arrangement in
     * `docs/SECURITY.md`. Reusing an IV under one key is the failure that breaks GCM
     * outright, and nothing about a new row makes it acceptable here.
     *
     * The three row clocks all take the same instant on a new entry: it was created,
     * written and given its password at once. `contentChangedAt` travels inside the
     * payload, so [CredentialPayloadCodec] carries it (#58).
     */
    fun encrypt(credential: Credential, vaultKey: SecretKey): RemoteCredentialRow {
        val plaintext = CredentialPayloadCodec.encode(credential).toByteArray(Charsets.UTF_8)
        val sealed = cryptoManager.encrypt(plaintext, vaultKey)
        plaintext.fill(0)

        return RemoteCredentialRow(
            id = credential.id,
            encryptedPayload = sealed.ciphertext,
            iv = sealed.iv,
            createdAt = credential.createdAt,
            updatedAt = credential.updatedAt,
            passwordChangedAt = credential.passwordChangedAt,
            isDeleted = false
        )
    }
}
