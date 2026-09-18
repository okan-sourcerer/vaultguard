package com.vaultguard.app.feedback

import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * `POST /api/feedback` on the hub.
 *
 * `HttpURLConnection` because both clients already have it: Android does not ship
 * `java.net.http`, and adding an HTTP library to the APK for one request a month is a
 * dependency the vault does not need. Blocking; the caller picks the thread.
 *
 * The key permits writing feedback under one app id and nothing else, which is why it can
 * be baked into a build that anyone can download.
 */
class FeedbackClient(
    private val endpoint: String,
    private val key: String,
    private val timeoutMillis: Int = 15_000
) {

    sealed class Result {
        data class Sent(val id: String, val duplicate: Boolean) : Result()

        /** The hub answered and said no: bad key, validation, rate limit. */
        data class Rejected(val status: Int, val detail: String) : Result()

        /** No usable answer at all. Safe to retry with the same report. */
        data class Failed(val reason: String) : Result()
    }

    val isConfigured: Boolean get() = endpoint.isNotBlank() && key.isNotBlank()

    fun send(report: FeedbackReport): Result {
        if (!isConfigured) return Result.Failed("No feedback hub is configured in this build.")

        val body = report.toJson().toString().toByteArray(Charsets.UTF_8)
        val connection = try {
            (URL(endpoint).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = timeoutMillis
                readTimeout = timeoutMillis
                doOutput = true
                setRequestProperty("Content-Type", "application/json")
                setRequestProperty("Authorization", "Bearer $key")
                setFixedLengthStreamingMode(body.size)
            }
        } catch (e: Exception) {
            return Result.Failed(e.message ?: e.javaClass.simpleName)
        }

        return try {
            connection.outputStream.use { it.write(body) }
            val status = connection.responseCode
            val text = (if (status < 400) connection.inputStream else connection.errorStream)
                ?.bufferedReader()?.use { it.readText() }.orEmpty()
            when (status) {
                201, 200 -> {
                    val json = runCatching { JSONObject(text) }.getOrNull()
                    Result.Sent(
                        id = json?.optString("id").orEmpty(),
                        duplicate = json?.optBoolean("duplicate", false) ?: false
                    )
                }
                else -> Result.Rejected(status, describe(status, text))
            }
        } catch (e: IOException) {
            Result.Failed(e.message ?: e.javaClass.simpleName)
        } finally {
            connection.disconnect()
        }
    }

    private fun describe(status: Int, text: String): String = when (status) {
        401 -> "The hub did not accept this build's key."
        422 -> "The hub rejected the report: " +
            (runCatching { JSONObject(text).optJSONArray("issues")?.toString() }.getOrNull() ?: text)
        429 -> "Too many reports right now; try again in a minute."
        else -> "HTTP $status"
    }
}
