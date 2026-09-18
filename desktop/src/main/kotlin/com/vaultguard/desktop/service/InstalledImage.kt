package com.vaultguard.desktop.service

import java.io.File

/**
 * The jpackage image this process is running from, if it is running from one.
 *
 * There are two ways VaultGuard gets onto a machine. `gradlew :desktop:installDist` puts a
 * `bin/` and a `lib/` under `build/`, and the code locates itself by walking up from
 * `lib/desktop.jar`. An installer — the `.msi`, `.dmg` or `.deb` from `packageInstaller` —
 * puts a launcher, an `app/` directory and a bundled runtime wherever the user chose, and the
 * walk from `app/desktop.jar` lands somewhere without a `bin/`. `--install-service` and
 * `--install-bridge` then reported that the application could not be found, from inside the
 * application.
 *
 * The executable that started this process is the reliable clue: a jpackage launcher sits
 * beside its siblings, so from `vaultguard-cli` the windowed `VaultGuard` is one directory
 * lookup away. Nothing is inferred from the jar's location.
 */
data class InstalledImage(
    /** The windowed launcher. Double-click, or a Run key, starts the tray service. */
    val gui: File,
    /** The console launcher. What a person types, and what the browser wrapper calls. */
    val cli: File
) {
    companion object {
        private const val GUI_NAME = "VaultGuard"
        private const val CLI_NAME = "vaultguard-cli"

        fun current(): InstalledImage? {
            val command = ProcessHandle.current().info().command().orElse(null) ?: return null
            val directory = File(command).parentFile ?: return null
            val extension = if (File(command).extension.equals("exe", ignoreCase = true)) ".exe" else ""
            val gui = File(directory, GUI_NAME + extension)
            val cli = File(directory, CLI_NAME + extension)
            return if (gui.isFile && cli.isFile) InstalledImage(gui, cli) else null
        }
    }
}
