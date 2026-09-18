package com.vaultguard.desktop.service.sni

import com.vaultguard.desktop.service.Frontend
import com.vaultguard.desktop.service.TrayModel
import com.vaultguard.desktop.service.VaultIcon
import org.freedesktop.dbus.DBusPath
import org.freedesktop.dbus.connections.impl.DBusConnection
import org.freedesktop.dbus.connections.impl.DBusConnectionBuilder
import org.freedesktop.dbus.interfaces.DBus
import org.freedesktop.dbus.interfaces.Properties
import org.freedesktop.dbus.types.UInt32
import org.freedesktop.dbus.types.Variant
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * The tray icon as a StatusNotifierItem over DBus: Linux desktops that no longer speak
 * XEmbed - KDE, and GNOME with the AppIndicator extension. See docs/GNOME-TRAY-PLAN.md.
 *
 * Two objects are exported. `/StatusNotifierItem` is the icon: pixmaps drawn by
 * [VaultIcon], a tooltip, and `Activate` for a left-click, which is the primary action.
 * `/MenuBar` is the right-click menu through `com.canonical.dbusmenu`, rendered from the
 * same [TrayModel] as every other frontend; a render bumps the layout revision and emits
 * `LayoutUpdated`, and the desktop fetches the menu again.
 *
 * Notifications go through `org.freedesktop.Notifications` with a `default` action, so a
 * click on one is the primary action here too.
 *
 * The watcher (`org.kde.StatusNotifierWatcher`) is the desktop's registry of items. It
 * restarts whenever the GNOME extension reloads, and forgets everything when it does, so
 * `NameOwnerChanged` for its name triggers a fresh registration.
 *
 * Nothing here holds a key, a password or a credential; it holds labels.
 */
class SniFrontend(
    /** A bus address for tests; null means the session bus from the environment. */
    private val address: String? = null
) : Frontend {

    private lateinit var connection: DBusConnection
    private lateinit var onPrimary: () -> Unit
    @Volatile private var model: TrayModel = TrayModel(locked = true, status = "", entries = emptyList())
    private val revision = AtomicLong(1)
    private val busName = "org.kde.StatusNotifierItem-${ProcessHandle.current().pid()}-1"
    private val ourNotifications = ConcurrentHashMap.newKeySet<Long>()

    override fun start(model: TrayModel, onPrimary: () -> Unit, onQuit: () -> Unit): Boolean {
        this.model = model
        this.onPrimary = onPrimary
        connection = try {
            connect(address)
        } catch (e: Exception) {
            return false
        }
        if (!hasWatcher(connection)) {
            connection.disconnect()
            return false
        }

        connection.requestBusName(busName)
        connection.exportObject(ITEM_PATH, item)
        connection.exportObject(MENU_PATH, menu)
        register()

        connection.addSigHandler(DBus.NameOwnerChanged::class.java) { signal ->
            if (signal.name == WATCHER && !signal.newOwner.isNullOrEmpty()) runCatching { register() }
        }
        connection.addSigHandler(Notifications.ActionInvoked::class.java) { signal ->
            if (ourNotifications.remove(signal.id.toLong()) && signal.actionKey == "default") onPrimary()
        }
        connection.addSigHandler(Notifications.NotificationClosed::class.java) { signal ->
            ourNotifications.remove(signal.id.toLong())
        }
        return true
    }

    private fun register() {
        connection.getRemoteObject(WATCHER, WATCHER_PATH, StatusNotifierWatcher::class.java)
            .RegisterStatusNotifierItem(busName)
    }

    override fun render(model: TrayModel) {
        val iconChanged = this.model.locked != model.locked
        this.model = model
        val rev = UInt32(revision.incrementAndGet())
        runCatching {
            connection.sendMessage(DBusMenu.LayoutUpdated(MENU_PATH, rev, MenuLayout.ROOT_ID))
            connection.sendMessage(StatusNotifierItem.NewToolTip(ITEM_PATH))
            if (iconChanged) connection.sendMessage(StatusNotifierItem.NewIcon(ITEM_PATH))
        }
    }

    override fun notify(caption: String, text: String) {
        runCatching {
            val notifications = connection.getRemoteObject(
                NOTIFICATIONS, NOTIFICATIONS_PATH, Notifications::class.java
            )
            val id = notifications.Notify(
                "VaultGuard",
                UInt32(0),
                "",
                caption,
                text,
                listOf("default", "Open"),
                mapOf("image-data" to Variant(imageData(model.locked), "(iiibiiay)")),
                -1
            )
            ourNotifications += id.toLong()
        }
    }

    override fun stop() {
        runCatching { connection.unExportObject(ITEM_PATH) }
        runCatching { connection.unExportObject(MENU_PATH) }
        runCatching { connection.releaseBusName(busName) }
        runCatching { connection.disconnect() }
    }

    /** The current revision, for a test to compare against `LayoutUpdated`. */
    val layoutRevision: Long get() = revision.get()

    // -- /StatusNotifierItem ----------------------------------------------------------------

    private val item = object : StatusNotifierItem, Properties {
        override fun getObjectPath(): String = ITEM_PATH
        override fun isRemote(): Boolean = false

        override fun ContextMenu(x: Int, y: Int) = Unit
        override fun Activate(x: Int, y: Int) = onPrimary()
        override fun SecondaryActivate(x: Int, y: Int) = onPrimary()
        override fun Scroll(delta: Int, orientation: String) = Unit

        private fun all(): Map<String, Variant<*>> {
            val m = model
            return mapOf(
                "Category" to Variant("ApplicationStatus"),
                "Id" to Variant("vaultguard"),
                "Title" to Variant("VaultGuard"),
                "Status" to Variant("Active"),
                "WindowId" to Variant(0),
                "IconName" to Variant(""),
                "IconPixmap" to Variant(Pixmaps.icon(m.locked), "a(iiay)"),
                "OverlayIconName" to Variant(""),
                "OverlayIconPixmap" to Variant(emptyList<Pixmap>(), "a(iiay)"),
                "AttentionIconName" to Variant(""),
                "AttentionIconPixmap" to Variant(emptyList<Pixmap>(), "a(iiay)"),
                "AttentionMovieName" to Variant(""),
                "ToolTip" to Variant(ToolTip("", emptyList(), "VaultGuard", m.status), "(sa(iiay)ss)"),
                "ItemIsMenu" to Variant(false),
                "Menu" to Variant(DBusPath(MENU_PATH))
            )
        }

        @Suppress("UNCHECKED_CAST")
        override fun <A : Any?> Get(interfaceName: String, propertyName: String): A =
            (all()[propertyName] ?: throw IllegalArgumentException("No property $propertyName")) as A

        override fun <A : Any?> Set(interfaceName: String, propertyName: String, value: A) = Unit

        override fun GetAll(interfaceName: String): Map<String, Variant<*>> = all()
    }

    // -- /MenuBar -----------------------------------------------------------------------------

    private val menu = object : DBusMenu, Properties {
        override fun getObjectPath(): String = MENU_PATH
        override fun isRemote(): Boolean = false

        override fun GetLayout(parentId: Int, recursionDepth: Int, propertyNames: List<String>): LayoutReply<UInt32, LayoutItem> {
            val full = MenuLayout.layout(model)
            val subtree = if (parentId == MenuLayout.ROOT_ID) full else LayoutItem(parentId, MenuLayout.properties(model, parentId), emptyList())
            return LayoutReply(UInt32(revision.get()), subtree)
        }

        override fun GetGroupProperties(ids: List<Int>, propertyNames: List<String>): List<ItemProperties> {
            val wanted = ids.ifEmpty { MenuLayout.allIds(model) }
            return wanted.map { ItemProperties(it, MenuLayout.properties(model, it)) }
        }

        override fun GetProperty(id: Int, name: String): Variant<*> =
            MenuLayout.properties(model, id)[name] ?: Variant("")

        override fun Event(id: Int, eventId: String, data: Variant<*>, timestamp: UInt32) {
            if (eventId == "clicked") MenuLayout.itemFor(model, id)?.takeIf { it.enabled }?.action?.invoke()
        }

        override fun EventGroup(events: List<MenuEvent>): List<Int> {
            events.forEach { Event(it.id, it.eventId, it.data, it.timestamp) }
            return emptyList()
        }

        override fun AboutToShow(id: Int): Boolean = false

        override fun AboutToShowGroup(ids: List<Int>): AboutToShowGroupReply<List<Int>, List<Int>> =
            AboutToShowGroupReply(emptyList(), emptyList())

        private val properties: Map<String, Variant<*>> = mapOf(
            "Version" to Variant(UInt32(3)),
            "TextDirection" to Variant("ltr"),
            "Status" to Variant("normal"),
            "IconThemePath" to Variant(emptyList<String>(), "as")
        )

        @Suppress("UNCHECKED_CAST")
        override fun <A : Any?> Get(interfaceName: String, propertyName: String): A =
            (properties[propertyName] ?: throw IllegalArgumentException("No property $propertyName")) as A

        override fun <A : Any?> Set(interfaceName: String, propertyName: String, value: A) = Unit

        override fun GetAll(interfaceName: String): Map<String, Variant<*>> = properties
    }

    companion object {
        const val WATCHER = "org.kde.StatusNotifierWatcher"
        const val WATCHER_PATH = "/StatusNotifierWatcher"
        const val ITEM_PATH = "/StatusNotifierItem"
        const val MENU_PATH = "/MenuBar"
        const val NOTIFICATIONS = "org.freedesktop.Notifications"
        const val NOTIFICATIONS_PATH = "/org/freedesktop/Notifications"

        private fun connect(address: String?): DBusConnection =
            (if (address != null) DBusConnectionBuilder.forAddress(address) else DBusConnectionBuilder.forSessionBus())
                .withShared(false)
                .build()

        private fun hasWatcher(connection: DBusConnection): Boolean = runCatching {
            connection.getRemoteObject("org.freedesktop.DBus", "/org/freedesktop/DBus", DBus::class.java)
                .NameHasOwner(WATCHER)
        }.getOrDefault(false)

        /**
         * Whether this desktop has a watcher to register with. Linux only; elsewhere there
         * is no session bus and the answer is no without trying.
         */
        fun isAvailable(address: String? = null): Boolean {
            val os = System.getProperty("os.name").orEmpty().lowercase()
            if (!os.contains("linux") && address == null) return false
            if (address == null && System.getenv("DBUS_SESSION_BUS_ADDRESS").isNullOrEmpty()) return false
            return runCatching {
                val connection = connect(address)
                try { hasWatcher(connection) } finally { connection.disconnect() }
            }.getOrDefault(false)
        }

        /** `(iiibiiay)` for the notification `image-data` hint: RGBA, 8 bits, 4 channels. */
        fun imageData(locked: Boolean): ImageData {
            val image = VaultIcon.image(48, locked)
            val data = ByteArray(image.width * image.height * 4)
            var i = 0
            for (y in 0 until image.height) for (x in 0 until image.width) {
                val argb = image.getRGB(x, y)
                data[i++] = (argb shr 16).toByte()
                data[i++] = (argb shr 8).toByte()
                data[i++] = argb.toByte()
                data[i++] = (argb ushr 24).toByte()
            }
            return ImageData(image.width, image.height, image.width * 4, true, 8, 4, data)
        }
    }
}
