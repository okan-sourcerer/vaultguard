package com.vaultguard.desktop.service

import java.awt.MenuItem
import java.awt.PopupMenu
import java.awt.SystemTray
import java.awt.TrayIcon
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.SwingUtilities

/**
 * The tray icon through `java.awt.SystemTray`: Windows, macOS, and the Linux desktops
 * that still speak XEmbed (KDE, XFCE, Cinnamon, MATE).
 *
 * The right-click menu is an AWT `PopupMenu`, which the operating system draws; no look
 * and feel reaches it, and a native menu is what a tray icon should have. It is rebuilt
 * from the model on every render rather than patched, because renders are rare and a
 * rebuild cannot get out of step.
 */
class AwtTrayFrontend : Frontend {

    private lateinit var trayIcon: TrayIcon
    private lateinit var onPrimary: () -> Unit
    private val statusItem = MenuItem().apply { isEnabled = false }

    override fun start(model: TrayModel, onPrimary: () -> Unit, onQuit: () -> Unit): Boolean {
        if (!SystemTray.isSupported()) return false
        this.onPrimary = onPrimary

        trayIcon = TrayIcon(VaultIcon.image(16, locked = model.locked), "VaultGuard", menu(model)).apply {
            isImageAutoSize = true
            // Double-click, and a click on a notification balloon, both arrive here.
            addActionListener { onPrimary() }
            // A single left-click does not; it is a mouse event. Right-click is the menu.
            addMouseListener(object : MouseAdapter() {
                override fun mouseClicked(e: MouseEvent) {
                    if (SwingUtilities.isLeftMouseButton(e) && e.clickCount == 1) onPrimary()
                }
            })
        }

        SystemTray.getSystemTray().add(trayIcon)
        render(model)
        return true
    }

    override fun render(model: TrayModel) = SwingUtilities.invokeLater {
        statusItem.label = model.status
        trayIcon.popupMenu = menu(model)
        trayIcon.image = VaultIcon.image(16, locked = model.locked)
        trayIcon.toolTip = "VaultGuard - ${model.status}"
    }

    override fun notify(caption: String, text: String) = SwingUtilities.invokeLater {
        trayIcon.displayMessage(caption, text, TrayIcon.MessageType.NONE)
    }

    override fun stop() {
        runCatching { SystemTray.getSystemTray().remove(trayIcon) }
    }

    private fun menu(model: TrayModel): PopupMenu = PopupMenu().apply {
        statusItem.label = model.status
        add(statusItem)
        addSeparator()
        for (entry in model.entries) {
            when (entry) {
                is MenuEntry.Separator -> addSeparator()
                is MenuEntry.Item -> add(MenuItem(entry.label).apply {
                    isEnabled = entry.enabled
                    addActionListener { entry.action() }
                })
            }
        }
    }
}
