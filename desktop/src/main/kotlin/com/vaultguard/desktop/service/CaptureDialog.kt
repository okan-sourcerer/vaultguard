package com.vaultguard.desktop.service

import com.vaultguard.app.autofill.CredentialMatcher
import com.vaultguard.app.autofill.SaveDecision
import com.vaultguard.app.domain.model.Credential
import java.awt.BorderLayout
import java.awt.FlowLayout
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import java.awt.Insets
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import javax.swing.BorderFactory
import javax.swing.JButton
import javax.swing.JDialog
import javax.swing.JLabel
import javax.swing.JOptionPane
import javax.swing.JPanel
import javax.swing.JTextField
import javax.swing.SwingUtilities

/**
 * "Save this login?" for a password the browser extension saw being submitted.
 *
 * The decision is [SaveDecision]'s - the same code the phone's autofill uses - made
 * against the open vault: already saved means nothing appears; a changed password
 * offers an update; the same account saved for another site offers to add this one to
 * it; otherwise a new entry. The user confirms here, in a VaultGuard window, and the
 * browser learns nothing of the outcome.
 *
 * *Not now* is remembered for the host until the service restarts. There is no
 * permanent dismissal on purpose: the phone's thirty-day one exists because Android's
 * prompt is hard to avoid, while here a wrong password is simply not saved.
 *
 * The password crosses this window as a String, as it does in [CredentialDialog], because
 * [Credential] holds one; the field showing it is a plain text field with the value
 * masked, never a JPasswordField the user can reveal - there is nothing to reveal that the
 * user did not just type in the browser.
 */
class CaptureDialog(
    private val service: VaultService,
    private val onSaved: () -> Unit
) {
    private val declined = ConcurrentHashMap.newKeySet<String>()

    /** Current window and the capture it shows, so a newer capture for the same host replaces it. */
    private var current: Pair<JDialog, String>? = null

    /** Off the Swing thread: decides, then shows if there is something to ask. */
    fun offer(host: String, username: String, password: String) {
        if (host in declined) return
        if (service.state != ServiceState.UNLOCKED) return

        val everything = service.credentials()
        val known = CredentialMatcher.match(everything, webDomain = host, packageName = null)
        val decision = SaveDecision.decide(username, password, known, everything)
        if (decision == SaveDecision.Outcome.Ignore) return

        SwingUtilities.invokeLater { show(host, username, password, decision, everything) }
    }

    private fun show(host: String, username: String, password: String, decision: SaveDecision.Outcome, everything: List<Credential>) {
        current?.let { (dialog, forHost) ->
            if (forHost == host) dialog.dispose() else { dialog.toFront(); return }
        }

        val title: String
        val explanation: String
        val confirmLabel: String
        val name = JTextField(host.removePrefix("www."), 24)
        val user = JTextField(username, 24)
        val existing: Credential? = when (decision) {
            is SaveDecision.Outcome.UpdateExisting -> everything.firstOrNull { it.id == decision.id }
            is SaveDecision.Outcome.LinkExisting -> everything.firstOrNull { it.id == decision.id }
            else -> null
        }
        when (decision) {
            is SaveDecision.Outcome.UpdateExisting -> {
                title = "Password changed?"
                explanation = "The password just used on $host is not the one saved for " +
                    "${existing?.displayName ?: host}. Replace the saved one?"
                confirmLabel = "Update"
            }
            is SaveDecision.Outcome.LinkExisting -> {
                title = "Same account?"
                explanation = "${existing?.displayName ?: "An entry"} already has this username and " +
                    "password. Add $host to it, so it fills there too?"
                confirmLabel = "Add to ${existing?.displayName ?: "entry"}"
            }
            else -> {
                title = "Save this login?"
                explanation = "VaultGuard can save the login you just used on $host."
                confirmLabel = "Save"
            }
        }

        val form = JPanel(GridBagLayout()).apply {
            border = BorderFactory.createEmptyBorder(12, 12, 4, 12)
            val c = GridBagConstraints().apply { insets = Insets(3, 3, 3, 3); anchor = GridBagConstraints.WEST }
            c.gridx = 0; c.gridy = 0; c.gridwidth = 2
            add(JLabel("<html><body style='width: 320px'>$explanation</body></html>"), c)
            c.gridwidth = 1
            if (decision is SaveDecision.Outcome.CreateNew) {
                c.gridy = 1; c.gridx = 0; add(JLabel("Name"), c); c.gridx = 1; add(name, c)
                c.gridy = 2; c.gridx = 0; add(JLabel("Username"), c); c.gridx = 1; add(user, c)
            }
            c.gridy = 3; c.gridx = 0; add(JLabel("Password"), c)
            c.gridx = 1; add(JLabel("•".repeat(password.length.coerceIn(6, 16))), c)
        }

        val confirm = JButton(confirmLabel)
        val notNow = JButton("Not now")
        val dialog = JDialog(null as java.awt.Frame?, "VaultGuard - $title", false).apply {
            contentPane.add(form, BorderLayout.CENTER)
            contentPane.add(JPanel(FlowLayout(FlowLayout.RIGHT)).apply { add(notNow); add(confirm) }, BorderLayout.SOUTH)
            defaultCloseOperation = JDialog.DISPOSE_ON_CLOSE
            iconImages = VaultIcon.windowIcons()
            isAlwaysOnTop = true
            pack()
            setLocationRelativeTo(null)
        }
        dialog.addWindowListener(object : java.awt.event.WindowAdapter() {
            override fun windowClosed(e: java.awt.event.WindowEvent) { if (current?.first === dialog) current = null }
        })
        current = dialog to host

        notNow.addActionListener { declined += host; dialog.dispose() }
        confirm.addActionListener {
            confirm.isEnabled = false
            val now = System.currentTimeMillis()
            val credential = when (decision) {
                is SaveDecision.Outcome.UpdateExisting -> existing?.copy(password = password, passwordChangedAt = now, updatedAt = now, contentChangedAt = now)
                is SaveDecision.Outcome.LinkExisting -> existing?.copy(
                    linkedDomains = (existing.linkedDomains + host).distinct(), updatedAt = now, contentChangedAt = now
                )
                else -> Credential(
                    id = UUID.randomUUID().toString(),
                    siteName = name.text.trim().ifEmpty { host },
                    username = user.text.trim(),
                    url = "https://$host",
                    password = password,
                    createdAt = now, updatedAt = now, passwordChangedAt = now, contentChangedAt = now
                )
            }
            if (credential == null) { dialog.dispose(); return@addActionListener }
            Thread({
                val outcome = if (decision is SaveDecision.Outcome.CreateNew) service.create(credential) else service.update(credential)
                SwingUtilities.invokeLater {
                    if (outcome.ok) {
                        dialog.dispose()
                        onSaved()
                    } else {
                        confirm.isEnabled = true
                        JOptionPane.showMessageDialog(dialog, outcome.message, "VaultGuard", JOptionPane.ERROR_MESSAGE)
                    }
                }
            }, "vaultguard-capture-save").apply { isDaemon = true }.start()
        }

        dialog.isVisible = true
    }
}
