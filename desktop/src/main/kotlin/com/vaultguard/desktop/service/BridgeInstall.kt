package com.vaultguard.desktop.service

import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Registers the native-messaging host with Chrome and Firefox.
 *
 * A browser will only start a host it has been told about, and will only let the extensions
 * named in that host's manifest talk to it. That is the whole access-control story for the
 * bridge: the loopback token proves the caller runs as this user, and this proves *which*
 * extension the browser is speaking for.
 *
 * The Chrome extension id is not knowable in advance for an unpacked extension — Chrome
 * derives it per installation — so it is taken as an argument rather than guessed. Firefox
 * uses the id declared in its own manifest.
 */
object BridgeInstall {

    const val HOST_NAME = "com.vaultguard.bridge"
    const val FIREFOX_EXTENSION_ID = "vaultguard@vaultguard.local"

    private val home: File get() = File(System.getProperty("user.home"))

    private val isWindows: Boolean
        get() = System.getProperty("os.name").orEmpty().lowercase().contains("win")

    /** Where each browser looks, per platform. */
    private fun manifestDirectories(): Map<String, File> {
        val os = System.getProperty("os.name").orEmpty().lowercase()
        return when {
            os.contains("win") -> mapOf(
                // On Windows the path is irrelevant — the registry points at the file — but
                // keeping them apart makes the two manifests obvious.
                "chrome" to File(home, ".vaultguard"),
                "firefox" to File(home, ".vaultguard")
            )

            os.contains("mac") -> mapOf(
                "chrome" to File(home, "Library/Application Support/Google/Chrome/NativeMessagingHosts"),
                "firefox" to File(home, "Library/Application Support/Mozilla/NativeMessagingHosts")
            )

            else -> mapOf(
                "chrome" to File(home, ".config/google-chrome/NativeMessagingHosts"),
                "firefox" to File(home, ".mozilla/native-messaging-hosts")
            )
        }
    }

    /**
     * What an install did, and what is left for the user to do.
     *
     * Separated from the printing so nothing can announce a step that did not happen — the
     * first version printed "run the reg add lines above" unconditionally, including when
     * it had bailed out and printed none.
     */
    data class Report(
        val written: List<String> = emptyList(),
        val registryCommands: List<String> = emptyList(),
        val problems: List<String> = emptyList()
    ) {
        val succeeded: Boolean get() = problems.isEmpty()
    }

    /**
     * Finds the launcher the browser should run, by asking where this code is loaded from.
     *
     * The first version guessed it from the working directory, so running the command from
     * anywhere but the repository root found nothing and reported a missing build. A
     * process knows where it lives; it does not know where it was started from.
     */
    fun locateLauncher(): File? {
        // Installed from the .msi/.dmg/.deb: the console launcher takes `--native-host`
        // like the Gradle script does, and the browser spawns it without a window.
        InstalledImage.current()?.let { return it.cli }

        val source = runCatching {
            File(BridgeInstall::class.java.protectionDomain.codeSource.location.toURI())
        }.getOrNull() ?: return null

        // .../vaultguard/lib/desktop.jar -> .../vaultguard
        val appHome = source.parentFile?.parentFile ?: return null
        val name = if (isWindows) "vaultguard.bat" else "vaultguard"
        return File(File(appHome, "bin"), name).takeIf { it.exists() }
    }

    fun install(chromeExtensionId: String?, installedLauncher: File?): Report {
        if (installedLauncher == null || !installedLauncher.exists()) {
            return Report(
                problems = listOf(
                    "Could not find the installed launcher.",
                    "Run `gradlew :desktop:installDist`, then run this from",
                    "desktop/build/install/vaultguard/bin/vaultguard - or install the",
                    ".msi and run it as `vaultguard-cli --install-bridge`."
                )
            )
        }

        val done = mutableListOf<String>()
        val registry = mutableListOf<String>()

        // A native-messaging manifest names an executable and cannot pass it arguments, so
        // the browser would run the launcher with none and get the usage text down the
        // protocol stream. This wrapper is what the manifest points at.
        val launcher = writeWrapper(installedLauncher)
        done += "Bridge launcher: ${launcher.path}"

        val directories = manifestDirectories()

        if (chromeExtensionId.isNullOrBlank()) {
            done += "Chrome: skipped, no extension id given (Firefox does not need one)."
        } else {
            val manifest = baseManifest(launcher).put(
                "allowed_origins",
                JSONArray().put("chrome-extension://$chromeExtensionId/")
            )
            val file = File(directories.getValue("chrome"), "$HOST_NAME.chrome.json")
            write(file, manifest)
            done += "Chrome host manifest: ${file.path}"
            if (isWindows) {
                registry += registryInstruction(
                    "HKCU\\Software\\Google\\Chrome\\NativeMessagingHosts\\$HOST_NAME", file
                )
            }
        }

        val firefoxManifest = baseManifest(launcher).put(
            "allowed_extensions", JSONArray().put(FIREFOX_EXTENSION_ID)
        )
        val firefoxFile = File(directories.getValue("firefox"), "$HOST_NAME.firefox.json")
        write(firefoxFile, firefoxManifest)
        done += "Firefox host manifest: ${firefoxFile.path}"
        if (isWindows) {
            registry += registryInstruction(
                "HKCU\\Software\\Mozilla\\NativeMessagingHosts\\$HOST_NAME", firefoxFile
            )
        }

        return Report(written = done, registryCommands = registry)
    }

    /**
     * Writes the shim the browser actually executes.
     *
     * Whether a `.bat` passes native messaging's binary framing through cleanly is exactly
     * the kind of platform question this project has learned not to assume — see the
     * "what the tests cannot tell you" section of CLAUDE.md. If the browser reports the host
     * as unresponsive, this shim is the first place to look.
     */
    private fun writeWrapper(installedLauncher: File): File {
        val directory = File(home, ".vaultguard").apply { mkdirs() }

        val path = installedLauncher.absolutePath

        return if (isWindows) {
            File(directory, "vaultguard-bridge.bat").apply {
                writeText(
                    listOf("@echo off", "call \"$path\" --native-host", "").joinToString("\r\n"),
                    Charsets.UTF_8
                )
            }
        } else {
            File(directory, "vaultguard-bridge").apply {
                writeText(
                    listOf("#!/bin/sh", "exec \"$path\" --native-host", "").joinToString("\n"),
                    Charsets.UTF_8
                )
                setExecutable(true)
            }
        }
    }

    private fun baseManifest(launcher: File): JSONObject = JSONObject()
        .put("name", HOST_NAME)
        .put("description", "VaultGuard native messaging bridge")
        .put("path", launcher.absolutePath)
        .put("type", "stdio")

    private fun write(file: File, manifest: JSONObject) {
        file.parentFile?.mkdirs()
        file.writeText(manifest.toString(2), Charsets.UTF_8)
    }

    /**
     * Printed rather than run.
     *
     * Registering the host is a change to the user's browser configuration, and it is theirs
     * to make knowingly. The command is exact so there is nothing to get wrong.
     */
    /**
     * What is registered now, read back from the manifests (and, on Windows, the registry
     * keys that point at them - a manifest nobody points at registers nothing).
     */
    fun status(): Setup.BridgeStatus {
        val directories = manifestDirectories()
        val chromeFile = File(directories.getValue("chrome"), "$HOST_NAME.chrome.json")
        val firefoxFile = File(directories.getValue("firefox"), "$HOST_NAME.firefox.json")

        fun pointedAt(key: String, file: File): Boolean = !isWindows || runCatching {
            val process = ProcessBuilder("reg", "query", key, "/ve").redirectErrorStream(true).start()
            val output = process.inputStream.bufferedReader().readText()
            process.waitFor() == 0 && output.contains(file.absolutePath, ignoreCase = true)
        }.getOrDefault(false)

        val firefox = firefoxFile.isFile &&
            pointedAt("HKCU\\Software\\Mozilla\\NativeMessagingHosts\\$HOST_NAME", firefoxFile)

        val chromeId = chromeFile.takeIf { it.isFile }?.let { file ->
            runCatching {
                JSONObject(file.readText(Charsets.UTF_8)).optJSONArray("allowed_origins")
                    ?.optString(0)?.removePrefix("chrome-extension://")?.removeSuffix("/")
            }.getOrNull()?.takeIf { it.isNotEmpty() }
        }?.takeIf { pointedAt("HKCU\\Software\\Google\\Chrome\\NativeMessagingHosts\\$HOST_NAME", chromeFile) }

        return Setup.BridgeStatus(firefox = firefox, chromeExtensionId = chromeId)
    }

    private fun registryInstruction(key: String, manifest: File): String =
        "  reg add \"$key\" /ve /t REG_SZ /d \"${manifest.absolutePath}\" /f"
}
