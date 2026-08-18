package com.vaultguard.desktop.cloud

import org.json.JSONArray
import org.json.JSONObject
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse

class FirestoreException(message: String) : Exception(message)

/**
 * The read half of the Firestore REST API, which is all this client needs.
 *
 * There is deliberately no write path here yet. Reading proves the chain end to end — sign
 * in, fetch config, unwrap the vault key, decrypt — against a live vault without being able
 * to damage it. Push comes after that is known good.
 */
class FirestoreClient(
    private val config: DesktopConfig,
    private val session: FirebaseSession,
    private val http: HttpClient = HttpClient.newHttpClient()
) {
    companion object {
        private const val BASE = "https://firestore.googleapis.com/v1"

        /** Firestore's own cap is 300 for a list; asking for more is silently clamped. */
        private const val PAGE_SIZE = 300
    }

    private val documentsRoot: String
        get() = "$BASE/projects/${config.projectId}/databases/(default)/documents"

    /** @return null when the vault document does not exist. */
    fun vaultDocument(): JSONObject? {
        val response = get("$documentsRoot/vaults/${encode(session.uid)}")
        if (response.statusCode() == 404) return null
        requireSuccess(response, "read the vault document")
        return JSONObject(response.body())
    }

    /**
     * Every credential document, following pagination to the end.
     *
     * A partial list would present as a vault with entries missing, which is the failure
     * mode this codebase treats most seriously — so a page that fails throws rather than
     * returning what arrived so far.
     */
    fun credentialDocuments(): List<JSONObject> {
        val documents = mutableListOf<JSONObject>()
        var pageToken: String? = null

        do {
            val token = pageToken
            val url = buildString {
                append(documentsRoot).append("/vaults/").append(encode(session.uid)).append("/credentials")
                append("?pageSize=").append(PAGE_SIZE)
                if (token != null) append("&pageToken=").append(encode(token))
            }

            val response = get(url)
            if (response.statusCode() == 404) return documents
            requireSuccess(response, "list credentials")

            val body = JSONObject(response.body())
            val page: JSONArray = body.optJSONArray("documents") ?: JSONArray()
            for (index in 0 until page.length()) documents += page.getJSONObject(index)

            pageToken = body.optString("nextPageToken").takeIf { it.isNotEmpty() }
        } while (pageToken != null)

        return documents
    }

    private fun get(url: String): HttpResponse<String> {
        val request = HttpRequest.newBuilder(URI(url))
            .header("Authorization", "Bearer ${session.idToken}")
            .GET()
            .build()
        return http.send(request, HttpResponse.BodyHandlers.ofString())
    }

    private fun requireSuccess(response: HttpResponse<String>, what: String) {
        if (response.statusCode() in 200..299) return

        val detail = try {
            JSONObject(response.body()).optJSONObject("error")?.optString("message").orEmpty()
        } catch (e: Exception) {
            ""
        }

        val hint = when (response.statusCode()) {
            401 -> " The session may have expired; sign in again."
            403 -> " Firestore's security rules refused this read. Check that vaults/{uid} " +
                "allows request.auth.uid == uid, and that you signed in with the same " +
                "Google account as the phone."
            else -> ""
        }

        throw FirestoreException("Could not $what (HTTP ${response.statusCode()}). $detail$hint".trim())
    }

    private fun encode(value: String): String = URLEncoder.encode(value, Charsets.UTF_8)
}
