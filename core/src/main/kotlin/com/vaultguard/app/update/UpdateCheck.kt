package com.vaultguard.app.update

import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * A version as three numbers. `v1.2.3` and `1.2.3` are the same; anything after the third
 * number (`-beta`) is ignored rather than compared, since releases here do not use it.
 */
data class Version(val major: Int, val minor: Int, val patch: Int) : Comparable<Version> {

    override fun compareTo(other: Version): Int =
        compareValuesBy(this, other, { it.major }, { it.minor }, { it.patch })

    override fun toString(): String = "$major.$minor.$patch"

    companion object {
        private val PATTERN = Regex("""^v?(\d+)(?:\.(\d+))?(?:\.(\d+))?""")

        fun parse(text: String): Version? {
            val match = PATTERN.find(text.trim()) ?: return null
            return Version(
                match.groupValues[1].toInt(),
                match.groupValues[2].toIntOrNull() ?: 0,
                match.groupValues[3].toIntOrNull() ?: 0
            )
        }
    }
}

/** What a release on GitHub says about itself, reduced to what an update needs. */
data class Release(
    val version: Version,
    val tag: String,
    /** The release page: where a person goes to read notes or fetch by hand. */
    val pageUrl: String,
    /** Asset file name -> download URL. */
    val assets: Map<String, String>
) {
    companion object {
        /** Parses the JSON of `GET /repos/{owner}/{repo}/releases/latest`. */
        fun parse(json: JSONObject): Release? {
            val tag = json.optString("tag_name").takeIf { it.isNotEmpty() } ?: return null
            val version = Version.parse(tag) ?: return null
            val assets = mutableMapOf<String, String>()
            json.optJSONArray("assets")?.let { array ->
                for (i in 0 until array.length()) {
                    val asset = array.optJSONObject(i) ?: continue
                    val name = asset.optString("name")
                    val url = asset.optString("browser_download_url")
                    if (name.isNotEmpty() && url.isNotEmpty()) assets[name] = url
                }
            }
            return Release(version, tag, json.optString("html_url"), assets)
        }
    }
}

/**
 * The `SHA256SUMS` file the release workflow attaches: one `<hex>  <name>` per line, as
 * `sha256sum` writes it. What lets a client check a download before running it.
 */
object Sha256Sums {

    fun parse(text: String): Map<String, String> = text.lineSequence()
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .mapNotNull { line ->
            val parts = line.split(Regex("\\s+"), limit = 2)
            if (parts.size == 2 && parts[0].length == 64) parts[1].removePrefix("*") to parts[0].lowercase() else null
        }
        .toMap()

    fun sha256Hex(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    fun matches(file: File, expectedHex: String): Boolean =
        MessageDigest.isEqual(sha256Hex(file).toByteArray(), expectedHex.lowercase().toByteArray())
}

/**
 * Asks GitHub for the latest release and compares it with the running version.
 *
 * One unauthenticated GET to the public releases API; no key, no identifying payload,
 * sixty an hour allowed and one a day needed. Blocking; the caller picks the thread.
 * Checking is all this does. Installing is a separate, visible step that the user
 * takes - a password manager does not replace itself in the background.
 */
class UpdateCheck(
    private val currentVersion: String,
    private val repository: String = DEFAULT_REPOSITORY,
    private val apiBase: String = "https://api.github.com",
    private val timeoutMillis: Int = 10_000
) {
    sealed class Result {
        data class UpToDate(val current: Version) : Result()
        data class Available(val current: Version, val release: Release) : Result()
        data class Failed(val reason: String) : Result()
    }

    fun check(): Result {
        val current = Version.parse(currentVersion)
            ?: return Result.Failed("This build has no version to compare (\"$currentVersion\").")

        val json = try {
            fetch("$apiBase/repos/$repository/releases/latest")
        } catch (e: IOException) {
            return Result.Failed(e.message ?: e.javaClass.simpleName)
        }
        val release = runCatching { Release.parse(JSONObject(json)) }.getOrNull()
            ?: return Result.Failed("GitHub answered with something that is not a release.")

        return if (release.version > current) Result.Available(current, release) else Result.UpToDate(current)
    }

    private fun fetch(url: String): String {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = timeoutMillis
            readTimeout = timeoutMillis
            setRequestProperty("Accept", "application/vnd.github+json")
            setRequestProperty("User-Agent", "VaultGuard/$currentVersion")
        }
        try {
            val status = connection.responseCode
            if (status != 200) throw IOException("GitHub answered HTTP $status.")
            return connection.inputStream.bufferedReader().use { it.readText() }
        } finally {
            connection.disconnect()
        }
    }

    companion object {
        const val DEFAULT_REPOSITORY = "okan-sourcerer/vaultguard"
        const val SUMS_FILE = "SHA256SUMS"
    }
}
