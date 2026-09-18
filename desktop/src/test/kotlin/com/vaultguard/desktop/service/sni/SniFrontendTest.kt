package com.vaultguard.desktop.service.sni

import com.vaultguard.desktop.service.MenuEntry
import com.vaultguard.desktop.service.TrayModel
import org.freedesktop.dbus.connections.impl.DBusConnection
import org.freedesktop.dbus.connections.impl.DBusConnectionBuilder
import org.freedesktop.dbus.interfaces.Properties
import org.freedesktop.dbus.types.UInt32
import org.freedesktop.dbus.types.Variant
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.concurrent.CompletableFuture
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * The frontend against a real bus with a fake desktop on it.
 *
 * `dbus-daemon` is started privately; a fake `StatusNotifierWatcher` and a fake
 * `org.freedesktop.Notifications` are exported on it, and the frontend is driven through
 * the same DBus calls a desktop makes. Wire-level, no display. Skips itself where there
 * is no `dbus-daemon` - Windows, macOS - so the Ubuntu runner is where it counts.
 */
class SniFrontendTest {

    private lateinit var daemon: Process
    private lateinit var address: String
    private lateinit var desktop: DBusConnection
    private lateinit var frontend: SniFrontend

    private val registered = CompletableFuture<String>()
    private val primaryClicks = LinkedBlockingQueue<Unit>()
    private var clicked: String? = null
    private val notifyCalls = LinkedBlockingQueue<List<Any?>>()

    private val model = TrayModel(
        locked = true,
        status = "Locked",
        entries = listOf(
            MenuEntry.Item("open", "Open VaultGuard") { clicked = "open" },
            MenuEntry.Separator,
            MenuEntry.Item("unlock", "Unlock") { clicked = "unlock" },
            MenuEntry.Item("lock", "Lock", enabled = false) { clicked = "lock" },
            MenuEntry.Item("quit", "Quit") { clicked = "quit" }
        )
    )

    @Before
    fun startBus() {
        assumeTrue("needs dbus-daemon", System.getProperty("os.name").lowercase().contains("linux"))
        val binary = listOf("/usr/bin/dbus-daemon", "/bin/dbus-daemon").map(::File).firstOrNull { it.canExecute() }
        assumeTrue("needs dbus-daemon", binary != null)

        val socket = File(Files.createTempDirectory("vg-bus").toFile(), "bus")
        daemon = ProcessBuilder(
            binary!!.path, "--session", "--nofork", "--print-address=1", "--address=unix:path=${socket.path}"
        ).redirectErrorStream(false).start()
        address = daemon.inputStream.bufferedReader().readLine() ?: error("dbus-daemon printed no address")

        desktop = DBusConnectionBuilder.forAddress(address).withShared(false).build()
        desktop.requestBusName(SniFrontend.WATCHER)
        desktop.exportObject(SniFrontend.WATCHER_PATH, object : StatusNotifierWatcher {
            override fun getObjectPath() = SniFrontend.WATCHER_PATH
            override fun isRemote() = false
            override fun RegisterStatusNotifierItem(service: String) { registered.complete(service) }
        })
        desktop.requestBusName(SniFrontend.NOTIFICATIONS)
        desktop.exportObject(SniFrontend.NOTIFICATIONS_PATH, object : Notifications {
            override fun getObjectPath() = SniFrontend.NOTIFICATIONS_PATH
            override fun isRemote() = false
            override fun Notify(
                appName: String, replacesId: UInt32, appIcon: String, summary: String, body: String,
                actions: List<String>, hints: Map<String, Variant<*>>, expireTimeout: Int
            ): UInt32 {
                notifyCalls += listOf(appName, summary, body, actions, hints.keys)
                return UInt32(7)
            }
        })

        frontend = SniFrontend(address)
    }

    @After
    fun stopBus() {
        if (::frontend.isInitialized) runCatching { frontend.stop() }
        if (::desktop.isInitialized) runCatching { desktop.disconnect() }
        if (::daemon.isInitialized) daemon.destroy()
    }

    private fun <T> CompletableFuture<T>.soon(): T = get(10, TimeUnit.SECONDS)

    /** A dbusmenu row as it arrives through a Variant: `(ia{sv}av)` untyped. */
    private data class Row(val id: Int, val properties: Map<String, Any?>)

    private fun rows(reply: LayoutReply<UInt32, LayoutItem>): List<Row> = reply.layout.children.map { child ->
        val struct = child.value as Array<*>
        @Suppress("UNCHECKED_CAST")
        val properties = (struct[1] as Map<String, Variant<*>>).mapValues { it.value.value }
        Row(struct[0] as Int, properties)
    }

    @Test
    fun `registers with the watcher, answers Activate, serves and updates the menu, notifies`() {
        assertTrue(SniFrontend.isAvailable(address))
        assertTrue(frontend.start(model, onPrimary = { primaryClicks += Unit }, onQuit = {}))

        // Registration names our bus name; the desktop talks to us through it from here on.
        val name = registered.soon()
        assertTrue(name, name.startsWith("org.kde.StatusNotifierItem-"))

        // Left-click.
        val item = desktop.getRemoteObject(name, SniFrontend.ITEM_PATH, StatusNotifierItem::class.java)
        item.Activate(0, 0)
        assertTrue(primaryClicks.poll(10, TimeUnit.SECONDS) != null)

        // Properties, as a desktop reads them.
        val itemProperties = desktop.getRemoteObject(name, SniFrontend.ITEM_PATH, Properties::class.java)
        val all = itemProperties.GetAll("org.kde.StatusNotifierItem")
        assertEquals("vaultguard", all.getValue("Id").value)
        assertEquals("ApplicationStatus", all.getValue("Category").value)
        // Inside a Variant the struct arrives untyped: (iiay) is an Object[] of two Integers
        // and a byte[] - which is exactly what a desktop written in C sees.
        val pixmaps = all.getValue("IconPixmap").value as List<*>
        assertEquals(Pixmaps.SIZES, pixmaps.map { (it as Array<*>)[0] })
        val largest = pixmaps.last() as Array<*>
        val bytes = largest[2].let { (it as? ByteArray)?.size ?: (it as List<*>).size }
        assertEquals(48 * 48 * 4, bytes)
        assertEquals(SniFrontend.MENU_PATH, all.getValue("Menu").value.toString())

        // The menu.
        val menu = desktop.getRemoteObject(name, SniFrontend.MENU_PATH, DBusMenu::class.java)
        val rows = rows(menu.GetLayout(0, -1, emptyList()))
        val labels = rows.map { it.properties["label"] }
        assertTrue(labels.toString(), labels.contains("Open VaultGuard") && labels.contains("Quit"))
        assertEquals("Locked", labels.first())
        assertTrue(rows.any { it.properties["type"] == "separator" })

        // A click on Unlock reaches its action; a click on the disabled Lock does not.
        val unlockId = rows.first { it.properties["label"] == "Unlock" }.id
        val lockId = rows.first { it.properties["label"] == "Lock" }.id
        menu.Event(unlockId, "clicked", Variant(""), UInt32(0))
        assertEquals("unlock", clicked)
        menu.Event(lockId, "clicked", Variant(""), UInt32(0))
        assertEquals("unlock", clicked)

        // A render announces a new layout revision, and the next fetch reflects the change.
        val updates = LinkedBlockingQueue<Long>()
        desktop.addSigHandler(DBusMenu.LayoutUpdated::class.java) { updates += it.revision.toLong() }
        frontend.render(model.copy(status = "Unlocked - 3 entries", locked = false))
        val revision = updates.poll(10, TimeUnit.SECONDS)
        assertEquals(frontend.layoutRevision, revision)
        assertEquals("Unlocked - 3 entries", rows(menu.GetLayout(0, -1, emptyList())).first().properties["label"])

        // A notification carries a default action, and invoking it is the primary action.
        frontend.notify("VaultGuard is running", "Click to unlock.")
        val call = notifyCalls.poll(10, TimeUnit.SECONDS)!!
        assertEquals("VaultGuard", call[0])
        assertEquals("VaultGuard is running", call[1])
        assertEquals(listOf("default", "Open"), call[3])
        assertTrue((call[4] as Set<*>).contains("image-data"))
        desktop.sendMessage(Notifications.ActionInvoked(SniFrontend.NOTIFICATIONS_PATH, UInt32(7), "default"))
        assertTrue(primaryClicks.poll(10, TimeUnit.SECONDS) != null)
    }
}
