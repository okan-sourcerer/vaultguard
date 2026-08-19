package com.vaultguard.desktop.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WindowListTest {

    @Test
    fun `a process and title become a window`() {
        val windows = WindowList.parse("steam|Steam")

        assertEquals(1, windows.size)
        assertEquals("steam", windows.single().process)
        assertEquals("Steam", windows.single().title)
    }

    @Test
    fun `a title containing the separator survives intact`() {
        // Window titles are whatever the application felt like, and a pipe in one is not
        // unusual. Splitting on every separator would truncate the title and, worse, could
        // take the wrong half as the process name.
        val windows = WindowList.parse("firefox|GitHub | okan | Mozilla Firefox")

        assertEquals("firefox", windows.single().process)
        assertEquals("GitHub | okan | Mozilla Firefox", windows.single().title)
    }

    @Test
    fun `the search term drops the extension`() {
        assertEquals("steam", OpenWindow("steam.exe", "Steam").searchTerm)
        assertEquals("Code", OpenWindow("Code", "VaultGuard").searchTerm)
    }

    @Test
    fun `the search term is the process, not the title`() {
        // "Inbox (14) - okan@example.com - Mozilla Thunderbird" matches no credential.
        // The process name is short, stable across documents, and usually what the entry
        // is called.
        val window = OpenWindow("thunderbird", "Inbox (14) - okan@example.com - Mozilla Thunderbird")
        assertEquals("thunderbird", window.searchTerm)
    }

    @Test
    fun `blank and malformed lines are dropped`() {
        val windows = WindowList.parse(
            """
            steam|Steam

            no-separator-here
            |a title with no process
            firefox|Firefox
            """.trimIndent()
        )

        assertEquals(listOf("firefox", "steam"), windows.map { it.process })
    }

    @Test
    fun `identical windows are listed once`() {
        val windows = WindowList.parse("chrome|Gmail\nchrome|Gmail\nchrome|Calendar")

        assertEquals(2, windows.size)
    }

    @Test
    fun `windows are sorted by name`() {
        val windows = WindowList.parse("steam|Steam\nchrome|Gmail\nAcrobat|A PDF")

        assertEquals(listOf("Acrobat", "chrome", "steam"), windows.map { it.process })
    }

    @Test
    fun `empty output is not an error`() {
        assertTrue(WindowList.parse("").isEmpty())
        assertTrue(WindowList.parse("\n  \n").isEmpty())
    }

    @Test
    fun `a window with no title still shows its process`() {
        val window = OpenWindow("steam", "")
        assertEquals("steam", window.toString())
    }
}
