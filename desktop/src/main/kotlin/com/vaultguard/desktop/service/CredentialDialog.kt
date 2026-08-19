package com.vaultguard.desktop.service

import com.vaultguard.app.domain.model.Credential
import com.vaultguard.app.domain.model.PasswordGeneratorConfig
import com.vaultguard.app.domain.usecase.GeneratePasswordUseCase
import com.vaultguard.app.util.PasswordStrengthEvaluator
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import java.awt.Insets
import java.util.UUID
import javax.swing.BorderFactory
import javax.swing.JButton
import javax.swing.JDialog
import javax.swing.JLabel
import javax.swing.JOptionPane
import javax.swing.JPanel
import javax.swing.JPasswordField
import javax.swing.JTextField
import javax.swing.SwingUtilities

/**
 * Create or change one credential.
 *
 * The password field is a [JPasswordField] whether it is being typed or generated, so the
 * value is never on screen and the component hands it back as a `CharArray`. It becomes a
 * `String` only at the last moment, because `Credential` holds one — a limitation of the
 * shared model rather than of this dialog.
 *
 * Nothing here talks to Firestore. It hands a [Credential] to [VaultService], which owns the
 * version checks and the refresh that follows.
 */
class CredentialDialog(
    private val service: VaultService,
    private val onSaved: () -> Unit
) {
    private val generator = GeneratePasswordUseCase()
    private val evaluator = PasswordStrengthEvaluator()

    private val name = JTextField(26)
    private val username = JTextField(26)
    private val url = JTextField(26)
    private val password = JPasswordField(26)
    private val strength = JLabel(" ")

    /** Null when creating; the entry being changed otherwise. */
    private var editing: Credential? = null

    fun createNew(owner: JDialog?) = show(owner, null)

    fun edit(owner: JDialog?, credential: Credential) = show(owner, credential)

    private fun show(owner: JDialog?, credential: Credential?) {
        SwingUtilities.invokeLater {
            editing = credential

            name.text = credential?.siteName.orEmpty()
            username.text = credential?.username.orEmpty()
            url.text = credential?.url.orEmpty()
            password.text = credential?.password.orEmpty()
            describe()

            val dialog = JDialog(owner, if (credential == null) "New entry" else "Edit entry", true)
            dialog.iconImages = VaultIcon.windowIcons(locked = false)
            dialog.contentPane.add(buildForm(dialog), BorderLayout.CENTER)
            dialog.minimumSize = Dimension(430, 240)
            dialog.pack()
            dialog.setLocationRelativeTo(owner)
            dialog.isVisible = true

            // The dialog is modal, so this runs once it closes. The fields outlive it as
            // members, and they hold a password.
            clearFields()
        }
    }

    private fun buildForm(dialog: JDialog): JPanel {
        val fields = JPanel(GridBagLayout())
        val c = GridBagConstraints().apply {
            insets = Insets(4, 6, 4, 6)
            anchor = GridBagConstraints.WEST
            fill = GridBagConstraints.HORIZONTAL
        }

        fun row(y: Int, label: String, field: java.awt.Component) {
            c.gridx = 0; c.gridy = y; c.weightx = 0.0
            fields.add(JLabel(label), c)
            c.gridx = 1; c.weightx = 1.0
            fields.add(field, c)
        }

        row(0, "Name", name)
        row(1, "Username", username)
        row(2, "URL", url)
        row(3, "Password", password)

        c.gridx = 1; c.gridy = 4
        fields.add(strength, c)

        password.addCaretListener { describe() }

        val generate = JButton("Generate").apply {
            addActionListener {
                password.text = generator(PasswordGeneratorConfig())
                describe()
            }
        }

        val save = JButton("Save").apply {
            addActionListener { if (save(dialog)) dialog.dispose() }
        }
        val cancel = JButton("Cancel").apply { addActionListener { dialog.dispose() } }

        val buttons = JPanel(FlowLayout(FlowLayout.RIGHT)).apply {
            add(generate)
            add(cancel)
            add(save)
        }

        return JPanel(BorderLayout()).apply {
            border = BorderFactory.createEmptyBorder(10, 10, 8, 10)
            add(fields, BorderLayout.CENTER)
            add(buttons, BorderLayout.SOUTH)
        }
    }

    private fun describe() {
        val value = String(password.password)
        if (value.isEmpty()) {
            strength.text = " "
            return
        }
        val result = evaluator(value)
        strength.text = buildString {
            append(result.level).append(" (").append(result.entropy.toInt()).append(" bits)")
            if (result.isCommon) append(" - a known-common password")
        }
    }

    private fun save(dialog: JDialog): Boolean {
        val siteName = name.text.trim()
        if (siteName.isEmpty()) {
            JOptionPane.showMessageDialog(dialog, "A name is required.", "VaultGuard", JOptionPane.WARNING_MESSAGE)
            return false
        }

        val secret = String(password.password)
        if (secret.isEmpty()) {
            JOptionPane.showMessageDialog(dialog, "A password is required.", "VaultGuard", JOptionPane.WARNING_MESSAGE)
            return false
        }

        val now = System.currentTimeMillis()
        val existing = editing

        val credential = if (existing == null) {
            Credential(
                id = UUID.randomUUID().toString(),
                siteName = siteName,
                username = username.text.trim(),
                url = url.text.trim(),
                password = secret,
                createdAt = now,
                updatedAt = now,
                passwordChangedAt = now,
                contentChangedAt = now
            )
        } else {
            existing.copy(
                siteName = siteName,
                username = username.text.trim(),
                url = url.text.trim(),
                password = secret,
                updatedAt = now,
                // Only when the password itself moved. Bumping it on every edit makes the
                // phone's rotation prompt meaningless, which is what #29 separated the two
                // clocks for.
                passwordChangedAt = if (secret != existing.password) now else existing.passwordChangedAt,
                contentChangedAt = now
            )
        }

        val outcome = if (existing == null) service.create(credential) else service.update(credential)

        if (!outcome.ok) {
            JOptionPane.showMessageDialog(dialog, outcome.message, "VaultGuard", JOptionPane.ERROR_MESSAGE)
            return false
        }

        onSaved()
        return true
    }

    private fun clearFields() {
        name.text = ""
        username.text = ""
        url.text = ""
        // JPasswordField keeps its own copy of the characters; setting it empty is what
        // releases them.
        password.text = ""
        strength.text = " "
        editing = null
    }
}
