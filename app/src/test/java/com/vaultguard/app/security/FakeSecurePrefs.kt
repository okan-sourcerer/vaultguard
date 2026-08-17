package com.vaultguard.app.security

/** In-memory [SecurePrefs] for host-side tests. */
class FakeSecurePrefs(
    initial: Map<String, String> = emptyMap()
) : SecurePrefs {

    val values = initial.toMutableMap()

    var writeCount = 0
        private set

    override fun getString(key: String): String? = values[key]

    override fun putAll(values: Map<String, String>) {
        writeCount++
        this.values.putAll(values)
    }
}
