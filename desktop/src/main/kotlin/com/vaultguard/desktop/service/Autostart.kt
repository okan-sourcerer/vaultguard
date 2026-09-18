package com.vaultguard.desktop.service

/**
 * The files that make the tray service start at login on macOS and Linux.
 *
 * Pure text generation, kept apart from [ServiceInstall] so the exact bytes can be checked
 * by a test on any machine — the platforms that consume them are not the one this is
 * developed on, and a malformed plist fails at the next login with nothing to say.
 *
 * **macOS** — a LaunchAgent. `~/Library/LaunchAgents/<label>.plist` is read at login;
 * `launchctl bootstrap gui/<uid> <plist>` loads it now. `ProcessType Interactive` because
 * the process owns a menu-bar item and dialogs, not background work.
 *
 * **Linux** — an XDG autostart entry. A `.desktop` file in `~/.config/autostart` is honoured by
 * every desktop environment that has a tray to put the icon in; GNOME reads it too, and
 * then the service exits because there is no tray (see ARCHITECTURE.md).
 */
object Autostart {

    const val LAUNCH_AGENT_LABEL = "com.vaultguard.service"
    const val DESKTOP_ENTRY_NAME = "vaultguard.desktop"

    /** [launcher] is an absolute path; taken as text so a test can hold a Unix one on Windows. */
    fun launchAgentPlist(launcher: String, arguments: List<String> = emptyList()): String {
        val program = (listOf(launcher) + arguments)
            .joinToString("") { "        <string>${escapeXml(it)}</string>\n" }
        return """
            |<?xml version="1.0" encoding="UTF-8"?>
            |<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
            |<plist version="1.0">
            |<dict>
            |    <key>Label</key>
            |    <string>$LAUNCH_AGENT_LABEL</string>
            |    <key>ProgramArguments</key>
            |    <array>
            |$program    </array>
            |    <key>RunAtLoad</key>
            |    <true/>
            |    <key>ProcessType</key>
            |    <string>Interactive</string>
            |</dict>
            |</plist>
            |""".trimMargin()
    }

    /**
     * `Exec=` follows the Desktop Entry spec's own quoting: the whole argument in double
     * quotes, with `"`, `` ` ``, `$` and `\` backslash-escaped inside. A path with a space
     * and no quotes is two arguments, and the entry silently launches nothing.
     */
    fun desktopEntry(launcher: String, arguments: List<String> = emptyList()): String {
        val exec = (listOf(launcher) + arguments).joinToString(" ") { quoteExec(it) }
        return """
            |[Desktop Entry]
            |Type=Application
            |Name=VaultGuard
            |Comment=Holds the password vault open for the browser extension
            |Exec=$exec
            |Terminal=false
            |X-GNOME-Autostart-enabled=true
            |""".trimMargin()
    }

    private fun quoteExec(argument: String): String =
        "\"" + argument.replace("\\", "\\\\").replace("\"", "\\\"").replace("`", "\\`").replace("$", "\\$") + "\""

    private fun escapeXml(text: String): String =
        text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
}
