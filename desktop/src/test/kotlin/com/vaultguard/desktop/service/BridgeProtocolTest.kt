package com.vaultguard.desktop.service

import com.vaultguard.app.domain.model.Credential
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BridgeProtocolTest {

    private val github = Credential(
        id = "gh",
        siteName = "GitHub",
        url = "https://github.com",
        username = "okan",
        password = "github-secret"
    )

    private val bank = Credential(
        id = "bank",
        siteName = "Bank",
        url = "https://bank.example",
        username = "okan",
        password = "bank-secret"
    )

    private val vault = listOf(github, bank)

    private fun ask(
        request: JSONObject,
        state: ServiceState = ServiceState.UNLOCKED,
        onLock: () -> Unit = {}
    ) = BridgeProtocol.handle(request, state, { vault }, onLock)

    private fun match(url: String, state: ServiceState = ServiceState.UNLOCKED) =
        ask(JSONObject().put("action", "match").put("url", url), state)

    @Test
    fun `status works while locked, because that is what it is for`() {
        val response = ask(JSONObject().put("action", "status"), ServiceState.LOCKED)

        assertTrue(response.getBoolean("ok"))
        assertTrue(response.getBoolean("locked"))
        assertEquals("LOCKED", response.getString("state"))
    }

    @Test
    fun `a locked vault answers nothing else`() {
        for (action in listOf("match", "secret", "lock")) {
            val response = ask(
                JSONObject().put("action", action).put("url", "https://github.com").put("id", "gh"),
                ServiceState.LOCKED
            )
            assertFalse("$action answered while locked", response.getBoolean("ok"))
        }
    }

    @Test
    fun `matching returns names and usernames, never passwords`() {
        val response = match("https://github.com/login")
        val credentials = response.getJSONArray("credentials")

        assertEquals(1, credentials.length())
        val entry = credentials.getJSONObject(0)
        assertEquals("GitHub", entry.getString("name"))
        assertEquals("okan", entry.getString("username"))

        // The whole point of splitting match from secret. A page that reaches the bridge
        // learns that a credential exists, not what it is.
        assertFalse("a password came back with the match list", entry.has("password"))
        assertFalse(response.toString().contains("github-secret"))
    }

    @Test
    fun `a lookalike domain matches nothing`() {
        // Finding #10, from the direction that matters most: a browser. This is delegated
        // to CredentialMatcher rather than re-decided here, and the assertion is that the
        // delegation actually happens.
        assertEquals(0, match("https://notgithub.com/login").getJSONArray("credentials").length())
        assertEquals(0, match("https://github.com.evil.test/login").getJSONArray("credentials").length())
    }

    @Test
    fun `a subdomain of a known host still matches`() {
        assertEquals(1, match("https://gist.github.com/").getJSONArray("credentials").length())
    }

    @Test
    fun `the secret comes back only when asked for by id`() {
        val response = ask(JSONObject().put("action", "secret").put("id", "gh"))

        assertTrue(response.getBoolean("ok"))
        assertEquals("github-secret", response.getString("password"))
        assertEquals("okan", response.getString("username"))
    }

    @Test
    fun `an unknown id gets nothing`() {
        val response = ask(JSONObject().put("action", "secret").put("id", "not-a-real-id"))
        assertFalse(response.getBoolean("ok"))
    }

    @Test
    fun `a secret without an id is refused`() {
        assertFalse(ask(JSONObject().put("action", "secret")).getBoolean("ok"))
    }

    @Test
    fun `a match without a usable url is refused`() {
        assertFalse(ask(JSONObject().put("action", "match")).getBoolean("ok"))
        assertFalse(match("not a url at all").getBoolean("ok"))
        assertFalse(match("about:blank").getBoolean("ok"))
    }

    @Test
    fun `an unknown action is refused by name`() {
        val response = ask(JSONObject().put("action", "exfiltrate"))
        assertFalse(response.getBoolean("ok"))
        assertTrue(response.getString("error").contains("exfiltrate"))
    }

    @Test
    fun `no action at all is refused`() {
        assertFalse(ask(JSONObject()).getBoolean("ok"))
    }

    @Test
    fun `lock is honoured`() {
        var locked = false
        val response = ask(JSONObject().put("action", "lock"), onLock = { locked = true })

        assertTrue(response.getBoolean("ok"))
        assertTrue(locked)
    }

    @Test
    fun `hosts are parsed, not pattern-matched`() {
        assertEquals("github.com", BridgeProtocol.hostOf("https://github.com/login?a=b"))
        assertEquals("gist.github.com", BridgeProtocol.hostOf("https://GIST.GitHub.com/"))

        // The string contains "github.com" and the host is not it.
        assertEquals("github.com.evil.test", BridgeProtocol.hostOf("https://github.com.evil.test/"))
        assertNull(BridgeProtocol.hostOf("about:blank"))
        assertNull(BridgeProtocol.hostOf(""))
    }
}
