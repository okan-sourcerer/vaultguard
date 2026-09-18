package com.vaultguard.desktop.service

import java.awt.SystemTray

/**
 * The menu as data, so every way of drawing it draws the same one.
 *
 * The AWT tray, the window that stands in for it, and the StatusNotifierItem menu on
 * Linux (docs/GNOME-TRAY-PLAN.md) all render this list. An item that exists in one and not
 * another would be a bug of exactly the kind two copies of anything produce.
 */
sealed class MenuEntry {
    data class Item(
        /** Stable across renders, so a frontend can update an item rather than rebuild. */
        val id: String,
        val label: String,
        val enabled: Boolean = true,
        val action: () -> Unit
    ) : MenuEntry()

    data object Separator : MenuEntry()
}

/** What a frontend shows: the icon state, the one-line status, the menu. */
data class TrayModel(
    val locked: Boolean,
    val status: String,
    val entries: List<MenuEntry>
) {
    val items: List<MenuEntry.Item> get() = entries.filterIsInstance<MenuEntry.Item>()
}

/**
 * Something that can show the service to a person: an icon in a tray, or failing that a
 * window. The controller ([TrayApp]) decides what everything means; a frontend only draws
 * and reports clicks.
 */
interface Frontend {

    /**
     * Shows the frontend.
     *
     * @param onPrimary the one action a person gets by clicking the thing itself, or a
     *        notification: open. See ARCHITECTURE.md, "One action: Open".
     * @param onQuit the window frontend's close box; a tray has Quit in its menu instead.
     * @return false if this frontend cannot draw on this desktop, so another can be tried.
     */
    fun start(model: TrayModel, onPrimary: () -> Unit, onQuit: () -> Unit): Boolean

    fun render(model: TrayModel)

    /** A notification for something the user did not just do. Clicking it is [onPrimary]. */
    fun notify(caption: String, text: String)

    fun stop()

    companion object {
        /**
         * The first frontend this desktop can draw.
         *
         * AWT first because it is native everywhere it works at all. The window is what
         * remains on GNOME until the StatusNotifierItem frontend exists; after that it is
         * what remains on GNOME without the AppIndicator extension.
         */
        fun select(): Frontend = when (System.getenv("VAULTGUARD_FRONTEND")?.lowercase()) {
            // For trying the fallback on a desktop that has a tray; not documented to users.
            "window" -> WindowFrontend()
            else -> if (SystemTray.isSupported()) AwtTrayFrontend() else WindowFrontend()
        }
    }
}
