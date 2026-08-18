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
 * The parts of the Firestore REST API this client needs: reads, and creating a credential.
 *
 * The write surface is deliberately one operation wide. Creating a row under a fresh UUID
 * cannot collide with anything, so it never reaches the two-writer conflict rules in
 * `SyncMerge` — which are well tested but have never run against two real writers. Editing
 * and deleting are the operations that would, and they are not here.
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

    /**
     * Creates one credential document.
     *
     * Goes through `:commit` rather than a plain PATCH because only a commit can carry an
     * `updateTransforms`, and the server timestamp is not optional — see
     * [FirestoreWrites.SERVER_TIME_TRANSFORM]. The write also carries a create-only
     * precondition, so this can add a row and can never replace one.
     */
    fun createCredential(row: RemoteCredentialRow) {
        val name = "projects/${config.projectId}/databases/(default)/documents" +
            "/vaults/${session.uid}/credentials/${row.id}"

        val body = FirestoreWrites.commitBody(
            listOf(FirestoreWrites.createCredentialWrite(name, row))
        )

        val request = HttpRequest.newBuilder(URI("$documentsRoot:commit"))
            .header("Authorization", "Bearer ${session.idToken}")
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body.toString()))
            .build()

        val response = http.send(request, HttpResponse.BodyHandlers.ofString())
        requireSuccess(response, "create the credential")
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

        val hint = when {
            response.statusCode() == 401 -> " The session may have expired; sign in again."

            response.statusCode() == 403 ->
                " Firestore's security rules refused this. Check that vaults/{uid} allows " +
                    "request.auth.uid == uid for the credentials subcollection as well as " +
                    "the document, and that you signed in with the same Google account as " +
                    "the phone."

            // The create-only precondition. For a freshly generated UUID this should be
            // unreachable, so it means something is wrong rather than something is racing.
            detail.contains("already exists", ignoreCase = true) ->
                " A document with that id already exists; nothing was overwritten."

            else -> ""
        }

        throw FirestoreException("Could not $what (HTTP ${response.statusCode()}). $detail$hint".trim())
    }

    private fun encode(value: String): String = URLEncoder.encode(value, Charsets.UTF_8)
}
