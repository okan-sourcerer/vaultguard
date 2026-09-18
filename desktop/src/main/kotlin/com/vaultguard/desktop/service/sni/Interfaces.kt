package com.vaultguard.desktop.service.sni

import org.freedesktop.dbus.Struct
import org.freedesktop.dbus.Tuple
import org.freedesktop.dbus.annotations.DBusInterfaceName
import org.freedesktop.dbus.annotations.Position
import org.freedesktop.dbus.interfaces.DBusInterface
import org.freedesktop.dbus.messages.DBusSignal
import org.freedesktop.dbus.types.UInt32
import org.freedesktop.dbus.types.Variant

/*
 * The DBus contracts, as dbus-java wants them: one Kotlin interface per DBus interface,
 * methods named as on the wire, signals as nested classes, structs with positional fields.
 *
 * Specifications:
 *   https://www.freedesktop.org/wiki/Specifications/StatusNotifierItem/
 *   https://github.com/AyatanaIndicators/libdbusmenu/blob/master/libdbusmenu-glib/dbus-menu.xml
 *   https://specifications.freedesktop.org/notification-spec/
 */

/** `(iiay)`: width, height, ARGB32 in network byte order. */
class Pixmap(
    @field:Position(0) val width: Int,
    @field:Position(1) val height: Int,
    @field:Position(2) val data: ByteArray
) : Struct()

/** `(sa(iiay)ss)`: icon name, icon pixmaps, title, text. */
class ToolTip(
    @field:Position(0) val iconName: String,
    @field:Position(1) val iconPixmaps: List<Pixmap>,
    @field:Position(2) val title: String,
    @field:Position(3) val text: String
) : Struct()

@DBusInterfaceName("org.kde.StatusNotifierWatcher")
interface StatusNotifierWatcher : DBusInterface {
    fun RegisterStatusNotifierItem(service: String)
}

@DBusInterfaceName("org.kde.StatusNotifierItem")
interface StatusNotifierItem : DBusInterface {
    fun ContextMenu(x: Int, y: Int)
    fun Activate(x: Int, y: Int)
    fun SecondaryActivate(x: Int, y: Int)
    fun Scroll(delta: Int, orientation: String)

    class NewTitle(path: String) : DBusSignal(path)
    class NewIcon(path: String) : DBusSignal(path)
    class NewAttentionIcon(path: String) : DBusSignal(path)
    class NewOverlayIcon(path: String) : DBusSignal(path)
    class NewToolTip(path: String) : DBusSignal(path)
    class NewStatus(path: String, val status: String) : DBusSignal(path, status)
}

/** `(ia{sv}av)`: id, properties, children (each a Variant holding another LayoutItem). */
class LayoutItem(
    @field:Position(0) val id: Int,
    @field:Position(1) val properties: Map<String, Variant<*>>,
    @field:Position(2) val children: List<Variant<*>>
) : Struct()

/** `(ia{sv})` */
class ItemProperties(
    @field:Position(0) val id: Int,
    @field:Position(1) val properties: Map<String, Variant<*>>
) : Struct()

/** `(ias)` */
class RemovedProperties(
    @field:Position(0) val id: Int,
    @field:Position(1) val names: List<String>
) : Struct()

/** `(isvu)` */
class MenuEvent(
    @field:Position(0) val id: Int,
    @field:Position(1) val eventId: String,
    @field:Position(2) val data: Variant<*>,
    @field:Position(3) val timestamp: UInt32
) : Struct()

/*
 * Multi-value replies. dbus-java derives a Tuple's wire types from the *method's* return
 * type parameters, not from the fields (it reads those raw, and a raw `List` is refused),
 * so a Tuple has to be generic and the method has to say `LayoutReply<UInt32, LayoutItem>`.
 * The CI DBus test is what found this.
 */

/** The two-value reply of `GetLayout`: `u(ia{sv}av)`. */
class LayoutReply<R, L>(
    @field:Position(0) val revision: R,
    @field:Position(1) val layout: L
) : Tuple()

/** The two-value reply of `AboutToShowGroup`: `ai ai`. */
class AboutToShowGroupReply<A, B>(
    @field:Position(0) val updatesNeeded: A,
    @field:Position(1) val idErrors: B
) : Tuple()

@DBusInterfaceName("com.canonical.dbusmenu")
interface DBusMenu : DBusInterface {
    fun GetLayout(parentId: Int, recursionDepth: Int, propertyNames: List<String>): LayoutReply<UInt32, LayoutItem>
    fun GetGroupProperties(ids: List<Int>, propertyNames: List<String>): List<ItemProperties>
    fun GetProperty(id: Int, name: String): Variant<*>
    fun Event(id: Int, eventId: String, data: Variant<*>, timestamp: UInt32)
    fun EventGroup(events: List<MenuEvent>): List<Int>
    fun AboutToShow(id: Int): Boolean
    fun AboutToShowGroup(ids: List<Int>): AboutToShowGroupReply<List<Int>, List<Int>>

    class ItemsPropertiesUpdated(
        path: String,
        val updated: List<ItemProperties>,
        val removed: List<RemovedProperties>
    ) : DBusSignal(path, updated, removed)

    class LayoutUpdated(path: String, val revision: UInt32, val parent: Int) : DBusSignal(path, revision, parent)

    class ItemActivationRequested(path: String, val id: Int, val timestamp: UInt32) : DBusSignal(path, id, timestamp)
}

@DBusInterfaceName("org.freedesktop.Notifications")
interface Notifications : DBusInterface {
    fun Notify(
        appName: String,
        replacesId: UInt32,
        appIcon: String,
        summary: String,
        body: String,
        actions: List<String>,
        hints: Map<String, Variant<*>>,
        expireTimeout: Int
    ): UInt32

    class ActionInvoked(path: String, val id: UInt32, val actionKey: String) : DBusSignal(path, id, actionKey)
    class NotificationClosed(path: String, val id: UInt32, val reason: UInt32) : DBusSignal(path, id, reason)
}

/** `(iiibiiay)`: the notification `image-data` hint. Width, height, rowstride, alpha, bits, channels, RGBA. */
class ImageData(
    @field:Position(0) val width: Int,
    @field:Position(1) val height: Int,
    @field:Position(2) val rowstride: Int,
    @field:Position(3) val hasAlpha: Boolean,
    @field:Position(4) val bitsPerSample: Int,
    @field:Position(5) val channels: Int,
    @field:Position(6) val data: ByteArray
) : Struct()
