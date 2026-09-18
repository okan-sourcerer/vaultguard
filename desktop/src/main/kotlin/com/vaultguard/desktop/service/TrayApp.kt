package com.vaultguard.desktop.service

import com.vaultguard.desktop.cloud.DesktopConfig
import java.awt.MenuItem
import java.awt.PopupMenu
import java.awt.SystemTray
import java.awt.TrayIcon
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
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
    private val clipboard: ClipboardGuard = ClipboardGuard()
) {

    private val search = SearchDialog(service, clipboard)
    private val feedback = FeedbackDialog()
    private val settings = SettingsDialog()
    private val bridge = BridgeServer(service, onOpen = { open() })

    private lateinit var trayIcon: TrayIcon
    private val statusItem = MenuItem("Starting...")
    private val openItem = MenuItem("Open VaultGuard")
    private val unlockItem = MenuItem("Unlock")
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

        // One primary action, "Open", reachable from everywhere a person might click:
        // this item, a left-click on the icon, any notification, and a second launch of
        // the program. Unlock stays separate for the browser case - let the extension
        // fill, no window wanted - and is the only action that does not open one.
        val menu = PopupMenu().apply {
            statusItem.isEnabled = false
            add(statusItem)
            addSeparator()
            add(openItem)
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
            // Double-click, and a click on a notification balloon, both arrive here.
            addActionListener { open() }
            // A single left-click does not; it is a mouse event. Right-click is the menu.
            addMouseListener(object : MouseAdapter() {
                override fun mouseClicked(e: MouseEvent) {
                    if (SwingUtilities.isLeftMouseButton(e) && e.clickCount == 1) open()
                }
            })
        }

        openItem.addActionListener { open() }
        unlockItem.addActionListener { worker.submit { unlock(showWindow = false) } }
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

        // The only notification for a state the user did not just cause, besides the
        // idle lock. Clicking it opens - the same as clicking the icon.
        when (service.state) {
            ServiceState.SIGNED_OUT -> notify("VaultGuard is running", "Click to sign in.")
            ServiceState.LOCKED -> notify("VaultGuard is running", "Click to unlock.")
            ServiceState.UNLOCKED -> Unit
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

    /** Guards against a double-click, or a balloon click during a prompt, asking twice. */
    private val opening = AtomicBoolean(false)

    /**
     * The primary action. Whatever state the vault is in, the end of this is the window
     * on screen: unlocked shows it, locked asks for the password first, signed out signs
     * in first. Nobody should have to come back to the menu to finish what they started.
     */
    private fun open() {
        if (service.state == ServiceState.UNLOCKED) {
            // credentials() counts as use, so a search keeps the auto-lock at bay while
            // the window is being driven.
            search.show()
            return
        }
        if (!opening.compareAndSet(false, true)) return
        worker.submit {
            try {
                unlock(showWindow = true)
            } finally {
                opening.set(false)
            }
        }
    }

    private fun tick() {
        if (service.lockIfIdle()) {
            notify("Vault locked", "It had been idle. Click to unlock.")
        }
    }

    private fun unlock(showWindow: Boolean) {
        val opened = service.unlock(
            askPassword = { label -> askPassword(label) },
            say = { status(it) },
            warn = { message -> SwingUtilities.invokeLater { error(message) } }
        )
        render()
        if (opened && showWindow) search.show()
    }

    private fun refresh() {
        if (service.state != ServiceState.UNLOCKED) {
            SwingUtilities.invokeLater { error("Unlock first.") }
            return
        }
        status("Refreshing...")
        service.refresh { message -> SwingUtilities.invokeLater { error(message) } }
        render()
    }

    // No notification for these: the user just chose them, and the menu's status line
    // changes in front of them. A balloon confirming a click is noise.
    private fun lockNow() {
        service.lock()
        render()
    }

    private fun signOut() {
        service.signOut()
        render()
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

        unlockItem.label = if (state == ServiceState.SIGNED_OUT) "Sign in" else "Unlock"
        unlockItem.isEnabled = state != ServiceState.UNLOCKED
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
        // The running copy is asked to open instead: a second launch from the Start Menu
        // means "show me VaultGuard", not "tell me about processes". The dialog is for
        // when the lock is held but nothing answers, which should not happen.
        if (NativeHost.askRunningServiceToOpen()) return
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
