package com.vaultguard.app.security

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import androidx.core.content.edit

/**
 * Narrow view of the Keystore-encrypted preference store.
 *
 * Extracted so [MasterPasswordManager] can be tested on the host JVM. It previously built
 * an [EncryptedSharedPreferences] inline on every access, which made the most
 * security-critical class in the app impossible to exercise off-device — and it is the
 * class where an untested mistake orphans the vault.
 */
interface SecurePrefs {
    fun getString(key: String): String?
    fun putAll(values: Map<String, String>)
}

/**
 * The real store.
 *
 * **Every constant here is frozen.** The file name, the key names, and the two encryption
 * schemes together determine whether an existing install can still read its own salt. A
 * change to any of them presents to the user as "wrong master password" on a vault whose
 * password is perfectly correct. See `docs/SECURITY.md`.
 */
class EncryptedSharedPrefs(
    private val context: Context,
    private val fileName: String
) : SecurePrefs {

    // Cached rather than rebuilt per call: constructing EncryptedSharedPreferences does
    // Keystore work, and the previous code paid that cost on every property read.
    private val prefs by lazy {
        EncryptedSharedPreferences.create(
            context,
            fileName,
            MasterKey.Builder(context)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build(),
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }

    override fun getString(key: String): String? = prefs.getString(key, null)

    override fun putAll(values: Map<String, String>) {
        prefs.edit { values.forEach { (key, value) -> putString(key, value) } }
    }
}
