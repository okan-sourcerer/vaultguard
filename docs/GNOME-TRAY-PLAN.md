# The tray on Linux: plan

Written September 2026, when the desktop client had shipped installers for four platforms
and the `.deb`/`.rpm` started, printed "no system tray", and exited on the most common
Linux desktop.

## The problem

`java.awt.SystemTray` speaks XEmbed. GNOME removed XEmbed in 3.26 (2017). Every modern
Linux desktop uses **StatusNotifierItem** (SNI) instead: a DBus protocol where the
application registers with `org.kde.StatusNotifierWatcher` on the session bus and exports
an object implementing `org.kde.StatusNotifierItem` (icon, tooltip, status, `Activate`
for a left-click) plus a `com.canonical.dbusmenu` object for the right-click menu. KDE,
Cinnamon, XFCE and MATE display SNI natively.

**Stock GNOME does not display SNI either.** It needs the AppIndicator shell extension
(`gnome-shell-extension-appindicator`). Ubuntu ships it enabled; Fedora, Debian and Arch
GNOME do not. The ceiling for this work is therefore: an icon on Ubuntu and on every
non-GNOME desktop without help, and on other GNOME once the user installs one extension,
which the package recommends and the documentation names. No implementation puts an icon
on vanilla Fedora GNOME; that is GNOME's decision, not ours.

## Decisions taken

1. **Own SNI implementation over `dbus-java`** (`com.github.hypfvieh:dbus-java-core` with
   `dbus-java-transport-native-unixsocket`), not dorkbox SystemTray. The transport uses
   the JDK's own Unix-domain sockets (16+; `:desktop` targets 17), so it is pure Java with
   no JNR, JNI or GTK in the process that holds the vault. MIT. Roughly 600 lines of ours.
   dorkbox would have been a day's work and would have brought GTK through JNA and its own
   native loader into that process.
2. **Window-first is the fallback**, not an error. When there is no tray at all, the
   window is the application: it opens at start, it carries the same menu, and closing it
   quits after locking. A process that prints to a console nobody sees and exits is what
   this replaces.
3. **Ubuntu first.** Vanilla GNOME needing one extension is the accepted end state. No
   GNOME Shell extension of our own: that is JavaScript inside GNOME Shell, a second
   codebase, and the AppIndicator extension already exists and is maintained.

## Phases

Each phase ships on its own.

### Phase 1 — the seam, and window-first (done)

`TrayApp` is split into a controller and a `Frontend`:

```
interface Frontend {
    fun start(model: TrayModel, onPrimary: () -> Unit, onQuit: () -> Unit): Boolean
    fun render(model: TrayModel)
    fun notify(caption: String, text: String)
    fun stop()
}
```

`TrayModel` is the icon state, the status line and the menu as data (`MenuEntry`: item
with id, label, enabled and action; or separator). One model serves every frontend, so
the AWT menu, the SNI menu and the window's menu cannot drift.

Two frontends: `AwtTrayFrontend`, the existing behaviour; and `WindowFrontend`, a small
persistent window with the status line and the menu as buttons, which is the application
when nothing else can draw an icon. Selection at startup:

1. A `StatusNotifierWatcher` on the session bus → SNI (Linux; checked first, since it is
   the one GNOME shows).
2. `SystemTray.isSupported()` → AWT (Windows, macOS, and X11 desktops without a watcher).
3. Otherwise → window.

Nothing in `VaultService` changes. The controller is tested with a fake frontend.

### Phase 2 — the SNI item (done, awaiting a desktop)

- `org.kde.StatusNotifierItem`: `Category=ApplicationStatus`, `Id=vaultguard`,
  `IconPixmap` as ARGB from `VaultIcon` (locked and unlocked; `NewIcon` on change),
  `ToolTip`, `Activate` → the primary action, `SecondaryActivate` → unlock. Register with
  the watcher; re-register on the watcher's `NameOwnerChanged`, which fires every time the
  GNOME extension reloads.
- `com.canonical.dbusmenu`: the `TrayModel` menu expressed as `GetLayout`,
  `GetGroupProperties`, `Event("clicked")`, and `LayoutUpdated` when enabled-state
  changes. This is the fiddly half; KDE and the GNOME extension read the spec slightly
  differently, and both are test targets.

### Phase 3 — Linux notifications (done, awaiting a desktop)

`TrayIcon.displayMessage` goes with XEmbed. `org.freedesktop.Notifications.Notify` with a
`default` action and the `ActionInvoked` signal gives click-to-open, which is the
notification policy already in force (ARCHITECTURE.md, "One action: Open"). The same call
serves the window frontend.

### Phase 4 — packaging and documentation

README and ARCHITECTURE say which desktops show an icon without help and which need the
extension. A `Recommends:` on `gnome-shell-extension-appindicator` is not something
jpackage can express (`--linux-package-deps` is `Depends`, and depending on a GNOME
extension from a package KDE users install would be wrong); it stays a documented step.

## What is done and what is not

Implemented: `desktop/.../service/sni/` — the DBus interfaces, `MenuLayout` (the
`TrayModel` as a dbusmenu tree, pure), `SniFrontend` (item, menu, watcher registration and
re-registration, notifications with a `default` action). `Frontend.select()` prefers SNI
whenever a `StatusNotifierWatcher` owns its name on the session bus.

Tested: `MenuLayoutTest` on every platform; `SniFrontendTest` against a private
`dbus-daemon` with a fake watcher and a fake notification server, on the Linux CI runner
(it skips itself elsewhere). That covers the wire: registration, `Activate`, `GetLayout`,
`Event`, `LayoutUpdated`, `Notify`, `ActionInvoked`.

**Not yet done: the manual pass on a real desktop.** Ubuntu GNOME (Wayland), KDE Plasma,
Fedora GNOME with the extension. Until that has happened the honest status of the Linux
tray is "believed to work", per CLAUDE.md's rule about platform behaviour.

## Testing

- Pure: menu model → dbusmenu layout; `BufferedImage` → SNI pixmap bytes; frontend
  selection. Unit tests.
- **Real DBus in CI.** The Ubuntu runner has `dbus-daemon`. A test starts a private bus
  with `dbus-run-session`, runs a fake `StatusNotifierWatcher` written in the test, starts
  the SNI frontend against it, and asserts registration, `Activate` → open, and
  `LayoutUpdated` on lock. A wire-level test with no desktop, and the thing that catches an
  interface-name typo before a person does. About a day, counted in Phase 2.
- Manual before it ships, non-negotiable (CLAUDE.md, "What the tests cannot tell you"):
  Ubuntu GNOME on Wayland, KDE Plasma, Fedora GNOME with the extension. A VM each.

## Estimate

About six days including the CI harness. Phase 1 is worth having regardless of the rest:
it turns "exits silently" into a usable application on the most common Linux desktop.
