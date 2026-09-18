package com.vaultguard.desktop.service

import java.awt.BasicStroke
import java.awt.Color
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.imageio.ImageIO

/**
 * The padlock, drawn rather than shipped.
 *
 * One drawing serves the tray, the dialog windows and the `.ico` that the native launcher
 * is built with, so they cannot drift apart — and there is no binary asset in the repository
 * to keep in step with it.
 */
object VaultIcon {

    private val LOCKED_BODY = Color(96, 100, 110)
    private val OPEN_BODY = Color(52, 140, 90)
    private val SHACKLE = Color(150, 155, 165)
    private val OPEN_SHACKLE = Color(96, 175, 128)

    /** Sizes Windows asks for. 256 is what modern shells actually scale from. */
    private val ICO_SIZES = listOf(16, 24, 32, 48, 64, 128, 256)

    fun image(size: Int, locked: Boolean = true): BufferedImage {
        val image = BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB)
        val g = image.createGraphics() as Graphics2D
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE)

            val unit = size / 16f

            // The shackle first, so the body overlaps its feet rather than the other way
            // round: at 16px the join is a single pixel and the order is visible.
            g.color = if (locked) SHACKLE else OPEN_SHACKLE
            g.stroke = BasicStroke(1.7f * unit, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
            val shackleX = if (locked) 4.6f * unit else 6.2f * unit
            g.drawArc(
                (shackleX).toInt(), (1.6f * unit).toInt(),
                (6.8f * unit).toInt(), (7.6f * unit).toInt(),
                0, 180
            )

            g.color = if (locked) LOCKED_BODY else OPEN_BODY
            val radius = (3.2f * unit).toInt()
            g.fillRoundRect(
                (2.2f * unit).toInt(), (6.4f * unit).toInt(),
                (11.6f * unit).toInt(), (8.2f * unit).toInt(),
                radius, radius
            )

            // Keyhole, only where there are pixels to spare for it.
            if (size >= 32) {
                g.color = Color(255, 255, 255, 220)
                val keyhole = (2.2f * unit).toInt()
                g.fillOval(
                    (size / 2f - keyhole / 2f).toInt(), (9.0f * unit).toInt(),
                    keyhole, keyhole
                )
            }
        } finally {
            g.dispose()
        }
        return image
    }

    /** The sizes a window manager may pick from. */
    fun windowIcons(locked: Boolean = true): List<BufferedImage> =
        listOf(16, 32, 48, 64, 128).map { image(it, locked) }

    /**
     * Writes a single PNG, for the platforms whose packager wants one (Linux).
     *
     * 512 rather than the largest `.ico` entry: desktop environments scale the launcher
     * icon up for the application grid, and a small source goes blurry there.
     */
    fun writePng(target: File, size: Int = 512) {
        target.parentFile?.mkdirs()
        ImageIO.write(image(size), "png", target)
    }

    /**
     * Writes a Windows `.ico`.
     *
     * Each entry is a PNG rather than a device-independent bitmap. Vista onwards reads PNG
     * inside an ICO, it is a fraction of the size, and it avoids hand-rolling a DIB with its
     * upside-down rows and separate AND mask.
     */
    fun writeIco(target: File) {
        val pngs = ICO_SIZES.map { size ->
            ByteArrayOutputStream().also { ImageIO.write(image(size), "png", it) }.toByteArray()
        }

        val header = ByteBuffer.allocate(6).order(ByteOrder.LITTLE_ENDIAN)
            .putShort(0)  // reserved
            .putShort(1)  // 1 = icon
            .putShort(pngs.size.toShort())
            .array()

        // Directory entries are fixed width, so the first image starts after all of them.
        var offset = header.size + pngs.size * 16

        val directory = ByteArrayOutputStream()
        for ((index, png) in pngs.withIndex()) {
            val size = ICO_SIZES[index]
            val entry = ByteBuffer.allocate(16).order(ByteOrder.LITTLE_ENDIAN)
            // 256 is written as 0: the field is one byte and 256 does not fit.
            entry.put(if (size >= 256) 0 else size.toByte())
            entry.put(if (size >= 256) 0 else size.toByte())
            entry.put(0)      // palette size, 0 for truecolour
            entry.put(0)      // reserved
            entry.putShort(1) // colour planes
            entry.putShort(32)// bits per pixel
            entry.putInt(png.size)
            entry.putInt(offset)
            directory.write(entry.array())
            offset += png.size
        }

        target.parentFile?.mkdirs()
        target.outputStream().buffered().use { out ->
            out.write(header)
            out.write(directory.toByteArray())
            pngs.forEach { out.write(it) }
        }
    }
}
