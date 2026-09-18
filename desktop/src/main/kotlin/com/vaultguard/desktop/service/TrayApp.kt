package com.vaultguard.desktop.service

import com.vaultguard.desktop.cloud.DesktopConfig
import java.awt.MenuItem
import java.awt.PopupMenu
import java.awt.SystemTray
import java.awt.TrayIcon
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.swing.JOptionPane
import javax.swing.JPasswordField
import javax.swing.SwingUtilities

/**
 * The tray icon: a thin shell over [VaultService].
 *
 * Thin on purpose. `java.awt.SystemTray` speaks the XEmbed tray protocol, which Windows and
 * macOS provide and which GNOME removed in 3.26 — so on the most common Linux desktop this
 * class cannot draw anything, while everything it drives works fine. Keeping all the state
 * and all the decisions in [VaultService] means supporting those desktops later is a
 * replacement for this file, not a rewrite.
 *
 * Nothing here holds a key, a password or a credential.
 */
class TrayApp(
    private val service: VaultService,
    private val bridge: BridgeServer = BridgeServer(service),
    private val clipboard: ClipboardGuard = ClipboardGuard()
) {

    private val search = SearchDialog(service, clipboard)
    private val feedback = FeedbackDialog()
    private val settings = SettingsDialog()

    private lateinit var trayIcon: TrayIcon
    private val statusItem = MenuItem("Starting...")
    private val searchItem = MenuItem("Search...")
    private val unlockItem = MenuItem("Unlock...")
    private val refreshItem = MenuItem("Refresh")
    private val lockItem = MenuItem("Lock")
    private val signOutItem = MenuItem("Sign out")

    private val worker = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "vaultguard-tray-worker").apply { isDaemon = true }
    }
    private val ticker = Executors.newSingleThreadScheduledExecutor { runnable ->
        Thread(runnable, "vaultguard-autolock").apply { isDaemon = true }
    }

    fun start(): Boolean {
        if (!SystemTray.isSupported()) {
            System.err.println(
                "This desktop has no system tray that Java can draw into.\n" +
                    "On GNOME that is expected: the tray protocol Java uses was removed in 3.26.\n" +
                    "Use `vaultguard --cloud` instead."
            )
            return false
        }

        val menu = PopupMenu().apply {
            statusItem.isEnabled = false
            add(statusItem)
            addSeparator()
            add(searchItem)
            add(MenuItem("Settings...").apply { addActionListener { settings.show() } })
            addSeparator()
            add(unlockItem)
            add(refreshItem)
            add(lockItem)
            add(signOutItem)
            addSeparator()
            // Only when the build knows a hub; a menu item that always fails is worse than none.
            if (feedback.isAvailable) {
                add(MenuItem("Send feedback...").apply { addActionListener { feedback.show() } })
            }
            add(MenuItem("Quit").apply { addActionListener { quit() } })
        }

        trayIcon = TrayIcon(VaultIcon.image(16, locked = true), "VaultGuard", menu).apply {
            isImageAutoSize = true
        }

        searchItem.addActionListener { openSearch() }
        unlockItem.addActionListener { worker.submit { unlock() } }
        refreshItem.addActionListener { worker.submit { refresh() } }
        lockItem.addActionListener { worker.submit { lockNow() } }
        signOutItem.addActionListener { worker.submit { signOut() } }

        // Started before the icon appears, so a browser that is already open finds the
        // bridge the moment the tray does.
        runCatching { bridge.start() }
            .onFailure { System.err.println("Could not open the browser bridge: ${it.message}") }

        SystemTray.getSystemTray().add(trayIcon)
        service.onStateChanged = { render() }
        render()

        // A minute is fine: the policy decides, this only asks. Checking every second would
        // wake the process 60 times as often to learn the same thing.
        ticker.scheduleAtFixedRate({ tick() }, 1, 1, TimeUnit.MINUTES)

        if (service.state == ServiceState.SIGNED_OUT) {
            notify("VaultGuard is running", "Right-click the tray icon and choose Sign in...")
        } else {
            notify("VaultGuard is running", "Right-click the tray icon to unlock.")
        }

        // A fresh install is otherwise a tray icon and nothing else. Once: the marker is
        // the only thing this writes, and Settings is reachable from the menu after.
        val firstRun = File(Setup.stateDirectory, "first-run-done")
        if (!firstRun.exists()) {
            runCatching { firstRun.parentFile.mkdirs(); firstRun.writeText("") }
            settings.show()
        }
        return true
    }

    private fun openSearch() {
        if (service.state != ServiceState.UNLOCKED) {
            SwingUtilities.invokeLater { error("Unlock the vault first.") }
            return
        }
        // credentials() counts as use, so a search keeps the auto-lock at bay while the
        // window is being driven.
        search.show()
    }

    private fun tick() {
        if (service.lockIfIdle()) {
            notify("Vault locked", "It had been idle. Unlock from the tray when you need it.")
        }
    }

    private fun unlock() {
        val opened = service.unlock(
            askPassword = { label -> askPassword(label) },
            say = { status(it) },
            warn = { message -> SwingUtilities.invokeLater { error(message) } }
        )
        if (opened) notify("Vault unlocked", "${service.entryCount} entries available.")
        render()
    }

    private fun refresh() {
        if (service.state != ServiceState.UNLOCKED) {
            SwingUtilities.invokeLater { error("Unlock first.") }
            return
        }
        status("Refreshing...")
        val ok = service.refresh { message -> SwingUtilities.invokeLater { error(message) } }
        if (ok) notify("Refreshed", "${service.entryCount} entries.")
        render()
    }

    private fun lockNow() {
        service.lock()
        notify("Vault locked", "The key is out of memory.")
    }

    private fun signOut() {
        service.signOut()
        notify("Signed out", "The saved sign-in has been forgotten.")
    }

    private fun quit() {
        service.lock()
        // Takes back anything still on the clipboard from this session, unless the user has
        // copied something else since.
        clipboard.shutdown()
        bridge.stop()
        runCatching { SystemTray.getSystemTray().remove(trayIcon) }
        worker.shutdownNow()
        ticker.shutdownNow()
        kotlin.system.exitProcess(0)
    }

    // -- Presentation ---------------------------------------------------------------------

    private fun render() = SwingUtilities.invokeLater {
        val state = service.state
        statusItem.label = when (state) {
            ServiceState.SIGNED_OUT -> "Signed out"
            ServiceState.LOCKED -> "Locked"
            ServiceState.UNLOCKED -> buildString {
                append("Unlocked - ").append(service.entryCount).append(" entries")
                if (service.undecryptableCount > 0) {
                    append(" (").append(service.undecryptableCount).append(" unreadable)")
                }
            }
        }

        unlockItem.label = if (state == ServiceState.SIGNED_OUT) "Sign in..." else "Unlock..."
        unlockItem.isEnabled = state != ServiceState.UNLOCKED
        searchItem.isEnabled = state == ServiceState.UNLOCKED
        refreshItem.isEnabled = state == ServiceState.UNLOCKED
        lockItem.isEnabled = state == ServiceState.UNLOCKED
        signOutItem.isEnabled = state != ServiceState.SIGNED_OUT

        trayIcon.image = VaultIcon.image(16, locked = state != ServiceState.UNLOCKED)
        trayIcon.toolTip = "VaultGuard - ${statusItem.label}"
    }

    private fun status(message: String) = SwingUtilities.invokeLater {
        trayIcon.toolTip = "VaultGuard - $message"
    }

    private fun notify(caption: String, text: String) = SwingUtilities.invokeLater {
        trayIcon.displayMessage(caption, text, TrayIcon.MessageType.NONE)
    }

    private fun error(message: String) {
        JOptionPane.showMessageDialog(null, message, "VaultGuard", JOptionPane.ERROR_MESSAGE)
    }

    /**
     * Asks on the Swing thread and returns a `CharArray`, never a `String`.
     *
     * `JPasswordField.getPassword()` exists for this reason: a `String` cannot be cleared
     * and sits in the heap until it is collected. The array is handed straight to
     * `KeyDerivation`, which zeroes it.
     */
    private fun askPassword(label: String): CharArray? {
        val field = JPasswordField(24)
        val result = arrayOfNulls<CharArray>(1)

        val show = Runnable {
            val option = JOptionPane.showConfirmDialog(
                null, field, "VaultGuard - $label",
                JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE
            )
            result[0] = if (option == JOptionPane.OK_OPTION) field.password else null
            // Not merely a courtesy: the field keeps its own copy of the characters.
            field.text = ""
        }

        if (SwingUtilities.isEventDispatchThread()) show.run() else SwingUtilities.invokeAndWait(show)

        return result[0]?.takeIf { it.isNotEmpty() }
    }

}

/** Entry point for `vaultguard --service`. */
fun runTrayService() {
    val config = try {
        DesktopConfig.load()
    } catch (e: DesktopConfig.Companion.MissingConfigException) {
        System.err.println(e.message)
        return
    }

    // Without this a tray-only process can still be treated as headless and fail to draw.
    System.setProperty("java.awt.headless", "false")
    Theme.apply()

    if (!SingleInstance.acquire()) {
        // A window rather than stderr: this launch came from a double-click or the Run
        // key, and nobody is watching a console.
        JOptionPane.showMessageDialog(
            null,
            "VaultGuard is already running.\nLook for the padlock in the notification area.",
            "VaultGuard",
            JOptionPane.INFORMATION_MESSAGE
        )
        return
    }

    val service = VaultService(config)
    if (!TrayApp(service).start()) return

    // The tray threads are daemons, so something has to keep the JVM alive. Quit from the
    // menu is the only way out, which is the point of a service.
    java.util.concurrent.CountDownLatch(1).await()
}
