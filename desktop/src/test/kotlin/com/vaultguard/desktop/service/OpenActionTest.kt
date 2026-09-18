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
        for (action in listOf("status", "match", "secret", "lock")) {
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
            setOf(BridgeProtocol.Action.STATUS, BridgeProtocol.Action.MATCH, BridgeProtocol.Action.SECRET, BridgeProtocol.Action.LOCK),
            BridgeProtocol.RELAYABLE
        )
        assertFalse(BridgeProtocol.Action.OPEN in BridgeProtocol.RELAYABLE)
    }
}
