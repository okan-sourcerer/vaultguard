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
     * @param launcher the command the browser should run. Must be an executable, not a
     *        jar: browsers exec it directly with no shell.
     */
    fun install(chromeExtensionId: String?, installedLauncher: File): List<String> {
        val done = mutableListOf<String>()

        if (!installedLauncher.exists()) {
            return listOf(
                "Launcher not found at ${installedLauncher.path}. " +
                    "Run `gradlew :desktop:installDist` first."
            )
        }

        // A native-messaging manifest names an executable and cannot pass it arguments, so
        // the browser would run the launcher with none and get the usage text down the
        // protocol stream. This wrapper is what the manifest points at.
        val launcher = writeWrapper(installedLauncher)
        done += "Bridge launcher: ${launcher.path}"

        val directories = manifestDirectories()

        if (chromeExtensionId.isNullOrBlank()) {
            done += "Skipped Chrome: no extension id given."
        } else {
            val manifest = baseManifest(launcher).put(
                "allowed_origins",
                JSONArray().put("chrome-extension://$chromeExtensionId/")
            )
            val file = File(directories.getValue("chrome"), "$HOST_NAME.chrome.json")
            write(file, manifest)
            done += "Chrome host manifest: ${file.path}"
            if (isWindows) {
                done += registryInstruction(
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
            done += registryInstruction(
                "HKCU\\Software\\Mozilla\\NativeMessagingHosts\\$HOST_NAME", firefoxFile
            )
        }

        return done
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
    private fun registryInstruction(key: String, manifest: File): String =
        "  reg add \"$key\" /ve /t REG_SZ /d \"${manifest.absolutePath}\" /f"
}
