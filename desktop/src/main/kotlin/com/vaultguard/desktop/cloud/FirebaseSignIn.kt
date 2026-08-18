package com.vaultguard.desktop.cloud

import org.json.JSONObject
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse

class SignInFailedException(message: String) : Exception(message)

/**
 * A Firebase session: the bearer token Firestore wants, and the uid the vault is filed
 * under.
 *
 * The uid is the part that matters for reaching an existing vault. Firebase assigns one
 * uid per Google account per project, so signing in here with the same account the phone
 * uses lands on the same `vaults/{uid}` document. A different account is not a
 * misconfiguration to work around — it is a different vault.
 */
data class FirebaseSession(
    val idToken: String,
    val uid: String,
    val email: String?,
    /**
     * Long-lived, and the reason signing in is a one-time event rather than a per-launch
     * browser round-trip. The `idToken` beside it expires after an hour; this exchanges for
     * a fresh one without any user interaction.
     *
     * Never written to disk in the clear — see [SavedSession].
     */
    val refreshToken: String? = null
)

/**
 * Exchanges a Google id_token for a Firebase session over the Identity Toolkit REST API.
 *
 * The Android app gets here through the Firebase SDK and Google Play services, neither of
 * which exists off Android. The REST endpoint is the same service underneath, so the
 * resulting session is indistinguishable from the phone's as far as Firestore's security
 * rules are concerned — which is the point: the rules stay in force.
 */
class FirebaseSignIn(
    private val config: DesktopConfig,
    private val http: HttpClient = HttpClient.newHttpClient()
) {
    companion object {
        private const val ENDPOINT = "https://identitytoolkit.googleapis.com/v1/accounts:signInWithIdp"
    }

    fun exchange(identity: GoogleIdentity): FirebaseSession {
        val payload = JSONObject()
            .put("postBody", "id_token=${identity.idToken}&providerId=google.com")
            // Not a real destination: the field exists so the service can echo a redirect
            // for web flows. Loopback is accurate enough and goes nowhere.
            .put("requestUri", "http://127.0.0.1")
            .put("returnSecureToken", true)

        val request = HttpRequest.newBuilder(URI("$ENDPOINT?key=${config.apiKey}"))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(payload.toString()))
            .build()

        val response = http.send(request, HttpResponse.BodyHandlers.ofString())
        if (response.statusCode() !in 200..299) {
            throw SignInFailedException(
                "Firebase refused the sign-in (HTTP ${response.statusCode()}). " +
                    describeError(response.body())
            )
        }

        val body = JSONObject(response.body())
        val idToken = body.optString("idToken", "")
        val uid = body.optString("localId", "")
        if (idToken.isEmpty() || uid.isEmpty()) {
            throw SignInFailedException("Firebase returned an incomplete session.")
        }

        return FirebaseSession(
            idToken = idToken,
            uid = uid,
            email = body.optString("email").ifEmpty { identity.email },
            refreshToken = body.optString("refreshToken").takeIf { it.isNotEmpty() }
        )
    }

    private fun describeError(body: String): String = try {
        val message = JSONObject(body).optJSONObject("error")?.optString("message").orEmpty()
        when (message) {
            "INVALID_IDP_RESPONSE" ->
                "The Google token was rejected. Check the OAuth client belongs to this Firebase project."
            "OPERATION_NOT_ALLOWED" ->
                "Google sign-in is not enabled for this project in the Firebase console."
            else -> message
        }
    } catch (e: Exception) {
        ""
    }
}
