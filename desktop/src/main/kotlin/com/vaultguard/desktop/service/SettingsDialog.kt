package com.vaultguard.desktop.service

import com.vaultguard.app.update.Release
import com.vaultguard.app.update.UpdateCheck
import com.vaultguard.desktop.cloud.BakedDefaults
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Font
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import java.awt.Insets
import javax.swing.BorderFactory
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.JButton
import javax.swing.JCheckBox
import javax.swing.JDialog
import javax.swing.JLabel
import javax.swing.JOptionPane
import javax.swing.JPanel
import javax.swing.JScrollPane
import javax.swing.JTextArea
import javax.swing.JTextField
import javax.swing.SwingUtilities

/**
 * Everything that used to need a terminal, in one window off the tray menu.
 *
 * Three things: start at login, register the browser extension, check the bridge. Each
 * reads its current state back from the machine (the Run key, the manifests, the
 * handshake) rather than from anything remembered, so the window is right after an
 * uninstall, a reinstall, or a `reg delete` done by hand.
 *
 * The work runs off the Swing thread; `reg` and `launchctl` are quick but not instant, and
 * a frozen checkbox reads as a broken one.
 */
class SettingsDialog(
    private val updater: Updater = Updater(),
    /** Quits the service; an installer cannot replace a running executable. */
    private val onQuit: () -> Unit = {}
) {

    private var dialog: JDialog? = null

    /** The last check's answer, shown when the window opens if the startup check found one. */
    @Volatile
    var pendingUpdate: Release? = null

    fun show() = SwingUtilities.invokeLater {
        dialog?.let { it.toFront(); return@invokeLater }

        val atLogin = JCheckBox("Start VaultGuard when I sign in to this computer")
        val firefoxStatus = JLabel()
        val firefoxButton = JButton("Register Firefox")
        val chromeId = JTextField(30)
        val chromeStatus = JLabel()
        val chromeButton = JButton("Register Chrome")
        val checkButton = JButton("Check the bridge")
        val output = JTextArea(6, 56).apply {
            isEditable = false
            font = Font(Font.MONOSPACED, Font.PLAIN, 12)
        }

        fun refresh() {
            Thread({
                val login = Setup.isAtLogin()
                val bridge = Setup.bridgeStatus()
                SwingUtilities.invokeLater {
                    atLogin.isSelected = login
                    firefoxStatus.text = if (bridge.firefox) "Registered" else "Not registered"
                    chromeStatus.text = bridge.chromeExtensionId?.let { "Registered for $it" } ?: "Not registered"
                    if (chromeId.text.isBlank()) chromeId.text = bridge.chromeExtensionId.orEmpty()
                }
            }, "vaultguard-settings-refresh").apply { isDaemon = true }.start()
        }

        fun busy(vararg controls: java.awt.Component, work: () -> Setup.Outcome) {
            controls.forEach { it.isEnabled = false }
            Thread({
                val outcome = work()
                SwingUtilities.invokeLater {
                    output.text = (outcome.lines + outcome.problems).joinToString("\n")
                    output.caretPosition = 0
                    if (!outcome.succeeded) {
                        JOptionPane.showMessageDialog(dialog, outcome.problems.joinToString("\n"), "VaultGuard", JOptionPane.ERROR_MESSAGE)
                    }
                    controls.forEach { it.isEnabled = true }
                    refresh()
                }
            }, "vaultguard-settings").apply { isDaemon = true }.start()
        }

        atLogin.addActionListener {
            val wanted = atLogin.isSelected
            busy(atLogin) { Setup.setAtLogin(wanted) }
        }
        firefoxButton.addActionListener {
            busy(firefoxButton, chromeButton) { Setup.registerBridge(chromeExtensionId = null) }
        }
        chromeButton.addActionListener {
            val id = chromeId.text.trim()
            if (id.isEmpty()) {
                JOptionPane.showMessageDialog(dialog, "Paste the extension id from chrome://extensions first.", "VaultGuard", JOptionPane.WARNING_MESSAGE)
                return@addActionListener
            }
            busy(firefoxButton, chromeButton) { Setup.registerBridge(id) }
        }
        checkButton.addActionListener {
            busy(checkButton) {
                val lines = mutableListOf<String>()
                BridgeCheck.run { lines += it }
                Setup.Outcome(lines, emptyList())
            }
        }

        val startup = section("Startup").apply { add(atLogin) }

        val browser = section("Browser extension").apply {
            add(JLabel("Firefox needs no id. Chrome shows its id on chrome://extensions once the extension is loaded."))
            add(Box.createVerticalStrut(6))
            add(JPanel(GridBagLayout()).apply {
                val c = GridBagConstraints().apply { insets = Insets(2, 0, 2, 8); anchor = GridBagConstraints.WEST }
                c.gridy = 0; c.gridx = 0; add(JLabel("Firefox"), c)
                c.gridx = 1; add(firefoxStatus, c)
                c.gridx = 2; add(firefoxButton, c)
                c.gridy = 1; c.gridx = 0; add(JLabel("Chrome"), c)
                c.gridx = 1; add(chromeId, c)
                c.gridx = 2; add(chromeButton, c)
                c.gridy = 2; c.gridx = 1; c.gridwidth = 2; add(chromeStatus, c)
            })
            add(Box.createVerticalStrut(6))
            add(JPanel(FlowLayout(FlowLayout.LEFT, 0, 0)).apply { add(checkButton) })
        }

        val checkAtStartup = JCheckBox("Check for updates when VaultGuard starts", updater.checkAtStartup)
        val updateStatus = JLabel(" ")
        val checkNow = JButton("Check now")
        val install = JButton(if (updater.installableAsset != null) "Install" else "Download").apply { isVisible = false }
        var available: Release? = null

        fun showUpdate(result: UpdateCheck.Result?) {
            when (result) {
                null -> Unit
                is UpdateCheck.Result.UpToDate -> { updateStatus.text = "Up to date."; install.isVisible = false }
                is UpdateCheck.Result.Failed -> { updateStatus.text = "Could not check: ${result.reason}"; install.isVisible = false }
                is UpdateCheck.Result.Available -> {
                    available = result.release
                    updateStatus.text = "VaultGuard ${result.release.version} is available."
                    install.isVisible = true
                }
            }
            dialog?.pack()
        }
        pendingUpdate?.let { showUpdate(UpdateCheck.Result.Available(com.vaultguard.app.update.Version.parse(BakedDefaults.version) ?: com.vaultguard.app.update.Version(0, 0, 0), it)) }

        checkAtStartup.addActionListener { updater.checkAtStartup = checkAtStartup.isSelected }
        checkNow.addActionListener {
            checkNow.isEnabled = false
            updateStatus.text = "Checking..."
            Thread({
                val result = updater.check()
                SwingUtilities.invokeLater { showUpdate(result); checkNow.isEnabled = true }
            }, "vaultguard-update-check").apply { isDaemon = true }.start()
        }
        install.addActionListener {
            val release = available ?: return@addActionListener
            if (updater.installableAsset == null) {
                updater.openReleasePage(release)
                return@addActionListener
            }
            install.isEnabled = false
            Thread({
                val prepared = updater.prepareInstall(release) { step -> SwingUtilities.invokeLater { updateStatus.text = step } }
                SwingUtilities.invokeLater {
                    install.isEnabled = true
                    when (prepared) {
                        is Updater.Install.Refused -> {
                            updateStatus.text = prepared.reason
                            JOptionPane.showMessageDialog(dialog, prepared.reason, "VaultGuard", JOptionPane.ERROR_MESSAGE)
                        }
                        is Updater.Install.Ready -> {
                            updateStatus.text = "Verified."
                            val go = JOptionPane.showConfirmDialog(
                                dialog,
                                "VaultGuard ${release.version} is downloaded and verified.\n" +
                                    "VaultGuard will quit and the installer will start.",
                                "VaultGuard",
                                JOptionPane.OK_CANCEL_OPTION, JOptionPane.QUESTION_MESSAGE
                            )
                            if (go == JOptionPane.OK_OPTION) {
                                runCatching { ProcessBuilder(prepared.command).start() }
                                    .onSuccess { onQuit() }
                                    .onFailure { JOptionPane.showMessageDialog(dialog, "Could not start the installer: ${it.message}", "VaultGuard", JOptionPane.ERROR_MESSAGE) }
                            }
                        }
                    }
                }
            }, "vaultguard-update-install").apply { isDaemon = true }.start()
        }

        val updates = section("Updates").apply {
            add(checkAtStartup)
            add(JPanel(FlowLayout(FlowLayout.LEFT, 0, 4)).apply {
                add(checkNow); add(Box.createHorizontalStrut(8)); add(install); add(Box.createHorizontalStrut(8)); add(updateStatus)
            })
            add(JLabel("Checks ask github.com for the latest release and send nothing about you."))
        }

        val about = section("This installation").apply {
            add(JLabel("Version ${BakedDefaults.version}"))
            add(JLabel("Firebase project: ${BakedDefaults.value("projectId") ?: "not configured"}"))
            add(JLabel("State and overrides: ${Setup.stateDirectory.path}"))
        }

        val content = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            border = BorderFactory.createEmptyBorder(12, 12, 12, 12)
            add(startup)
            add(Box.createVerticalStrut(12))
            add(browser)
            add(Box.createVerticalStrut(12))
            add(updates)
            add(Box.createVerticalStrut(12))
            add(about)
            add(Box.createVerticalStrut(12))
            add(JScrollPane(output).apply { preferredSize = Dimension(600, 120) })
        }

        val close = JButton("Close")
        val window = JDialog(null as java.awt.Frame?, "VaultGuard - Settings", false).apply {
            contentPane.add(content, BorderLayout.CENTER)
            contentPane.add(JPanel(FlowLayout(FlowLayout.RIGHT)).apply { add(close) }, BorderLayout.SOUTH)
            defaultCloseOperation = JDialog.DISPOSE_ON_CLOSE
            iconImages = VaultIcon.windowIcons()
            pack()
            setLocationRelativeTo(null)
        }
        close.addActionListener { window.dispose() }
        window.addWindowListener(object : java.awt.event.WindowAdapter() {
            override fun windowClosed(e: java.awt.event.WindowEvent) { dialog = null }
        })
        dialog = window
        refresh()
        window.isVisible = true
    }

    private fun section(title: String): JPanel = JPanel().apply {
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
        border = BorderFactory.createTitledBorder(title)
        alignmentX = java.awt.Component.LEFT_ALIGNMENT
    }
}
