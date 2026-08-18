package com.vaultguard.app.security

/**
 * Narrow view of the Keystore-encrypted preference store.
 *
 * Extracted so [MasterPasswordManager] can be tested on the host JVM. It previously built
 * an `EncryptedSharedPreferences` inline on every access, which made the most
 * security-critical class in the app impossible to exercise off-device — and it is the
 * class where an untested mistake orphans the vault.
 *
 * That same abstraction is what lets `MasterPasswordManager` live in `:core`: the Android
 * implementation is `EncryptedSharedPrefs` in `:app`, and a desktop client supplies its
 * own without either side reaching for a platform API.
 */
interface SecurePrefs {
    fun getString(key: String): String?
    fun putAll(values: Map<String, String>)
    fun remove(keys: Collection<String>)
}
