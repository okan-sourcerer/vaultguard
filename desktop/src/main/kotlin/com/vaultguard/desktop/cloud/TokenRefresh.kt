package com.vaultguard.desktop.cloud

import org.json.JSONObject
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse

/**
 * The saved refresh token was rejected.
 *
 * Recoverable by signing in again, and not a fault to report as one: a refresh token can be
 * revoked from a Google account page, expire after long disuse, or belong to a Firebase
 * project that has moved on.
 */
class RefreshRejectedException(message: String) : Exception(message)

/**
 * Exchanges a refresh token for a fresh Firebase session, with no user interaction.
 *
 * Firebase `idToken`s last an hour. Without this the client would have to send the user
 * back through a browser sign-in every hour, and — before this existed — did exactly that
 * on every single launch.
 */
class TokenRefresh(
    private val config: DesktopConfig,
    private val http: HttpClient = HttpClient.newHttpClient()
) {
    companion object {
        private const val ENDPOINT = "https://securetoken.googleapis.com/v1/token"
    }

    fun exchange(refreshToken: String, email: String?): FirebaseSession {
        val form = "grant_type=refresh_token&refresh_token=${encode(refreshToken)}"

        val request = HttpRequest.newBuilder(URI("$ENDPOINT?key=${config.apiKey}"))
            .header("Content-Type", "application/x-www-form-urlencoded")
            .POST(HttpRequest.BodyPublishers.ofString(form))
            .build()

        val response = http.send(request, HttpResponse.BodyHandlers.ofString())
        if (response.statusCode() !in 200..299) {
            throw RefreshRejectedException(describe(response.body()))
        }

        val body = JSONObject(response.body())
        val idToken = body.optString("id_token", "")
        val uid = body.optString("user_id", "")
        if (idToken.isEmpty() || uid.isEmpty()) {
            throw RefreshRejectedException("The refresh returned an incomplete session.")
        }

        return FirebaseSession(
            idToken = idToken,
            uid = uid,
            email = email,
            // Firebase may hand back a rotated token; keeping the new one means a session
            // that is used regularly never has to be re-established.
            refreshToken = body.optString("refresh_token").takeIf { it.isNotEmpty() } ?: refreshToken
        )
    }

    private fun describe(body: String): String = try {
        when (val message = JSONObject(body).optJSONObject("error")?.optString("message").orEmpty()) {
            "TOKEN_EXPIRED", "INVALID_REFRESH_TOKEN" ->
                "The saved sign-in is no longer valid."
            "USER_DISABLED" -> "That account has been disabled."
            else -> message.ifEmpty { "The saved sign-in was rejected." }
        }
    } catch (e: Exception) {
        "The saved sign-in was rejected."
    }

    private fun encode(value: String): String = URLEncoder.encode(value, Charsets.UTF_8)
}
