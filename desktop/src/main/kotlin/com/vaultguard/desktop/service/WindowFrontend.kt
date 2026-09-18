package com.vaultguard.desktop.service

import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.Font
import java.awt.event.WindowAdapter
import java.awt.event.WindowEvent
import javax.swing.BorderFactory
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.JButton
import javax.swing.JFrame
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JSeparator
import javax.swing.SwingUtilities
import javax.swing.WindowConstants

/**
 * The application when there is no tray: a small window that stays open.
 *
 * On GNOME without the AppIndicator extension there is nowhere to put an icon, and a
 * process with no icon and no window is indistinguishable from one that failed. So the
 * window is the icon: the padlock, the status line, the same menu as buttons, and a
 * notification is a line of text plus the window coming to the front. Closing it quits,
 * after locking - there is nothing to minimise to.
 *
 * Deliberately plain. Its job is to exist; the Search window is where the work happens.
 */
class WindowFrontend : Frontend {

    private lateinit var frame: JFrame
    private lateinit var onPrimary: () -> Unit
    private val icon = JLabel()
    private val status = JLabel()
    private val note = JLabel(" ")
    private val buttons = JPanel().apply { layout = BoxLayout(this, BoxLayout.Y_AXIS) }

    override fun start(model: TrayModel, onPrimary: () -> Unit, onQuit: () -> Unit): Boolean {
        this.onPrimary = onPrimary

        val header = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.X_AXIS)
            add(icon)
            add(Box.createHorizontalStrut(10))
            add(JPanel().apply {
                layout = BoxLayout(this, BoxLayout.Y_AXIS)
                add(status.apply { font = font.deriveFont(Font.BOLD) })
                add(note)
            })
        }

        val content = JPanel(BorderLayout(0, 12)).apply {
            border = BorderFactory.createEmptyBorder(12, 12, 12, 12)
            add(header, BorderLayout.NORTH)
            add(buttons, BorderLayout.CENTER)
        }

        SwingUtilities.invokeAndWait {
            frame = JFrame("VaultGuard").apply {
                iconImages = VaultIcon.windowIcons()
                defaultCloseOperation = WindowConstants.DO_NOTHING_ON_CLOSE
                addWindowListener(object : WindowAdapter() {
                    override fun windowClosing(e: WindowEvent) = onQuit()
                })
                contentPane.add(content)
                minimumSize = Dimension(320, 0)
                isResizable = false
            }
            render(model)
            frame.pack()
            frame.setLocationRelativeTo(null)
            frame.isVisible = true
        }
        return true
    }

    override fun render(model: TrayModel) = SwingUtilities.invokeLater {
        icon.icon = javax.swing.ImageIcon(VaultIcon.image(32, locked = model.locked))
        status.text = model.status
        buttons.removeAll()
        for (entry in model.entries) {
            when (entry) {
                is MenuEntry.Separator -> {
                    buttons.add(Box.createVerticalStrut(4))
                    buttons.add(JSeparator())
                    buttons.add(Box.createVerticalStrut(4))
                }
                is MenuEntry.Item -> buttons.add(JButton(entry.label).apply {
                    isEnabled = entry.enabled
                    alignmentX = java.awt.Component.LEFT_ALIGNMENT
                    maximumSize = Dimension(Int.MAX_VALUE, preferredSize.height)
                    addActionListener { entry.action() }
                })
            }
        }
        buttons.revalidate()
        buttons.repaint()
        if (::frame.isInitialized) frame.pack()
    }

    override fun notify(caption: String, text: String) = SwingUtilities.invokeLater {
        note.text = "$caption - $text"
        if (::frame.isInitialized) {
            frame.isVisible = true
            frame.toFront()
        }
    }

    override fun stop() {
        if (::frame.isInitialized) SwingUtilities.invokeLater { frame.dispose() }
    }
}
