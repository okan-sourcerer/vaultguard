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
import javax.swing.JOptionPane
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
    private val service: VaultService,
    private val clipboard: ClipboardGuard
) {
    private val credentials: () -> List<Credential> = { service.credentials() }
    private val editor = CredentialDialog(service) { SwingUtilities.invokeLater { refill(query.text) } }
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

    /**
     * Revokes this window's view of decrypted rows when the vault locks.
     *
     * Hiding a Swing dialog is not enough: its list model remains live and the copy buttons
     * can still read the credentials held by its rows if the window is brought back. This is
     * deliberately safe to call from the bridge or auto-lock threads.
     */
    fun closeForLock() {
        SwingUtilities.invokeLater {
            editor.closeForLock()
            dialog.isVisible = false
            forget()
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

        val add = JButton("New").apply { addActionListener { editor.createNew(dialog) } }
        val edit = JButton("Edit").apply {
            addActionListener {
                val row = list.selectedValue ?: return@addActionListener run { status.text = "Nothing selected" }
                editor.edit(dialog, row.credential)
            }
        }
        val remove = JButton("Delete").apply { addActionListener { delete() } }

        val fromWindow = JButton("From window...").apply {
            isEnabled = WindowList.isSupported
            toolTipText = if (WindowList.isSupported) {
                "Filter by an application you have open"
            } else {
                "Only available on Windows so far"
            }
            addActionListener { pickWindow() }
        }

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

        val manage = JPanel(FlowLayout(FlowLayout.LEFT, 4, 0)).apply {
            add(fromWindow)
            add(add)
            add(edit)
            add(remove)
        }

        val top = JPanel(BorderLayout(0, 6)).apply {
            border = BorderFactory.createEmptyBorder(10, 10, 4, 10)
            add(JLabel("Search"), BorderLayout.NORTH)
            add(query, BorderLayout.CENTER)
            add(manage, BorderLayout.SOUTH)
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

    private fun delete() {
        val row = list.selectedValue ?: run {
            status.text = "Nothing selected"
            return
        }

        // Said plainly, because "delete" reads as final and this one is not.
        val confirmed = JOptionPane.showConfirmDialog(
            dialog,
            "Delete \"${row.credential.displayName}\"?\n\n" +
                "This marks it deleted and syncs that to the phone, where it can still be undone.",
            "VaultGuard",
            JOptionPane.YES_NO_OPTION,
            JOptionPane.WARNING_MESSAGE
        )
        if (confirmed != JOptionPane.YES_OPTION) return

        val outcome = service.delete(row.credential.id)
        status.text = outcome.message
        if (outcome.ok) refill(query.text)
    }

    /**
     * Filters by an application the user has open.
     *
     * They pick; nothing watches. A service that noticed launches by itself would be keeping
     * a record of what you run, for a feature that works just as well on request.
     */
    private fun pickWindow() {
        status.text = "Listing windows..."

        // PowerShell takes a moment, and the event thread is drawing this dialog.
        Thread({
            val windows = WindowList.list()
            SwingUtilities.invokeLater {
                if (windows.isEmpty()) {
                    status.text = "No windows found"
                    return@invokeLater
                }

                val chosen = JOptionPane.showInputDialog(
                    dialog,
                    "Filter by which application?",
                    "VaultGuard",
                    JOptionPane.PLAIN_MESSAGE,
                    null,
                    windows.toTypedArray(),
                    windows.first()
                ) as? OpenWindow ?: return@invokeLater

                query.text = chosen.searchTerm
                refill(chosen.searchTerm)
            }
        }, "vaultguard-window-list").apply { isDaemon = true }.start()
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
