package com.vaultguard.desktop.service

import com.vaultguard.app.domain.model.Credential
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.event.KeyAdapter
import java.awt.event.KeyEvent
import java.awt.event.WindowAdapter
import java.awt.event.WindowEvent
import javax.swing.BorderFactory
import javax.swing.DefaultListModel
import javax.swing.JButton
import javax.swing.JDialog
import javax.swing.JLabel
import javax.swing.JList
import javax.swing.JPanel
import javax.swing.JScrollPane
import javax.swing.JTextField
import javax.swing.ListSelectionModel
import javax.swing.SwingUtilities

/**
 * Find a credential and copy a field from it.
 *
 * Free-text search lives here rather than in the browser extension, deliberately. The
 * extension's `match` is bound to the tab's host, which is what stops a compromised
 * extension from enumerating the whole vault; adding a search action there would spend that
 * property permanently. The tray already holds the vault and is not reachable from a page,
 * so the same capability costs nothing here.
 *
 * The window shows names and usernames. A password is never rendered — it goes from the
 * vault to the clipboard without passing through a widget, because a Swing component keeps
 * its own copy of whatever it displays and there is no reliable way to take that back.
 */
class SearchDialog(
    private val credentials: () -> List<Credential>,
    private val clipboard: ClipboardGuard
) {
    private val dialog = JDialog(null as java.awt.Frame?, "VaultGuard", false)
    private val query = JTextField()
    private val model = DefaultListModel<Row>()
    private val list = JList(model)
    private val status = JLabel(" ")

    /** What the list holds: enough to show, plus the id to fetch by. */
    private class Row(val credential: Credential) {
        override fun toString(): String {
            val name = credential.displayName.ifEmpty { "(unnamed)" }
            val user = credential.username.ifEmpty { "no username" }
            return "$name  -  $user"
        }
    }

    fun show() {
        SwingUtilities.invokeLater {
            if (dialog.isVisible) {
                dialog.toFront()
                return@invokeLater
            }
            build()
            refill("")
            dialog.isVisible = true
            query.requestFocusInWindow()
        }
    }

    private fun build() {
        if (dialog.contentPane.componentCount > 0) return

        list.selectionMode = ListSelectionModel.SINGLE_SELECTION
        list.visibleRowCount = 12

        query.addKeyListener(object : KeyAdapter() {
            override fun keyReleased(e: KeyEvent) {
                if (e.keyCode == KeyEvent.VK_ESCAPE) {
                    close()
                    return
                }
                refill(query.text)
            }
        })

        val copyPassword = JButton("Copy password").apply { addActionListener { copy(password = true) } }
        val copyUsername = JButton("Copy username").apply { addActionListener { copy(password = false) } }
        val close = JButton("Close").apply { addActionListener { close() } }

        // Enter on the list copies the password, which is what anyone reaching for this
        // wants nine times in ten.
        list.addKeyListener(object : KeyAdapter() {
            override fun keyPressed(e: KeyEvent) {
                when (e.keyCode) {
                    KeyEvent.VK_ENTER -> copy(password = true)
                    KeyEvent.VK_ESCAPE -> close()
                }
            }
        })

        val buttons = JPanel(FlowLayout(FlowLayout.RIGHT)).apply {
            add(copyUsername)
            add(copyPassword)
            add(close)
        }

        val top = JPanel(BorderLayout(0, 6)).apply {
            border = BorderFactory.createEmptyBorder(10, 10, 4, 10)
            add(JLabel("Search"), BorderLayout.NORTH)
            add(query, BorderLayout.CENTER)
        }

        val bottom = JPanel(BorderLayout()).apply {
            border = BorderFactory.createEmptyBorder(0, 10, 8, 10)
            add(status, BorderLayout.WEST)
            add(buttons, BorderLayout.EAST)
        }

        dialog.contentPane.layout = BorderLayout()
        dialog.contentPane.add(top, BorderLayout.NORTH)
        dialog.contentPane.add(
            JScrollPane(list).apply {
                border = BorderFactory.createEmptyBorder(0, 10, 0, 10)
            },
            BorderLayout.CENTER
        )
        dialog.contentPane.add(bottom, BorderLayout.SOUTH)

        dialog.defaultCloseOperation = JDialog.HIDE_ON_CLOSE
        dialog.addWindowListener(object : WindowAdapter() {
            override fun windowClosing(e: WindowEvent) = forget()
        })

        // Otherwise the window, alt-tab and the taskbar all show the default Java icon.
        dialog.iconImages = VaultIcon.windowIcons(locked = false)

        dialog.minimumSize = Dimension(430, 340)
        dialog.pack()
        dialog.setLocationRelativeTo(null)
    }

    private fun refill(text: String) {
        val needle = text.trim().lowercase()
        val matches = credentials().filter { credential ->
            needle.isEmpty() || listOf(
                credential.displayName, credential.username, credential.url, credential.siteName
            ).any { it.lowercase().contains(needle) }
        }

        model.clear()
        matches.forEach { model.addElement(Row(it)) }
        if (model.size() > 0) list.selectedIndex = 0

        status.text = when {
            matches.isEmpty() && needle.isEmpty() -> "Vault is empty"
            matches.isEmpty() -> "No match"
            else -> "${matches.size} shown"
        }
    }

    private fun copy(password: Boolean) {
        val row = list.selectedValue ?: run {
            status.text = "Nothing selected"
            return
        }

        val value = if (password) row.credential.password else row.credential.username
        if (value.isEmpty()) {
            status.text = if (password) "That entry has no password" else "That entry has no username"
            return
        }

        val seconds = clipboard.copy(value)
        status.text = buildString {
            append(if (password) "Password" else "Username")
            append(" copied - clears in ").append(seconds).append("s")
        }
    }

    private fun close() {
        dialog.isVisible = false
        forget()
    }

    /**
     * Empties the window when it goes away.
     *
     * The list holds decrypted credentials, and a hidden dialog is still a live object. This
     * does not make anything unrecoverable — the vault is unlocked and the strings are in
     * the heap regardless — but it keeps the window from being a second, longer-lived copy.
     */
    private fun forget() {
        model.clear()
        query.text = ""
        status.text = " "
    }
}
