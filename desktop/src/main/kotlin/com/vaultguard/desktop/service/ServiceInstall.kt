package com.vaultguard.desktop.service

import java.io.File

/**
 * Makes the tray service start at login, without a terminal.
 *
 * **The console window (Windows).** Gradle's start script runs `java.exe`, which attaches a console
 * and holds it open for the life of the process. `javaw.exe` is the same JVM without one,
 * so the launcher invokes it directly.
 *
 * **Starting it at login.** Through the `HKCU\...\CurrentVersion\Run` key, which runs the
 * `javaw` command line directly — no interpreter, no console, not even briefly.
 *
 * **macOS and Linux.** A LaunchAgent plist and an XDG autostart entry respectively, with
 * the file contents in [Autostart]. Same policy: the file is generated, and the command that
 * installs it is printed for the user to run.
 *
 * This used to write a `.vbs` into the Startup folder, which is the usual advice and is
 * wrong on a hardened machine: Windows Script Host is disabled by policy on plenty of them
 * (`HKLM\Software\Microsoft\Windows Script Host\Settings\Enabled = 0`), and the script
 * then fails with "Windows Script Host access is disabled on this device". Nothing here
 * depends on WSH any more. Disabling it is a reasonable hardening measure and re-enabling it
 * to launch a password manager would be a poor trade.
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

    val launcherFile: File get() = File(home, ".vaultguard/vaultguard-service.cmd")

    /** Where a .vbs from an older install may still be sitting. */
    private val legacyStartupScript: File get() = File(startupDirectory, "VaultGuard.vbs")

    const val RUN_KEY = """HKCU\Software\Microsoft\Windows\CurrentVersion\Run"""
    const val RUN_VALUE = "VaultGuard"

    data class Report(
        val lines: List<String>,
        val commands: List<String> = emptyList(),
        val problems: List<String> = emptyList()
    ) {
        val succeeded: Boolean get() = problems.isEmpty()
    }

    private val isMac: Boolean
        get() = System.getProperty("os.name").orEmpty().lowercase().contains("mac")

    /** Where the generated autostart files are kept before the user puts them in place. */
    private val stagingDirectory: File get() = File(home, ".vaultguard")

    val launchAgentFile: File
        get() = File(home, "Library/LaunchAgents/${Autostart.LAUNCH_AGENT_LABEL}.plist")

    val autostartEntryFile: File
        get() = File(
            System.getenv("XDG_CONFIG_HOME") ?: File(home, ".config").path,
            "autostart/${Autostart.DESKTOP_ENTRY_NAME}"
        )

    fun install(atLogin: Boolean, installTo: File? = null): Report {
        // Installed from the .msi/.dmg/.deb: the launcher is already somewhere stable, so
        // there is nothing to locate and nothing to copy.
        val installed = InstalledImage.current()
        if (installed != null) {
            return if (isWindows) {
                installFromImage(installed, atLogin, installTo)
            } else {
                installUnix(installed.gui, emptyList(), atLogin, installTo)
            }
        }

        if (!isWindows) {
            val appHome = locateAppHome()
                ?: return Report(emptyList(), problems = notFound)
            return installUnix(File(appHome, "bin/vaultguard"), listOf("--service"), atLogin, installTo)
        }

        val appHome = locateAppHome()
            ?: return Report(emptyList(), problems = notFound)

        var native = locateNativeLauncher(appHome)

        // The jpackage image lives under build/, which `gradlew clean` removes -- leaving a
        // Run key pointing at nothing, discovered at the next login. Copying it somewhere
        // stable is the fix, and it has to happen before the command line is built.
        if (installTo != null) {
            if (native == null) {
                return Report(
                    emptyList(),
                    problems = listOf(
                        "There is no native image to copy.",
                        "Run `gradlew :desktop:packageApp` first."
                    )
                )
            }

            val destination = File(installTo, "VaultGuard")
            val copied = copyImage(native.parentFile, destination)
                ?: return Report(
                    emptyList(),
                    problems = listOf(
                        "Could not copy the image to ${destination.path}.",
                        "If VaultGuard is running from there, quit it from the tray first."
                    )
                )
            native = copied
        }
        val command = if (native != null) {
            "\"${native.absolutePath}\""
        } else {
            val javaw = locateJavaw()
                ?: return Report(
                    emptyList(),
                    problems = listOf("Could not find javaw.exe next to the running JVM.")
                )
            serviceCommand(javaw, appHome)
        }

        writeLauncher(command)

        val lines = mutableListOf<String>()
        lines += if (native != null) {
            "Using the native launcher: ${native.path}"
        } else {
            "Using javaw. Run `gradlew :desktop:packageApp` for a launcher Windows can name" +
                " and draw, then run this again."
        }
        lines += "Launcher: ${launcherFile.path}"
        lines += "  Double-click it to start the service now, with no window left behind."
        val commands = mutableListOf<String>()

        if (atLogin) {
            // Printed rather than run: this is a persistent change to what happens when the
            // user logs in, and it is theirs to make knowingly. Same reasoning as the
            // native-messaging registration.
            commands += "  reg add \"$RUN_KEY\" /v $RUN_VALUE /t REG_SZ /d \"${escapeForCommandLine(command)}\" /f"
            lines += "Run the command below to start it at every login."
        } else {
            lines += "Not set to run at login. Add --at-login for the command that does that."
        }

        // An artefact of this same command from before it stopped using Windows Script
        // Host. It cannot work, and it is ours, so it goes rather than being warned about.
        if (legacyStartupScript.exists() && legacyStartupScript.delete()) {
            lines += "Removed ${legacyStartupScript.name} from your Startup folder - it needed"
            lines += "  Windows Script Host, which is disabled on this machine."
        }

        return Report(lines, commands)
    }

    private val notFound = listOf(
        "Could not find the installed application.",
        "Run `gradlew :desktop:installDist`, then run this from",
        "desktop/build/install/vaultguard/bin/vaultguard - or install the",
        "release package and run it as `vaultguard-cli --install-service`."
    )

    /**
     * macOS and Linux. The launcher is either the installed image's windowed launcher, or
     * the Gradle start script with `--service`; neither needs a `javaw` equivalent, since
     * a Unix process has no console unless something gives it one.
     *
     * The autostart file is written to `~/.vaultguard` and the one command that puts it in
     * place is printed, not run - the same policy as the Windows Run key: it changes what
     * happens at login, and it is the user's to make knowingly.
     */
    private fun installUnix(launcher: File, arguments: List<String>, atLogin: Boolean, installTo: File?): Report {
        if (!launcher.exists()) {
            return Report(emptyList(), problems = listOf("Launcher not found: ${launcher.path}") + notFound.drop(1))
        }

        val lines = mutableListOf<String>()
        val commands = mutableListOf<String>()
        lines += "Using the launcher: ${launcher.path}"
        if (installTo != null) {
            lines += "  --to is a Windows option: here the package manager decides where the app lives."
        }

        if (!atLogin) {
            lines += "Not set to run at login. Add --at-login for the command that does that."
            return Report(lines, commands)
        }

        val path = launcher.absolutePath
        if (isMac) {
            val staged = File(stagingDirectory, launchAgentFile.name)
            stage(staged, Autostart.launchAgentPlist(path, arguments))
            lines += "LaunchAgent written to ${staged.path}"
            lines += "Run the commands below to install it and start it now."
            commands += "  mkdir -p \"${launchAgentFile.parentFile.path}\""
            commands += "  cp \"${staged.path}\" \"${launchAgentFile.path}\""
            commands += "  launchctl bootstrap gui/${"$"}(id -u) \"${launchAgentFile.path}\""
        } else {
            val staged = File(stagingDirectory, autostartEntryFile.name)
            stage(staged, Autostart.desktopEntry(path, arguments))
            lines += "Autostart entry written to ${staged.path}"
            lines += "Run the commands below to install it. It takes effect at the next login."
            commands += "  mkdir -p \"${autostartEntryFile.parentFile.path}\""
            commands += "  cp \"${staged.path}\" \"${autostartEntryFile.path}\""
        }
        return Report(lines, commands)
    }

    private fun stage(file: File, content: String) {
        file.parentFile?.mkdirs()
        file.writeText(content, Charsets.UTF_8)
    }

    private fun installFromImage(image: InstalledImage, atLogin: Boolean, installTo: File?): Report {
        val command = "\"${image.gui.absolutePath}\""
        writeLauncher(command)

        val lines = mutableListOf<String>()
        lines += "Using the installed launcher: ${image.gui.path}"
        if (installTo != null) {
            lines += "  --to ignored: an installed copy is already somewhere `clean` cannot reach."
        }
        lines += "Launcher: ${launcherFile.path}"
        lines += "  Double-click it to start the service now, with no window left behind."

        val commands = mutableListOf<String>()
        if (atLogin) {
            commands += "  reg add \"$RUN_KEY\" /v $RUN_VALUE /t REG_SZ /d \"${escapeForCommandLine(command)}\" /f"
            lines += "Run the command below to start it at every login."
        } else {
            lines += "Not set to run at login. Add --at-login for the command that does that."
        }
        return Report(lines, commands)
    }

    fun uninstall(): Report {
        val lines = mutableListOf<String>()

        if (!isWindows) {
            lines += "A service already running is not stopped - use Quit on the tray icon."
            val commands = if (isMac) {
                listOf(
                    "  launchctl bootout gui/${"$"}(id -u) \"${launchAgentFile.path}\"",
                    "  rm \"${launchAgentFile.path}\""
                )
            } else {
                listOf("  rm \"${autostartEntryFile.path}\"")
            }
            return Report(lines, commands)
        }

        if (legacyStartupScript.exists() && legacyStartupScript.delete()) {
            lines += "Removed ${legacyStartupScript.path}"
        }

        lines += "The launcher at ${launcherFile.path} is left in place; it is harmless."
        lines += "A service already running is not stopped - use Quit on the tray icon."

        return Report(
            lines,
            commands = listOf("  reg delete \"$RUN_KEY\" /v $RUN_VALUE /f")
        )
    }

    /** `.../vaultguard/lib/desktop.jar` -> `.../vaultguard` */
    private fun locateAppHome(): File? {
        val source = runCatching {
            File(ServiceInstall::class.java.protectionDomain.codeSource.location.toURI())
        }.getOrNull() ?: return null
        return source.parentFile?.parentFile?.takeIf { File(it, "lib").isDirectory }
    }

    /**
     * The jpackage image, if it has been built.
     *
     * Preferred over `javaw` because Windows shows a process by its executable: without it
     * the thing holding the vault open appears in Task Manager as `javaw.exe` with a coffee
     * cup, indistinguishable from any other JVM. `--arguments --service` is baked into the
     * image, so the command line is just the path.
     */
    private fun locateNativeLauncher(appHome: File): File? {
        // .../desktop/build/install/vaultguard -> .../desktop/build/native/VaultGuard
        val buildDir = appHome.parentFile?.parentFile ?: return null
        return File(buildDir, "native/VaultGuard/VaultGuard.exe").takeIf { it.exists() }
    }

    /**
     * Copies the whole app image, not just the executable.
     *
     * jpackage produces a launcher, an `app` directory and a bundled runtime, and the
     * launcher finds the other two by their position beside it. Copying the `.exe` alone
     * produces something that looks installed and cannot start.
     *
     * @return the copied launcher, or null if the copy failed.
     */
    private fun copyImage(source: File, destination: File): File? = try {
        if (destination.exists() && !destination.deleteRecursively()) {
            null
        } else {
            source.copyRecursively(destination, overwrite = true)
            File(destination, "VaultGuard.exe").takeIf { it.exists() }
        }
    } catch (e: Exception) {
        null
    }

    private fun locateJavaw(): File? {
        val javaHome = System.getProperty("java.home") ?: return null
        return File(File(javaHome, "bin"), "javaw.exe").takeIf { it.exists() }
    }

    /**
     * Quotes a command so it survives being the `/d` argument of `reg add`.
     *
     * The value is itself a quoted command line, so its quotes have to reach the registry
     * rather than terminating the argument. `cmd` reads a backslash-escaped quote as a
     * literal one. Without this the value is truncated at the first space in
     * `C:\Program Files\...` and the entry silently launches nothing.
     */
    private fun escapeForCommandLine(value: String): String = value.replace("\"", "\\\"")

    /**
     * The command line that starts the service, used both by the launcher and the Run key.
     *
     * `javaw.exe` rather than `java.exe`: same JVM, no console attached.
     */
    private fun serviceCommand(javaw: File, appHome: File): String {
        val classpath = File(appHome, "lib").absolutePath + File.separator + "*"
        return "\"${javaw.absolutePath}\" -cp \"$classpath\" com.vaultguard.desktop.MainKt --service"
    }

    /**
     * A `.cmd` for starting it by hand.
     *
     * `start ""` hands the JVM off and lets the shell exit immediately, so the console this
     * opens closes again at once rather than living as long as the service. At login the Run
     * key is used instead and no console appears at all.
     */
    private fun writeLauncher(command: String) {
        val script = listOf(
            "@echo off",
            "rem VaultGuard - starts the tray service. Written by `vaultguard --install-service`.",
            "start \"\" $command",
            ""
        ).joinToString("\r\n")

        launcherFile.parentFile?.mkdirs()
        launcherFile.writeText(script, Charsets.UTF_8)
    }
}
