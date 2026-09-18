package com.vaultguard.desktop.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AutostartTest {

    @Test
    fun `launch agent names the launcher and runs at load`() {
        val plist = Autostart.launchAgentPlist("/Applications/VaultGuard.app/Contents/MacOS/VaultGuard")

        assertTrue(plist.startsWith("<?xml version=\"1.0\""))
        assertTrue(plist.contains("<string>com.vaultguard.service</string>"))
        assertTrue(plist.contains("<string>/Applications/VaultGuard.app/Contents/MacOS/VaultGuard</string>"))
        assertTrue(plist.contains("<key>RunAtLoad</key>\n    <true/>"))
    }

    @Test
    fun `launch agent escapes XML in the path and keeps arguments separate`() {
        val plist = Autostart.launchAgentPlist("/opt/a&b/vaultguard", listOf("--service"))

        assertTrue(plist.contains("<string>/opt/a&amp;b/vaultguard</string>\n        <string>--service</string>"))
    }

    @Test
    fun `desktop entry quotes the Exec path`() {
        val entry = Autostart.desktopEntry("/opt/vault guard/bin/VaultGuard")

        assertTrue(entry.startsWith("[Desktop Entry]\n"))
        assertTrue(entry.contains("Exec=\"/opt/vault guard/bin/VaultGuard\"\n"))
        assertTrue(entry.contains("Terminal=false"))
    }

    @Test
    fun `desktop entry escapes the characters the spec reserves`() {
        val entry = Autostart.desktopEntry("/opt/\$HOME/\"quoted\"/VaultGuard", listOf("--service"))

        val exec = entry.lines().first { it.startsWith("Exec=") }
        assertEquals("Exec=\"/opt/\\\$HOME/\\\"quoted\\\"/VaultGuard\" \"--service\"", exec)
    }
}
