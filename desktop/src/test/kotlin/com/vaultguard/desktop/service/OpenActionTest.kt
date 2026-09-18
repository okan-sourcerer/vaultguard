package com.vaultguard.desktop.service

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * "open" is how a second launch reaches the running tray. It has to work while locked -
 * that is the whole point, unlocking is what opening does - and it must not be reachable
 * from a browser, which is the native host's job to refuse.
 */
class OpenActionTest {

    private fun open(state: ServiceState, onOpen: () -> Unit) = BridgeProtocol.handle(
        JSONObject().put("action", "open"), state, { emptyList() }, {}, onOpen
    )

    @Test
    fun `open is honoured in every state, locked included`() {
        for (state in ServiceState.entries) {
            var opened = false
            val response = open(state) { opened = true }
            assertTrue("$state", response.getBoolean("ok"))
            assertTrue("$state", opened)
        }
    }

    @Test
    fun `the native host relays what the extension needs and refuses open`() {
        for (action in listOf("status", "match", "secret", "lock", "save")) {
            assertTrue(action, NativeHost.isRelayable("""{"action":"$action"}"""))
        }
        assertFalse(NativeHost.isRelayable("""{"action":"open"}"""))
        assertFalse(NativeHost.isRelayable("""{"action":"anything-else"}"""))
        assertFalse(NativeHost.isRelayable("not json"))
    }

    @Test
    fun `the relayable set and the protocol agree`() {
        // Everything the host relays, the protocol knows; a typo in either shows up here.
        assertEquals(
            setOf(BridgeProtocol.Action.STATUS, BridgeProtocol.Action.MATCH, BridgeProtocol.Action.SECRET, BridgeProtocol.Action.LOCK, BridgeProtocol.Action.SAVE),
            BridgeProtocol.RELAYABLE
        )
        assertFalse(BridgeProtocol.Action.OPEN in BridgeProtocol.RELAYABLE)
    }
}

class SaveActionTest {

    private fun save(state: ServiceState, body: JSONObject, onCapture: (BridgeProtocol.Capture) -> Unit) =
        BridgeProtocol.handle(body.put("action", "save"), state, { emptyList() }, {}, {}, onCapture)

    @Test
    fun `a capture reaches the tray with the host of the tab, while unlocked only`() {
        var captured: BridgeProtocol.Capture? = null
        val body = JSONObject().put("url", "https://login.site.com/session?next=/").put("username", "okan").put("password", "pw")

        val locked = save(ServiceState.LOCKED, JSONObject(body.toString())) { captured = it }
        assertFalse(locked.getBoolean("ok"))
        assertTrue(captured == null)

        val unlocked = save(ServiceState.UNLOCKED, body) { captured = it }
        assertTrue(unlocked.getBoolean("ok"))
        assertEquals(BridgeProtocol.Capture("login.site.com", "okan", "pw"), captured)
    }

    @Test
    fun `a capture without a password or a usable url is refused`() {
        var called = false
        assertFalse(save(ServiceState.UNLOCKED, JSONObject().put("url", "https://site.com").put("password", "")) { called = true }.getBoolean("ok"))
        assertFalse(save(ServiceState.UNLOCKED, JSONObject().put("url", "about:blank").put("password", "pw")) { called = true }.getBoolean("ok"))
        assertFalse(called)
    }
}
