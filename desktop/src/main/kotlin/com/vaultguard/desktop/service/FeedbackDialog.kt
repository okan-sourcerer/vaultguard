package com.vaultguard.desktop.service

import com.vaultguard.app.feedback.FeedbackClient
import com.vaultguard.app.feedback.FeedbackReport
import com.vaultguard.desktop.cloud.BakedDefaults
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Font
import java.util.UUID
import javax.swing.BorderFactory
import javax.swing.BoxLayout
import javax.swing.JButton
import javax.swing.JComboBox
import javax.swing.JDialog
import javax.swing.JLabel
import javax.swing.JOptionPane
import javax.swing.JPanel
import javax.swing.JScrollPane
import javax.swing.JTextArea
import javax.swing.SwingUtilities
import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener

/**
 * "Send feedback..." from the tray: a type, a message, and the exact JSON that will be
 * posted, updated as the user types. Same [FeedbackReport] as the phone builds, so the two
 * cannot disagree about what leaves the machine.
 *
 * Talks to nothing in the vault. It does not need the vault unlocked and does not ask.
 */
class FeedbackDialog(
    private val client: FeedbackClient = FeedbackClient(
        BakedDefaults.value("feedbackUrl").orEmpty(),
        BakedDefaults.value("feedbackKey").orEmpty()
    ),
    private val version: String = BakedDefaults.version,
    private val environment: FeedbackReport.Environment = FeedbackReport.Environment.PROD
) {
    val isAvailable: Boolean get() = client.isConfigured

    private var dialog: JDialog? = null

    fun show() = SwingUtilities.invokeLater {
        dialog?.let { it.toFront(); return@invokeLater }

        val idempotencyKey = UUID.randomUUID().toString()
        val type = JComboBox(FeedbackReport.Type.entries.toTypedArray()).apply {
            renderer = object : javax.swing.DefaultListCellRenderer() {
                override fun getListCellRendererComponent(
                    list: javax.swing.JList<*>?, value: Any?, index: Int, isSelected: Boolean, cellHasFocus: Boolean
                ) = super.getListCellRendererComponent(
                    list, (value as? FeedbackReport.Type)?.label ?: value, index, isSelected, cellHasFocus
                )
            }
        }
        val message = JTextArea(8, 48).apply { lineWrap = true; wrapStyleWord = true }
        val preview = JTextArea(12, 48).apply {
            isEditable = false
            font = Font(Font.MONOSPACED, Font.PLAIN, 12)
        }
        val send = JButton("Send").apply { isEnabled = false }
        val cancel = JButton("Cancel")

        fun report() = FeedbackReport(
            type = type.selectedItem as FeedbackReport.Type,
            message = message.text.trim().take(FeedbackReport.MAX_MESSAGE_LENGTH),
            appVersion = version,
            environment = environment,
            platform = FeedbackReport.Platform.DESKTOP,
            os = "${System.getProperty("os.name")} ${System.getProperty("os.version")}",
            idempotencyKey = idempotencyKey
        )

        fun refresh() {
            preview.text = report().toJson().toString(2)
            preview.caretPosition = 0
            send.isEnabled = message.text.isNotBlank()
        }
        message.document.addDocumentListener(object : DocumentListener {
            override fun insertUpdate(e: DocumentEvent) = refresh()
            override fun removeUpdate(e: DocumentEvent) = refresh()
            override fun changedUpdate(e: DocumentEvent) = refresh()
        })
        type.addActionListener { refresh() }
        refresh()

        val form = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            border = BorderFactory.createEmptyBorder(12, 12, 12, 12)
            add(JLabel("What happened, or what would help?"))
            add(JPanel(FlowLayout(FlowLayout.LEFT, 0, 4)).apply { add(type) })
            add(JScrollPane(message))
            add(JLabel("Do not include a password or a site you would rather not name."))
            add(javax.swing.Box.createVerticalStrut(12))
            add(JLabel("What will be sent - this and nothing else. No log, no account, no vault contents."))
            add(JScrollPane(preview).apply { preferredSize = Dimension(560, 220) })
        }
        val buttons = JPanel(FlowLayout(FlowLayout.RIGHT)).apply { add(cancel); add(send) }

        val window = JDialog(null as java.awt.Frame?, "VaultGuard - Send feedback", false).apply {
            contentPane.add(form, BorderLayout.CENTER)
            contentPane.add(buttons, BorderLayout.SOUTH)
            defaultCloseOperation = JDialog.DISPOSE_ON_CLOSE
            iconImages = VaultIcon.windowIcons()
            pack()
            setLocationRelativeTo(null)
        }
        dialog = window
        window.addWindowListener(object : java.awt.event.WindowAdapter() {
            override fun windowClosed(e: java.awt.event.WindowEvent) { dialog = null }
        })

        cancel.addActionListener { window.dispose() }
        send.addActionListener {
            send.isEnabled = false
            cancel.isEnabled = false
            val toSend = report()
            Thread({
                val result = client.send(toSend)
                SwingUtilities.invokeLater {
                    when (result) {
                        is FeedbackClient.Result.Sent -> {
                            window.dispose()
                            JOptionPane.showMessageDialog(null, "Thanks - your feedback was sent.", "VaultGuard", JOptionPane.INFORMATION_MESSAGE)
                        }
                        is FeedbackClient.Result.Rejected -> fail(window, send, cancel, result.detail)
                        is FeedbackClient.Result.Failed -> fail(window, send, cancel, "Could not reach the feedback hub: ${result.reason}")
                    }
                }
            }, "vaultguard-feedback").apply { isDaemon = true }.start()
        }

        window.isVisible = true
    }

    /** Leaves the dialog open with the text intact; the same key makes a retry harmless. */
    private fun fail(window: JDialog, send: JButton, cancel: JButton, detail: String) {
        JOptionPane.showMessageDialog(window, detail, "VaultGuard", JOptionPane.ERROR_MESSAGE)
        send.isEnabled = true
        cancel.isEnabled = true
    }
}
