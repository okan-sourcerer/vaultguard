package com.vaultguard.desktop.cloud

import javax.crypto.SecretKey

/** An unlocked cloud vault: everything a caller needs to read or write it. */
class OpenVault(
    val client: FirestoreClient,
    val vault: CloudVault,
    val vaultKey: SecretKey,
    val email: String?
)

/**
 * Signing in and unlocking, in one place.
 *
 * Extracted because there are now two front ends — the CLI and the tray service — and the
 * ordering here is subtle enough that two copies would drift. The subtlety: the refresh
 * token is what reaches Firestore, Firestore is what supplies the salt, and the token is
 * sealed under the key that salt derives. So the salt is read from the local file first,
 * one derivation serves both, and the result is checked against the vault document
 * afterwards.
 *
 * Presentation is injected rather than assumed, so the same flow drives a console prompt
 * and a Swing dialog.
 */
object CloudConnect {

    /**
     * @param askPassword returns null if the user declines; the array is consumed.
     * @return null if the user cannot get in, having already been told why.
     */
    fun open(
        config: DesktopConfig,
        askPassword: (String) -> CharArray?,
        say: (String) -> Unit,
        warn: (String) -> Unit
    ): OpenVault? {
        val vault = CloudVault()

        var masterKey: SecretKey? = null
        var derivedAgainst: ByteArray? = null
        var session: FirebaseSession? = null

        val savedSalt = SavedSession.saltOf()
        if (savedSalt != null) {
            val password = askPassword("Master password") ?: return null

            say("Deriving key (Argon2id, 64 MiB)...")
            val key = vault.deriveMasterKey(savedSalt, password)

            val saved = SavedSession.open(key)
            if (saved == null) {
                // A wrong password and a file left from a previous one look identical here,
                // and signing in again fixes both. Neither is worth failing on.
                say("The saved sign-in did not open with that password.")
            } else {
                try {
                    session = TokenRefresh(config).exchange(saved.refreshToken, saved.email)
                    masterKey = key
                    derivedAgainst = savedSalt
                } catch (e: RefreshRejectedException) {
                    say("${e.message} Signing in again.")
                    SavedSession.clear()
                }
            }
        }

        if (session == null) {
            session = try {
                FirebaseSignIn(config).exchange(GoogleOAuth(config).signIn(say))
            } catch (e: Exception) {
                warn("Sign-in failed: ${e.message}")
                return null
            }
        }
        say("Signed in as ${session.email ?: session.uid}.")

        val client = FirestoreClient(config, session)

        val document = try {
            client.vaultDocument()
        } catch (e: Exception) {
            warn(e.message ?: "Could not read the vault document.")
            return null
        }

        if (document == null) {
            // Creating a vault from here would be the "uploaded a vault the owner never
            // asked for" behaviour of finding #15, and this client has none to upload.
            warn(
                "This account has no cloud vault. Turn on sync in the app's Settings " +
                    "first; the phone publishes the vault."
            )
            return null
        }

        val remoteConfig = RemoteVaultCodec.readVaultConfig(document)
        if (remoteConfig == null) {
            warn("The cloud vault document is incomplete - it carries no usable configuration.")
            return null
        }

        // Changing the master password on the phone re-salts the vault, so a key derived
        // against the saved salt belongs to the old password. Discarded rather than tried.
        if (masterKey != null && derivedAgainst?.contentEquals(remoteConfig.salt) != true) {
            say("The master password has changed since this session was saved.")
            SavedSession.clear()
            masterKey = null
        }

        if (masterKey == null) {
            val password = askPassword("Master password") ?: return null
            say("Deriving key (Argon2id, 64 MiB)...")
            masterKey = vault.deriveMasterKey(remoteConfig.salt, password)
        }

        val vaultKey = try {
            vault.unlockWith(remoteConfig, masterKey)
        } catch (e: Exception) {
            warn(e.message ?: "Could not unlock the vault.")
            return null
        }

        // Only now, with the password proven against the vault. Saving earlier would write
        // a file sealed under a key that opens nothing.
        session.refreshToken?.let { token ->
            runCatching {
                SavedSession.save(SavedSession(token, session.email), masterKey, remoteConfig.salt)
            }.onFailure { say("(Could not save the sign-in for next time: ${it.message})") }
        }

        return OpenVault(client, vault, vaultKey, session.email)
    }
}
