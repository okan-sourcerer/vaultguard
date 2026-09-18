package com.vaultguard.desktop.service

import com.vaultguard.app.security.KeyDerivation
import com.vaultguard.desktop.cloud.DesktopConfig
import com.vaultguard.desktop.cloud.SavedSession
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * The controller through a fake frontend: what is on the menu, what is enabled when, and
 * what gets notified. No display needed - that is the point of the seam.
 */
class TrayAppTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private class FakeFrontend : Frontend {
        var started: TrayModel? = null
        var primary: (() -> Unit)? = null
        val rendered = mutableListOf<TrayModel>()
        val notifications = mutableListOf<Pair<String, String>>()
        var stopped = false

        override fun start(model: TrayModel, onPrimary: () -> Unit, onQuit: () -> Unit): Boolean {
            started = model
            primary = onPrimary
            return true
        }

        override fun render(model: TrayModel) { rendered += model }
        override fun notify(caption: String, text: String) { notifications += caption to text }
        override fun stop() { stopped = true }
    }

    private fun service(signedIn: Boolean): VaultService {
        val sessionFile = File(temporaryFolder.root, "desktop-session.json")
        if (signedIn) {
            val salt = ByteArray(16) { 7 }
            val key = KeyDerivation().deriveKey("master".toCharArray(), salt)
            SavedSession.save(SavedSession("token", "okan@example.com"), key, salt, sessionFile)
        }
        return VaultService(DesktopConfig("p", "k", "id", "secret"), sessionFile = sessionFile)
    }

    private fun app(signedIn: Boolean, frontend: FakeFrontend = FakeFrontend()): Pair<TrayApp, FakeFrontend> {
        // Its own state directory, with the first-run marker already present: the real one
        // holds the running service's bridge handshake, and first run opens a window.
        val state = temporaryFolder.newFolder("state").also { File(it, "first-run-done").writeText("") }
        return TrayApp(service(signedIn), frontend = frontend, stateDirectory = state) to frontend
    }

    private fun TrayModel.item(id: String) = items.first { it.id == id }

    @Test
    fun `signed out - the menu offers sign in, and nothing that needs a vault`() {
        val (app, _) = app(signedIn = false)
        val model = app.model()

        assertTrue(model.locked)
        assertEquals("Signed out", model.status)
        assertEquals("Sign in", model.item("unlock").label)
        assertTrue(model.item("unlock").enabled)
        assertFalse(model.item("refresh").enabled)
        assertFalse(model.item("lock").enabled)
        assertFalse(model.item("signout").enabled)
        assertTrue(model.item("open").enabled)
        assertTrue(model.item("quit").enabled)
    }

    @Test
    fun `locked - unlock and sign out are offered, refresh and lock are not`() {
        val (app, _) = app(signedIn = true)
        val model = app.model()

        assertEquals("Locked", model.status)
        assertEquals("Unlock", model.item("unlock").label)
        assertTrue(model.item("signout").enabled)
        assertFalse(model.item("refresh").enabled)
        assertFalse(model.item("lock").enabled)
    }

    @Test
    fun `open is the first item and quit the last, on every frontend`() {
        val (app, _) = app(signedIn = true)
        val ids = app.model().items.map { it.id }

        assertEquals("open", ids.first())
        assertEquals("quit", ids.last())
        assertEquals(ids.size, ids.toSet().size)
    }

    @Test
    fun `feedback is on the menu only when the build has a hub`() {
        val (app, _) = app(signedIn = true)
        // Present exactly when the baked configuration names a hub - never as a dead item.
        val expected = FeedbackDialog().isAvailable
        assertEquals(expected, app.model().items.any { it.id == "feedback" })
    }

    @Test
    fun `starting locked notifies once, and the notification means open`() {
        val (app, frontend) = app(signedIn = true)
        assertTrue(app.start())

        assertEquals(listOf("VaultGuard is running" to "Click to unlock."), frontend.notifications)
        assertEquals(frontend.started?.status, "Locked")
        assertTrue(frontend.rendered.isNotEmpty())
        assertTrue(frontend.primary != null)
    }

    @Test
    fun `starting signed out says sign in`() {
        val (app, frontend) = app(signedIn = false)
        assertTrue(app.start())

        assertEquals(listOf("VaultGuard is running" to "Click to sign in."), frontend.notifications)
    }
}
