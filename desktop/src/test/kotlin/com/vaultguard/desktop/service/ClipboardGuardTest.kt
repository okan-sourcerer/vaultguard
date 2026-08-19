package com.vaultguard.desktop.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ClipboardGuardTest {

    private class FakeClipboard(var contents: String? = null) : ClipboardAccess {
        var writes = 0
            private set

        override fun read(): String? = contents

        override fun write(value: String) {
            writes++
            contents = value
        }
    }

    private fun guard(clipboard: FakeClipboard) = ClipboardGuard(clipboard, holdSeconds = 3600)

    @Test
    fun `copying puts the secret on the clipboard`() {
        val clipboard = FakeClipboard()

        guard(clipboard).copy("hunter2")

        assertEquals("hunter2", clipboard.contents)
    }

    @Test
    fun `clearing takes back what it put there`() {
        val clipboard = FakeClipboard()
        val guard = guard(clipboard)
        guard.copy("hunter2")

        assertTrue(guard.clearIfUnchanged())
        assertEquals("", clipboard.contents)
    }

    @Test
    fun `something copied since is left alone`() {
        val clipboard = FakeClipboard()
        val guard = guard(clipboard)
        guard.copy("hunter2")

        // The user copied a URL while the timer was running. Wiping it is how a password
        // manager becomes the thing you turn off (#31, #46 on the Android side).
        clipboard.contents = "https://example.com/something-they-wanted"

        assertFalse(guard.clearIfUnchanged())
        assertEquals("https://example.com/something-they-wanted", clipboard.contents)
    }

    @Test
    fun `clearing twice does nothing the second time`() {
        val clipboard = FakeClipboard()
        val guard = guard(clipboard)
        guard.copy("hunter2")

        assertTrue(guard.clearIfUnchanged())
        val writesAfterFirst = clipboard.writes

        assertFalse(guard.clearIfUnchanged())
        assertEquals(writesAfterFirst, clipboard.writes)
    }

    @Test
    fun `clearing without a copy does nothing`() {
        val clipboard = FakeClipboard("something the user put there")
        val guard = guard(clipboard)

        assertFalse(guard.clearIfUnchanged())
        assertEquals("something the user put there", clipboard.contents)
    }

    @Test
    fun `a second copy supersedes the first`() {
        val clipboard = FakeClipboard()
        val guard = guard(clipboard)

        guard.copy("first")
        guard.copy("second")

        // Only the newest is ours. If the first copy's identity survived, its timer would
        // find a mismatch and decline to clear the second - leaving a password on the
        // clipboard for ever.
        assertTrue(guard.clearIfUnchanged())
        assertEquals("", clipboard.contents)
    }

    @Test
    fun `an empty clipboard is not mistaken for our secret`() {
        val clipboard = FakeClipboard(null)
        val guard = guard(clipboard)

        assertFalse(guard.clearIfUnchanged())
        assertEquals(0, clipboard.writes)
    }
}
