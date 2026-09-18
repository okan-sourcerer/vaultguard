package com.vaultguard.desktop.service

import com.vaultguard.app.update.Release
import com.vaultguard.app.update.Sha256Sums
import com.vaultguard.app.update.UpdateCheck
import com.vaultguard.desktop.cloud.BakedDefaults
import java.awt.Desktop
import java.io.File
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.util.Properties

/**
 * Checking for, and on Windows installing, a newer release.
 *
 * The check is one GET to GitHub's public releases API (see [UpdateCheck]). It runs at
 * most once a day at startup, if the user has not turned it off, and on demand from
 * Settings. Nothing is ever installed without the user pressing Install.
 *
 * **Installing** is platform-shaped. On Windows the `.msi` is downloaded, checked against
 * the release's `SHA256SUMS`, and handed to `msiexec`; the fixed upgrade UUID makes it a
 * replacement in place, and the service quits first because an MSI cannot replace a
 * running executable. On macOS a `.dmg` is something the user drags; on Linux a package
 * belongs to the package manager. Both get the release page in the browser.
 *
 * The hash check is the reason this may run an installer at all. TLS to GitHub is the
 * first line; the sums file, fetched separately from the same release, is the second. A
 * download that does not match is deleted and named, never run.
 */
class Updater(
    private val stateDirectory: File = Setup.stateDirectory,
    private val currentVersion: String = BakedDefaults.version
) {
    private val settingsFile = File(stateDirectory, "settings.properties")

    var checkAtStartup: Boolean
        get() = load().getProperty("checkUpdates", "true").toBoolean()
        set(value) = save { it.setProperty("checkUpdates", value.toString()) }

    private var lastCheck: Long
        get() = load().getProperty("lastUpdateCheck", "0").toLongOrNull() ?: 0L
        set(value) = save { it.setProperty("lastUpdateCheck", value.toString()) }

    /** The asset for this platform, or null where installing is the package manager's job. */
    val installableAsset: String? = when {
        System.getProperty("os.name").orEmpty().lowercase().contains("win") -> "VaultGuard-windows.msi"
        else -> null
    }

    fun check(): UpdateCheck.Result {
        lastCheck = System.currentTimeMillis()
        return UpdateCheck(currentVersion).check()
    }

    /** The startup check: at most once a day, and only if wanted. Null means not checked. */
    fun checkIfDue(): UpdateCheck.Result? {
        if (!checkAtStartup) return null
        if (System.currentTimeMillis() - lastCheck < ONE_DAY_MILLIS) return null
        return check()
    }

    fun openReleasePage(release: Release) {
        runCatching { Desktop.getDesktop().browse(URI(release.pageUrl)) }
    }

    sealed class Install {
        data class Ready(val installer: File, val command: List<String>) : Install()
        data class Refused(val reason: String) : Install()
    }

    /**
     * Downloads the installer and its checksum, verifies, and returns the command to run.
     * The caller quits the service and then runs it: the order matters, since the MSI
     * needs the executable it is replacing to be closed.
     */
    fun prepareInstall(release: Release, progress: (String) -> Unit = {}): Install {
        val asset = installableAsset ?: return Install.Refused("Installing from here is only wired up on Windows.")
        val assetUrl = release.assets[asset] ?: return Install.Refused("This release has no $asset.")
        val sumsUrl = release.assets[UpdateCheck.SUMS_FILE]
            ?: return Install.Refused("This release has no ${UpdateCheck.SUMS_FILE}, so the download cannot be verified.")

        val directory = File(stateDirectory, "updates").apply { mkdirs() }
        val installer = File(directory, asset)
        return try {
            progress("Fetching checksums...")
            val sums = Sha256Sums.parse(fetchText(sumsUrl))
            val expected = sums[asset] ?: return Install.Refused("${UpdateCheck.SUMS_FILE} does not list $asset.")

            progress("Downloading ${release.version}...")
            download(assetUrl, installer)

            progress("Verifying...")
            if (!Sha256Sums.matches(installer, expected)) {
                installer.delete()
                return Install.Refused("The download did not match its published checksum and was deleted.")
            }
            Install.Ready(installer, listOf("msiexec", "/i", installer.absolutePath))
        } catch (e: Exception) {
            installer.delete()
            Install.Refused("Could not download the update: ${e.message ?: e.javaClass.simpleName}")
        }
    }

    private fun fetchText(url: String): String = open(url).use { it.bufferedReader().readText() }

    private fun download(url: String, target: File) {
        open(url).use { input -> target.outputStream().use { input.copyTo(it) } }
    }

    private fun open(url: String): java.io.InputStream {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 60_000
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", "VaultGuard/$currentVersion")
        }
        if (connection.responseCode != 200) throw java.io.IOException("HTTP ${connection.responseCode} for $url")
        return connection.inputStream
    }

    private fun load(): Properties = Properties().also { properties ->
        if (settingsFile.isFile) runCatching { settingsFile.inputStream().use { properties.load(it) } }
    }

    private fun save(edit: (Properties) -> Unit) {
        val properties = load()
        edit(properties)
        settingsFile.parentFile?.mkdirs()
        runCatching { settingsFile.outputStream().use { properties.store(it, null) } }
    }

    companion object {
        private const val ONE_DAY_MILLIS = 24L * 60 * 60 * 1000
    }
}
