package com.vaultguard.desktop.cloud

import com.sun.net.httpserver.HttpServer
import org.json.JSONObject
import java.awt.Desktop
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.concurrent.SynchronousQueue
import java.util.concurrent.TimeUnit

class OAuthException(message: String) : Exception(message)

/**
 * The PKCE pair for one authorisation attempt.
 *
 * The verifier never leaves this process until the code is exchanged, so an attacker who
 * intercepts the redirect still cannot redeem the authorisation code. That matters more
 * than usual here: the redirect arrives over plain HTTP on the loopback interface, which
 * is the standard arrangement for installed apps and the reason PKCE exists.
 */
data class Pkce(val verifier: String, val challenge: String) {
    companion object {
        private val random = SecureRandom()
        private val encoder: Base64.Encoder = Base64.getUrlEncoder().withoutPadding()

        fun generate(): Pkce {
            val verifier = encoder.encodeToString(ByteArray(64).also { random.nextBytes(it) })
            val digest = MessageDigest.getInstance("SHA-256")
                .digest(verifier.toByteArray(Charsets.US_ASCII))
            return Pkce(verifier, encoder.encodeToString(digest))
        }
    }
}

/** What Google returns once the browser round-trip is done. */
data class GoogleIdentity(val idToken: String, val email: String?)

/**
 * Signs in with Google using the loopback redirect that installed applications use: a
 * one-shot HTTP server on 127.0.0.1, an authorisation code delivered to it by the browser,
 * and a token exchange that proves possession of the PKCE verifier.
 *
 * Bound to the loopback address specifically, never to `0.0.0.0` — the port is open for a
 * few seconds and anything else would expose the redirect to the network.
 */
class GoogleOAuth(
    private val config: DesktopConfig,
    private val http: HttpClient = HttpClient.newHttpClient()
) {
    companion object {
        private const val AUTH_ENDPOINT = "https://accounts.google.com/o/oauth2/v2/auth"
        private const val TOKEN_ENDPOINT = "https://oauth2.googleapis.com/token"
        private const val SCOPE = "openid email"
        private val TIMEOUT_SECONDS = TimeUnit.MINUTES.toSeconds(5)
    }

    fun signIn(announce: (String) -> Unit): GoogleIdentity {
        val pkce = Pkce.generate()
        val state = Pkce.generate().verifier

        val server = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0)
        val redirectUri = "http://127.0.0.1:${server.address.port}"
        val received = SynchronousQueue<Result<String>>()

        server.createContext("/") { exchange ->
            val query = exchange.requestURI.query.orEmpty().split("&")
                .mapNotNull { it.split("=", limit = 2).takeIf { part -> part.size == 2 } }
                .associate { java.net.URLDecoder.decode(it[0], Charsets.UTF_8) to java.net.URLDecoder.decode(it[1], Charsets.UTF_8) }

            val outcome = when {
                query["state"] != state ->
                    Result.failure(OAuthException("Redirect carried the wrong state value."))
                query["error"] != null ->
                    Result.failure(OAuthException("Google reported: ${query["error"]}"))
                query["code"] == null ->
                    Result.failure(OAuthException("Redirect carried no authorisation code."))
                else -> Result.success(query.getValue("code"))
            }

            val body = if (outcome.isSuccess) {
                "<h2>Signed in</h2><p>You can close this tab and go back to the terminal.</p>"
            } else {
                "<h2>Sign-in failed</h2><p>Go back to the terminal for the reason.</p>"
            }
            val bytes = "<!doctype html><meta charset=utf-8>$body".toByteArray()
            exchange.responseHeaders.add("Content-Type", "text/html; charset=utf-8")
            exchange.sendResponseHeaders(if (outcome.isSuccess) 200 else 400, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }

            received.offer(outcome)
        }

        server.start()
        try {
            val authUrl = buildString {
                append(AUTH_ENDPOINT)
                append("?client_id=").append(encode(config.oauthClientId))
                append("&redirect_uri=").append(encode(redirectUri))
                append("&response_type=code")
                append("&scope=").append(encode(SCOPE))
                append("&code_challenge=").append(encode(pkce.challenge))
                append("&code_challenge_method=S256")
                append("&state=").append(encode(state))
                // Google will not return an id_token for an account that has never
                // consented; asking explicitly avoids a silent empty response.
                append("&prompt=select_account")
            }

            openBrowser(authUrl, announce)

            val code = received.poll(TIMEOUT_SECONDS, TimeUnit.SECONDS)
                ?: throw OAuthException("Timed out waiting for the browser redirect.")

            return exchangeCode(code.getOrThrow(), pkce.verifier, redirectUri)
        } finally {
            server.stop(0)
        }
    }

    private fun openBrowser(url: String, announce: (String) -> Unit) {
        val opened = try {
            if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
                Desktop.getDesktop().browse(URI(url))
                true
            } else {
                false
            }
        } catch (e: Exception) {
            false
        }

        if (opened) {
            announce("Opened your browser to sign in. Waiting for the redirect...")
        } else {
            announce("Open this URL to sign in:\n\n$url\n")
        }
    }

    private fun exchangeCode(code: String, verifier: String, redirectUri: String): GoogleIdentity {
        val form = mapOf(
            "code" to code,
            "client_id" to config.oauthClientId,
            "client_secret" to config.oauthClientSecret,
            "redirect_uri" to redirectUri,
            "grant_type" to "authorization_code",
            "code_verifier" to verifier
        ).map { (key, value) -> "${encode(key)}=${encode(value)}" }.joinToString("&")

        val request = HttpRequest.newBuilder(URI(TOKEN_ENDPOINT))
            .header("Content-Type", "application/x-www-form-urlencoded")
            .POST(HttpRequest.BodyPublishers.ofString(form))
            .build()

        val response = http.send(request, HttpResponse.BodyHandlers.ofString())
        if (response.statusCode() !in 200..299) {
            throw OAuthException("Token exchange failed (HTTP ${response.statusCode()}). ${describe(response.body())}")
        }

        val body = JSONObject(response.body())
        val idToken = body.optString("id_token", "")
        if (idToken.isEmpty()) {
            throw OAuthException("Token exchange returned no id_token. Check the client is of type \"Desktop app\".")
        }
        return GoogleIdentity(idToken = idToken, email = emailFrom(idToken))
    }

    /**
     * Reads the `email` claim for display only. The token is not verified here — it is
     * handed straight to Firebase, which does verify it, and nothing is trusted on the
     * strength of this read.
     */
    private fun emailFrom(idToken: String): String? = try {
        val payload = idToken.split(".").getOrNull(1) ?: return null
        val json = String(Base64.getUrlDecoder().decode(payload), Charsets.UTF_8)
        JSONObject(json).optString("email").takeIf { it.isNotEmpty() }
    } catch (e: Exception) {
        null
    }

    private fun describe(body: String): String = try {
        val json = JSONObject(body)
        json.optString("error_description").ifEmpty { json.optString("error") }
    } catch (e: Exception) {
        ""
    }

    private fun encode(value: String): String = URLEncoder.encode(value, Charsets.UTF_8)
}
