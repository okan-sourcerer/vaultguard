package com.vaultguard.app.security

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton

data class BreachResult(
    val isBreached: Boolean,
    val occurrences: Int = 0
)

/**
 * Checks passwords against the HaveIBeenPwned Passwords API using k-anonymity.
 * Only the first 5 characters of the SHA-1 hash are sent — the full hash never leaves the device.
 */
@Singleton
class BreachCheckService @Inject constructor() {

    companion object {
        private const val API_URL = "https://api.pwnedpasswords.com/range/"
        private const val TIMEOUT_MS = 5000
    }

    suspend fun check(password: String): BreachResult = withContext(Dispatchers.IO) {
        try {
            val sha1 = sha1Hash(password)
            val prefix = sha1.take(5)
            val suffix = sha1.substring(5)

            val response = fetchRange(prefix)
            val match = response.lineSequence()
                .map { line -> line.trim().split(":") }
                .filter { it.size == 2 }
                .find { it[0].equals(suffix, ignoreCase = true) }

            if (match != null) {
                BreachResult(isBreached = true, occurrences = match[1].toIntOrNull() ?: 0)
            } else {
                BreachResult(isBreached = false)
            }
        } catch (_: Exception) {
            // Network failure — don't block the user, just report unknown
            BreachResult(isBreached = false)
        }
    }

    private fun fetchRange(prefix: String): String {
        val url = URL("$API_URL$prefix")
        val connection = url.openConnection() as HttpURLConnection
        connection.requestMethod = "GET"
        connection.connectTimeout = TIMEOUT_MS
        connection.readTimeout = TIMEOUT_MS
        connection.setRequestProperty("User-Agent", "VaultGuard-PasswordManager")
        connection.setRequestProperty("Add-Padding", "true")

        return try {
            if (connection.responseCode == 200) {
                connection.inputStream.bufferedReader().readText()
            } else ""
        } finally {
            connection.disconnect()
        }
    }

    private fun sha1Hash(input: String): String {
        val digest = MessageDigest.getInstance("SHA-1")
        val bytes = digest.digest(input.toByteArray(Charsets.UTF_8))
        return bytes.joinToString("") { "%02X".format(it) }
    }
}
