package com.vaultguard.app.security

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton

/**
 * What a breach check can tell you.
 *
 * [Unavailable] is the point. The old type had only a boolean, so a network failure was
 * reported as `isBreached = false` and the screen showed a green "Not found in any
 * breaches" — the check having never happened (finding #14). The catch block's own comment
 * said "report unknown"; there was nothing to report it with.
 *
 * False reassurance is the worst answer a security feature can give. It is strictly worse
 * than no feature, because it invites the user to stop worrying about a password nobody
 * looked at.
 */
sealed interface BreachCheckResult {
    /** Found in known breaches [occurrences] times. */
    data class Breached(val occurrences: Int) : BreachCheckResult

    /** The service answered, and this password is not in it. */
    data object Safe : BreachCheckResult

    /** The check did not complete. Says nothing about the password either way. */
    data class Unavailable(val reason: String) : BreachCheckResult
}

/**
 * Fetches one k-anonymity range from Have I Been Pwned.
 *
 * An interface so the parsing and error mapping around it can be tested without a network.
 */
fun interface PwnedRangeSource {
    /**
     * @param prefix the first five hex characters of the SHA-1 hash.
     * @return the raw `SUFFIX:COUNT` body.
     * @throws IOException if the range could not be retrieved.
     */
    suspend fun fetch(prefix: String): String
}

/**
 * Checks a password against Have I Been Pwned using k-anonymity.
 *
 * Only the first five characters of the SHA-1 hash leave the device — roughly one in a
 * million of the corpus. The full hash, and the password, never do.
 */
@Singleton
class BreachCheckService @Inject constructor(
    private val rangeSource: PwnedRangeSource
) {
    suspend fun check(password: String): BreachCheckResult {
        if (password.isEmpty()) return BreachCheckResult.Safe

        val hash = sha1Hex(password)
        val prefix = hash.take(5)
        val suffix = hash.substring(5)

        val body = try {
            rangeSource.fetch(prefix)
        } catch (e: IOException) {
            Timber.w(e, "Breach check could not reach the service")
            return BreachCheckResult.Unavailable(
                e.message ?: "Could not reach the breach database"
            )
        } catch (e: Exception) {
            Timber.w(e, "Breach check failed")
            return BreachCheckResult.Unavailable(e.message ?: "The check could not be completed")
        }

        val occurrences = findOccurrences(body, suffix)
        return if (occurrences > 0) {
            BreachCheckResult.Breached(occurrences)
        } else {
            BreachCheckResult.Safe
        }
    }

    /**
     * @return how many times [suffix] appears in the range, or 0 if it does not.
     *
     * Entries with a count of zero are **padding**. Requesting `Add-Padding` makes the
     * service mix in several hundred invented suffixes so the response size reveals
     * nothing about how many real matches there were; they are marked by a zero count.
     * Treating one as a hit would report a password as breached zero times.
     */
    internal fun findOccurrences(body: String, suffix: String): Int =
        body.lineSequence()
            .mapNotNull { line ->
                val parts = line.trim().split(':')
                if (parts.size != 2) return@mapNotNull null
                val count = parts[1].trim().toIntOrNull() ?: return@mapNotNull null
                parts[0] to count
            }
            .firstOrNull { (candidate, count) -> count > 0 && candidate.equals(suffix, ignoreCase = true) }
            ?.second
            ?: 0

    private fun sha1Hex(input: String): String =
        MessageDigest.getInstance("SHA-1")
            .digest(input.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02X".format(it) }
}

/** The real range source. */
@Singleton
class HttpPwnedRangeSource @Inject constructor() : PwnedRangeSource {

    companion object {
        private const val API_URL = "https://api.pwnedpasswords.com/range/"
        private const val TIMEOUT_MS = 8000
    }

    override suspend fun fetch(prefix: String): String = withContext(Dispatchers.IO) {
        val connection = (URL("$API_URL$prefix").openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = TIMEOUT_MS
            readTimeout = TIMEOUT_MS
            setRequestProperty("User-Agent", "VaultGuard-PasswordManager")
            // Pads the response with decoy entries so its size does not hint at how many
            // real matches the range holds. BreachCheckService discards them by count.
            setRequestProperty("Add-Padding", "true")
        }

        try {
            val status = connection.responseCode
            // A non-200 used to be turned into an empty body, which read as "no match" and
            // produced the same false all-clear as a network failure (finding #14).
            if (status != HttpURLConnection.HTTP_OK) {
                throw IOException("The breach database returned HTTP $status")
            }
            connection.inputStream.bufferedReader().use { it.readText() }
        } finally {
            connection.disconnect()
        }
    }
}
