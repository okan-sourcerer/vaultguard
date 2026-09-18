package com.vaultguard.desktop.service

import com.vaultguard.desktop.cloud.DesktopConfig
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.swing.JOptionPane
import javax.swing.JPasswordField
import javax.swing.SwingUtilities

/**
 * The controller behind the tray icon - or behind the window that stands in for one.
 *
 * Decides what everything means: what the menu contains, what a click does, when to
 * notify. Draws nothing. A [Frontend] draws; there is one for `java.awt.SystemTray`, one
 * that is a plain window for desktops with no tray Java can reach, and (planned, see
 * docs/GNOME-TRAY-PLAN.md) one for StatusNotifierItem. All three render the same
 * [TrayModel], so they cannot disagree about what is on the menu.
 *
 * Nothing here holds a key, a password or a credential.
 */
class TrayApp(
    private val service: VaultService,
    private val clipboard: ClipboardGuard = ClipboardGuard(),
    private val frontend: Frontend = Frontend.select(),
    /** Where the bridge handshake and the first-run marker live; a test points elsewhere. */
    private val stateDirectory: File = Setup.stateDirectory
) {

    // Lazy so a test can drive the controller without a display: Swing components are
    // created when a window is first shown, not when the controller is.
    private val search by lazy { SearchDialog(service, clipboard) }
    private val feedback = FeedbackDialog()
    private val settings = SettingsDialog()
    private val bridge = BridgeServer(service, handshakeFile = File(stateDirectory, "bridge.json"), onOpen = { open() })

    private val worker = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "vaultguard-tray-worker").apply { isDaemon = true }
    }
    private val ticker = Executors.newSingleThreadScheduledExecutor { runnable ->
        Thread(runnable, "vaultguard-autolock").apply { isDaemon = true }
    }

    /**
     * The menu, as data. One primary action, "Open", reachable from everywhere a person
     * might click: this item, a left-click on the icon, any notification, and a second
     * launch of the program. Unlock stays separate for the browser case - let the
     * extension fill, no window wanted - and is the only action that does not open one.
     */
    fun model(): TrayModel {
        val state = service.state
        val status = when (state) {
            ServiceState.SIGNED_OUT -> "Signed out"
            ServiceState.LOCKED -> "Locked"
            ServiceState.UNLOCKED -> buildString {
                append("Unlocked - ").append(service.entryCount).append(" entries")
                if (service.undecryptableCount > 0) {
                    append(" (").append(service.undecryptableCount).append(" unreadable)")
                }
            }
        }
        val entries = buildList {
            add(MenuEntry.Item("open", "Open VaultGuard") { open() })
            add(MenuEntry.Item("settings", "Settings...") { settings.show() })
            add(MenuEntry.Separator)
            add(MenuEntry.Item(
                "unlock",
                if (state == ServiceState.SIGNED_OUT) "Sign in" else "Unlock",
                enabled = state != ServiceState.UNLOCKED
            ) { worker.submit { unlock(showWindow = false) } })
            add(MenuEntry.Item("refresh", "Refresh", enabled = state == ServiceState.UNLOCKED) { worker.submit { refresh() } })
            add(MenuEntry.Item("lock", "Lock", enabled = state == ServiceState.UNLOCKED) { worker.submit { lockNow() } })
            add(MenuEntry.Item("signout", "Sign out", enabled = state != ServiceState.SIGNED_OUT) { worker.submit { signOut() } })
            add(MenuEntry.Separator)
            // Only when the build knows a hub; a menu item that always fails is worse than none.
            if (feedback.isAvailable) add(MenuEntry.Item("feedback", "Send feedback...") { feedback.show() })
            add(MenuEntry.Item("quit", "Quit") { quit() })
        }
        return TrayModel(locked = state != ServiceState.UNLOCKED, status = status, entries = entries)
    }

    fun start(): Boolean {
        // Started before the icon appears, so a browser that is already open finds the
        // bridge the moment the tray does.
        runCatching { bridge.start() }
            .onFailure { System.err.println("Could not open the browser bridge: ${it.message}") }

        if (!frontend.start(model(), onPrimary = { open() }, onQuit = { quit() })) {
            System.err.println("Nothing on this desktop can show VaultGuard.")
            bridge.stop()
            return false
        }
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
        val firstRun = File(stateDirectory, "first-run-done")
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
        frontend.stop()
        worker.shutdownNow()
        ticker.shutdownNow()
        kotlin.system.exitProcess(0)
    }

    // -- Presentation ---------------------------------------------------------------------

    private fun render() = frontend.render(model())

    private fun status(message: String) = frontend.render(model().copy(status = message))

    private fun notify(caption: String, text: String) = frontend.notify(caption, text)

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
