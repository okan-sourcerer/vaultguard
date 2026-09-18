package com.vaultguard.app.feedback

import org.json.JSONObject
import java.util.UUID

/**
 * What "Send feedback" sends, and the whole of it.
 *
 * This is a password manager, so the report is deliberately narrower than what the hub
 * accepts. No log lines, no stack trace, no free-form metadata, no route, no account
 * identifier: every one of those is a place where a site name, a username or worse could
 * ride along, and the hub's suggested "last 200 log lines" would be exactly that. The user
 * types the message; the rest is the version and the platform, which is what makes a bug
 * report actionable and nothing else.
 *
 * Shared by the phone and the desktop so the two cannot disagree about what leaves the
 * device. The JSON is shown to the user before it is sent, verbatim, so [toJson] is the
 * contract rather than a serialisation detail.
 */
data class FeedbackReport(
    val type: Type,
    val message: String,
    val appVersion: String,
    val environment: Environment,
    val platform: Platform,
    val os: String,
    /** Hardware model on Android; null on desktop, where it says little and identifies more. */
    val device: String? = null,
    val locale: String? = null,
    val timezone: String? = null,
    /**
     * Generated once per report and kept across retries, so a send that timed out after
     * the hub stored it does not store it twice.
     */
    val idempotencyKey: String = UUID.randomUUID().toString()
) {
    enum class Type(val wire: String, val label: String) {
        BUG("bug", "Bug"),
        FEATURE("feature", "Feature request"),
        QUESTION("question", "Question"),
        PRAISE("praise", "Praise"),
        OTHER("other", "Other")
    }

    enum class Environment(val wire: String) { PROD("prod"), DEV("dev") }

    enum class Platform(val wire: String) { ANDROID("android"), DESKTOP("desktop") }

    fun toJson(): JSONObject = JSONObject().apply {
        put("type", type.wire)
        put("message", message)
        put("app_version", appVersion)
        put("environment", environment.wire)
        put("platform", platform.wire)
        put("os", os)
        device?.let { put("device", it) }
        locale?.let { put("locale", it) }
        timezone?.let { put("timezone", it) }
        put("idempotency_key", idempotencyKey)
    }

    companion object {
        /** The hub's limit; the UI stops typing there rather than letting the send fail. */
        const val MAX_MESSAGE_LENGTH = 10_000
    }
}
