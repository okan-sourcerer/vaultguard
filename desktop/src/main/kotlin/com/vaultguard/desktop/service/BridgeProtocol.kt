package com.vaultguard.desktop.service

import com.vaultguard.app.autofill.CredentialMatcher
import com.vaultguard.app.domain.model.Credential
import org.json.JSONArray
import org.json.JSONObject

/**
 * What the browser extension may ask the service, and what it gets back.
 *
 * Pure: a request in, a response out, no sockets. That is what makes the rules below
 * testable, and they are the security surface of the whole extension — everything past this
 * point runs in a browser, where a compromised page is a normal Tuesday.
 *
 * ## Matching never returns a password
 *
 * `match` answers with names and usernames only. The password comes from a separate
 * `secret` call naming one id, which the extension makes only when the user has picked an
 * entry. So a page that somehow reaches the bridge learns, at worst, that a credential
 * exists — not what it is. Returning passwords with the match list would have been one
 * round-trip fewer and would have put every matching password into the browser's memory on
 * every page load.
 *
 * ## Matching is not done here
 *
 * It delegates to [CredentialMatcher] in `:core`, the same code the Android autofill
 * service uses. Findings #10 and #11 were both a second, looser copy of this decision;
 * a copy written in JavaScript would be a third.
 */
object BridgeProtocol {

    const val VERSION = 1

    /** Actions the extension may ask for. Anything else is refused by name. */
    object Action {
        const val STATUS = "status"
        const val MATCH = "match"
        const val SECRET = "secret"
        const val LOCK = "lock"

        /**
         * A login the extension saw being submitted. The service decides whether it is
         * new, changed, or already known, and asks the user in its own window; the
         * extension gets an acknowledgement and nothing else - never what was decided.
         */
        const val SAVE = "save"

        /**
         * Bring the tray's window up - unlocking first if need be. Not for the extension:
         * [NativeHost] refuses to relay it, so only a local process holding the token (a
         * second launch of VaultGuard itself) can send it. It reads nothing.
         */
        const val OPEN = "open"
    }

    /** What a browser may ask through the native host. [Action.OPEN] is deliberately absent. */
    val RELAYABLE: Set<String> = setOf(Action.STATUS, Action.MATCH, Action.SECRET, Action.LOCK, Action.SAVE)

    fun error(message: String): JSONObject =
        JSONObject().put("ok", false).put("error", message)

    private fun ok(): JSONObject = JSONObject().put("ok", true)

    /**
     * Handles one request.
     *
     * @param credentials supplies the vault, and is expected to count as use — see
     *        [VaultService.credentials]. It is a lambda rather than a list so that an
     *        unlocked check happens before the vault is touched at all.
     */
    fun handle(
        request: JSONObject,
        state: ServiceState,
        credentials: () -> List<Credential>,
        lock: () -> Unit,
        open: () -> Unit = {},
        capture: (Capture) -> Unit = {}
    ): JSONObject {
        val action = request.optString("action")

        if (action == Action.OPEN) {
            open()
            return ok()
        }

        if (action == Action.STATUS) {
            return ok()
                .put("version", VERSION)
                .put("state", state.name)
                .put("locked", state != ServiceState.UNLOCKED)
        }

        // Everything below reads the vault. Checked once, here, rather than in each branch.
        if (state != ServiceState.UNLOCKED) {
            return error("The vault is locked. Unlock it from the VaultGuard tray icon.")
        }

        return when (action) {
            Action.LOCK -> {
                lock()
                ok()
            }

            Action.SAVE -> {
                val url = request.optString("url").takeIf { it.isNotEmpty() }
                    ?: return error("A save needs a url.")
                val host = hostOf(url) ?: return error("That url has no host.")
                val password = request.optString("password")
                if (password.isEmpty()) return error("A save needs a password.")
                capture(Capture(host, request.optString("username"), password))
                ok()
            }

            Action.MATCH -> {
                val url = request.optString("url").takeIf { it.isNotEmpty() }
                    ?: return error("A match needs a url.")

                val host = hostOf(url)
                    ?: return error("That url has no host to match against.")

                // Never a package name from a browser: reverse-DNS derivation is for
                // Android callers, and letting a page supply one would open a matching path
                // the browser has no business reaching.
                val matches = CredentialMatcher.match(credentials(), webDomain = host, packageName = null)

                ok().put("host", host).put(
                    "credentials",
                    JSONArray().apply { matches.forEach { put(summaryOf(it)) } }
                )
            }

            Action.SECRET -> {
                val id = request.optString("id").takeIf { it.isNotEmpty() }
                    ?: return error("A secret needs an id.")

                val credential = credentials().firstOrNull { it.id == id }
                    ?: return error("No such entry.")

                ok()
                    .put("id", credential.id)
                    .put("username", credential.username)
                    .put("password", credential.password)
            }

            "" -> error("No action given.")
            else -> error("Unknown action: $action")
        }
    }

    /** A submitted login, as the tray receives it. The host is the tab's, never the page's claim. */
    data class Capture(val host: String, val username: String, val password: String)

    /** Everything the extension needs to render a chooser, and nothing more. */
    private fun summaryOf(credential: Credential): JSONObject = JSONObject()
        .put("id", credential.id)
        .put("name", credential.displayName)
        .put("username", credential.username)

    /**
     * The host of a url, or null.
     *
     * Parsed rather than pattern-matched, and the result is handed to [CredentialMatcher],
     * which compares on dot boundaries. `java.net.URI` will not be fooled by
     * `https://github.com.evil.test/` into reporting `github.com`, which a regex over the
     * string very well might.
     */
    fun hostOf(url: String): String? = try {
        java.net.URI(url).host?.lowercase()?.takeIf { it.isNotEmpty() }
    } catch (e: Exception) {
        null
    }
}
