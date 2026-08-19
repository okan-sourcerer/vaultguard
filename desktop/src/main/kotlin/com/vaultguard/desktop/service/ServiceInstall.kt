package com.vaultguard.desktop.service

import java.io.File

/**
 * Makes the tray service start at login, without a terminal.
 *
 * Two separate annoyances, two separate fixes:
 *
 * - **The console window.** Gradle's start script runs `java.exe`, which attaches a console
 *   and holds it open for the life of the process. `javaw.exe` is the same JVM without one.
 *   The launcher written here calls `javaw` directly rather than going through the script,
 *   which also sidesteps `cmd` entirely.
 * - **Having to start it.** A file in the user's Startup folder runs at login. A `.vbs`
 *   rather than a `.bat`, because a batch file flashes a console for a moment even when
 *   what it launches has none.
 */
object ServiceInstall {

    private val home: File get() = File(System.getProperty("user.home"))

    private val isWindows: Boolean
        get() = System.getProperty("os.name").orEmpty().lowercase().contains("win")

    /** Where Windows runs things at login. */
    val startupDirectory: File
        get() = File(
            System.getenv("APPDATA") ?: File(home, "AppData/Roaming").path,
            "Microsoft/Windows/Start Menu/Programs/Startup"
        )

    val launcherFile: File get() = File(home, ".vaultguard/vaultguard-service.vbs")

    private val startupShortcut: File get() = File(startupDirectory, "VaultGuard.vbs")

    data class Report(val lines: List<String>, val problems: List<String> = emptyList()) {
        val succeeded: Boolean get() = problems.isEmpty()
    }

    fun install(atLogin: Boolean): Report {
        if (!isWindows) {
            return Report(
                emptyList(),
                listOf(
                    "Automatic startup is only wired up for Windows so far.",
                    "On Linux or macOS, run `vaultguard --service` from your session's",
                    "autostart (a .desktop file, or a LaunchAgent)."
                )
            )
        }

        val appHome = locateAppHome()
            ?: return Report(
                emptyList(),
                listOf(
                    "Could not find the installed application.",
                    "Run `gradlew :desktop:installDist`, then run this from",
                    "desktop/build/install/vaultguard/bin/vaultguard."
                )
            )

        val javaw = locateJavaw()
            ?: return Report(emptyList(), listOf("Could not find javaw.exe next to the running JVM."))

        writeLauncher(javaw, appHome)
        val lines = mutableListOf("Launcher: ${launcherFile.path}")

        if (atLogin) {
            startupDirectory.mkdirs()
            launcherFile.copyTo(startupShortcut, overwrite = true)
            lines += "Runs at login: ${startupShortcut.path}"
            lines += "Remove that file to stop it, or run --uninstall-service."
        } else {
            lines += "Not set to run at login. Add --at-login to do that."
        }

        return Report(lines)
    }

    fun uninstall(): Report {
        val removed = startupShortcut.exists() && startupShortcut.delete()
        return Report(
            listOf(
                if (removed) "Removed ${startupShortcut.path}"
                else "Nothing at ${startupShortcut.path}",
                "The launcher at ${launcherFile.path} is left in place; it is harmless.",
                "A service already running is not stopped - use Quit on the tray icon."
            )
        )
    }

    /** `.../vaultguard/lib/desktop.jar` -> `.../vaultguard` */
    private fun locateAppHome(): File? {
        val source = runCatching {
            File(ServiceInstall::class.java.protectionDomain.codeSource.location.toURI())
        }.getOrNull() ?: return null
        return source.parentFile?.parentFile?.takeIf { File(it, "lib").isDirectory }
    }

    private fun locateJavaw(): File? {
        val javaHome = System.getProperty("java.home") ?: return null
        return File(File(javaHome, "bin"), "javaw.exe").takeIf { it.exists() }
    }

    /**
     * A one-line VBScript that starts the JVM with no window at all.
     *
     * `0` is the window style — hidden — and `False` means do not wait for it to exit, so
     * the script ends immediately and leaves the service running.
     */
    private fun writeLauncher(javaw: File, appHome: File) {
        val classpath = File(appHome, "lib").absolutePath + File.separator + "*"
        val command = "\"\"${javaw.absolutePath}\"\" -cp \"\"$classpath\"\" " +
            "com.vaultguard.desktop.MainKt --service"

        val script = listOf(
            "' VaultGuard - starts the tray service with no console window.",
            "' Written by `vaultguard --install-service`; safe to delete.",
            "Set shell = CreateObject(\"WScript.Shell\")",
            "shell.Run \"$command\", 0, False",
            ""
        ).joinToString("\r\n")

        launcherFile.parentFile?.mkdirs()
        launcherFile.writeText(script, Charsets.UTF_8)
    }
}
