package com.vaultguard.desktop.service.sni

import com.vaultguard.desktop.service.MenuEntry
import com.vaultguard.desktop.service.TrayModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.awt.image.BufferedImage

class MenuLayoutTest {

    private var clicked: String? = null

    private val model = TrayModel(
        locked = true,
        status = "Locked",
        entries = listOf(
            MenuEntry.Item("open", "Open VaultGuard") { clicked = "open" },
            MenuEntry.Separator,
            MenuEntry.Item("unlock", "Unlock", enabled = true) { clicked = "unlock" },
            MenuEntry.Item("lock", "Lock", enabled = false) { clicked = "lock" },
            MenuEntry.Item("quit", "Quit") { clicked = "quit" }
        )
    )

    private fun LayoutItem.label() = properties["label"]?.value as? String
    private fun LayoutItem.children() = children.map { it.value as LayoutItem }

    @Test
    fun `the layout is the status line, a separator, then the entries in order`() {
        val root = MenuLayout.layout(model)

        assertEquals(MenuLayout.ROOT_ID, root.id)
        assertEquals("submenu", root.properties["children-display"]?.value)

        val rows = root.children()
        assertEquals("Locked", rows[0].label())
        assertEquals(false, rows[0].properties["enabled"]?.value)
        assertEquals("separator", rows[1].properties["type"]?.value)
        assertEquals(listOf("Open VaultGuard", null, "Unlock", "Lock", "Quit"), rows.drop(2).map { it.label() })
        assertEquals("separator", rows[3].properties["type"]?.value)
        assertEquals(false, rows[5].properties["enabled"]?.value)
    }

    @Test
    fun `ids are stable and map back to the entry that was clicked`() {
        val root = MenuLayout.layout(model)
        val unlockId = root.children().first { it.label() == "Unlock" }.id
        val lockId = root.children().first { it.label() == "Lock" }.id

        assertEquals("unlock", MenuLayout.itemFor(model, unlockId)?.id)
        assertEquals("lock", MenuLayout.itemFor(model, lockId)?.id)
        assertNull(MenuLayout.itemFor(model, 999))
        // A separator is not an item and cannot be clicked.
        val separatorId = root.children().first { it.properties["type"]?.value == "separator" && it.id > 2 }.id
        assertNull(MenuLayout.itemFor(model, separatorId))

        assertEquals(MenuLayout.layout(model).children().map { it.id }, MenuLayout.allIds(model).drop(1))
    }

    @Test
    fun `properties by id agree with the layout`() {
        val root = MenuLayout.layout(model)
        for (row in root.children()) {
            assertEquals(row.properties.keys, MenuLayout.properties(model, row.id).keys)
        }
        assertTrue(MenuLayout.properties(model, 999).isEmpty())
    }

    @Test
    fun `pixmaps are ARGB in network byte order`() {
        val image = BufferedImage(2, 1, BufferedImage.TYPE_INT_ARGB)
        image.setRGB(0, 0, 0x80FF0000.toInt()) // half-transparent red
        image.setRGB(1, 0, 0xFF0000FF.toInt()) // opaque blue

        val pixmap = Pixmaps.of(image)
        assertEquals(2, pixmap.width)
        assertEquals(1, pixmap.height)
        assertEquals(listOf(0x80, 0xFF, 0x00, 0x00, 0xFF, 0x00, 0x00, 0xFF), pixmap.data.map { it.toInt() and 0xFF })
    }

    @Test
    fun `the notification image is RGBA with the row stride the spec wants`() {
        val data = SniFrontend.imageData(locked = true)
        assertEquals(48, data.width)
        assertEquals(48 * 4, data.rowstride)
        assertTrue(data.hasAlpha)
        assertEquals(8, data.bitsPerSample)
        assertEquals(4, data.channels)
        assertEquals(48 * 48 * 4, data.data.size)
        assertFalse(data.data.all { it == 0.toByte() })
    }
}
