package com.vaultguard.desktop.service

import com.formdev.flatlaf.FlatDarkLaf
import com.formdev.flatlaf.FlatLightLaf
import javax.swing.UIManager

/**
 * FlatLaf, light or dark to match the desktop.
 *
 * What this can and cannot reach: every Swing window - Search, the editor, Settings,
 * Feedback, the password prompt - is drawn by the look and feel and follows it. The tray
 * icon's right-click menu is an AWT `PopupMenu`, which the operating system draws
 * itself; no Java look and feel can touch it, and that is the correct trade - a native
 * menu is what users expect from a tray icon.
 *
 * Applied once, before the first Swing component exists. FlatLaf reads the theme it is
 * told and nothing else; the detection below is a registry value on Windows and a
 * `defaults` read on macOS. Linux has no single answer, so it gets light.
 */
object Theme {

    fun apply() {
        val dark = runCatching { systemPrefersDark() }.getOrDefault(false)
        if (dark) FlatDarkLaf.setup() else FlatLightLaf.setup()

        // A little more room than FlatLaf's defaults; these windows are forms, not tools.
        UIManager.put("Component.arc", 8)
        UIManager.put("Button.arc", 8)
        UIManager.put("TextComponent.arc", 8)
        UIManager.put("Component.focusWidth", 1)
        UIManager.put("TitledBorder.titleColor", UIManager.getColor("Label.foreground"))
    }

    private fun systemPrefersDark(): Boolean {
        val os = System.getProperty("os.name").orEmpty().lowercase()
        return when {
            os.contains("win") -> {
                val process = ProcessBuilder(
                    "reg", "query",
                    "HKCU\\Software\\Microsoft\\Windows\\CurrentVersion\\Themes\\Personalize",
                    "/v", "AppsUseLightTheme"
                ).redirectErrorStream(true).start()
                val output = process.inputStream.bufferedReader().readText()
                process.waitFor()
                // "AppsUseLightTheme    REG_DWORD    0x0" means dark.
                Regex("AppsUseLightTheme\\s+REG_DWORD\\s+0x0\\b").containsMatchIn(output)
            }
            os.contains("mac") -> {
                val process = ProcessBuilder("defaults", "read", "-g", "AppleInterfaceStyle")
                    .redirectErrorStream(true).start()
                val output = process.inputStream.bufferedReader().readText()
                process.waitFor() == 0 && output.trim().equals("Dark", ignoreCase = true)
            }
            else -> false
        }
    }
}
