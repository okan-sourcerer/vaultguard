package com.vaultguard.desktop.service.sni

import com.vaultguard.desktop.service.MenuEntry
import com.vaultguard.desktop.service.TrayModel
import com.vaultguard.desktop.service.VaultIcon
import org.freedesktop.dbus.types.Variant
import java.awt.image.BufferedImage
import java.nio.ByteBuffer

/**
 * A [TrayModel] as a dbusmenu tree: pure, so it can be tested on any machine.
 *
 * Ids are stable per entry id (1 for the status line, then the entries in order), so a
 * client that remembers "item 4" between layouts gets the same item. The status line is a
 * disabled item at the top, as it is in the AWT menu; dbusmenu has no other place for it.
 */
object MenuLayout {

    const val ROOT_ID = 0
    private const val STATUS_ID = 1
    private const val STATUS_SEPARATOR_ID = 2

    fun properties(model: TrayModel, id: Int): Map<String, Variant<*>> = when (id) {
        ROOT_ID -> mapOf("children-display" to Variant("submenu"))
        STATUS_ID -> mapOf("label" to Variant(model.status), "enabled" to Variant(false))
        STATUS_SEPARATOR_ID -> mapOf("type" to Variant("separator"))
        else -> {
            val entry = entryAt(model, id)
            when (entry) {
                null -> emptyMap()
                is MenuEntry.Separator -> mapOf("type" to Variant("separator"))
                is MenuEntry.Item -> mapOf(
                    "label" to Variant(entry.label),
                    "enabled" to Variant(entry.enabled),
                    "visible" to Variant(true)
                )
            }
        }
    }

    fun layout(model: TrayModel): LayoutItem {
        val children = mutableListOf<Variant<*>>()
        children += Variant(LayoutItem(STATUS_ID, properties(model, STATUS_ID), emptyList()))
        // The separator after the status line, as in the AWT menu.
        children += Variant(LayoutItem(STATUS_SEPARATOR_ID, properties(model, STATUS_SEPARATOR_ID), emptyList()))
        var id = firstEntryId(model)
        for (entry in model.entries) {
            children += Variant(LayoutItem(id, properties(model, id), emptyList()))
            id++
        }
        return LayoutItem(ROOT_ID, properties(model, ROOT_ID), children)
    }

    /** Every id in the layout, root included, for `GetGroupProperties`. */
    fun allIds(model: TrayModel): List<Int> =
        listOf(ROOT_ID, STATUS_ID, STATUS_SEPARATOR_ID) + model.entries.indices.map { firstEntryId(model) + it }

    private fun firstEntryId(@Suppress("UNUSED_PARAMETER") model: TrayModel) = STATUS_SEPARATOR_ID + 1

    private fun entryAt(model: TrayModel, id: Int): MenuEntry? =
        model.entries.getOrNull(id - firstEntryId(model))

    /** Item entries only, keyed by id, in a form the click dispatcher can use. */
    fun itemFor(model: TrayModel, id: Int): MenuEntry.Item? = entryAt(model, id) as? MenuEntry.Item
}

/** ARGB32 in network byte order, which is what StatusNotifierItem's `(iiay)` carries. */
object Pixmaps {

    val SIZES = listOf(16, 22, 24, 32, 48)

    fun of(image: BufferedImage): Pixmap {
        val buffer = ByteBuffer.allocate(image.width * image.height * 4)
        for (y in 0 until image.height) {
            for (x in 0 until image.width) {
                // getRGB is ARGB in an int; putInt writes it big-endian, which is the order.
                buffer.putInt(image.getRGB(x, y))
            }
        }
        return Pixmap(image.width, image.height, buffer.array())
    }

    fun icon(locked: Boolean): List<Pixmap> = SIZES.map { of(VaultIcon.image(it, locked)) }
}
