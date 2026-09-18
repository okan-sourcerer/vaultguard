package com.vaultguard.app.feedback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Test

class FeedbackReportTest {

    private val report = FeedbackReport(
        type = FeedbackReport.Type.BUG,
        message = "Autofill did not offer anything on the login page.",
        appVersion = "1.2.3",
        environment = FeedbackReport.Environment.PROD,
        platform = FeedbackReport.Platform.ANDROID,
        os = "Android 15",
        device = "Pixel 8",
        locale = "tr-TR",
        timezone = "Europe/Istanbul",
        idempotencyKey = "fixed-key"
    )

    private fun minimal() = FeedbackReport(
        type = FeedbackReport.Type.OTHER,
        message = "x",
        appVersion = "1.0.0",
        environment = FeedbackReport.Environment.DEV,
        platform = FeedbackReport.Platform.DESKTOP,
        os = "Windows 11"
    )

    @Test
    fun `sends exactly the agreed fields and nothing else`() {
        val json = report.toJson()

        assertEquals(
            setOf(
                "type", "message", "app_version", "environment", "platform", "os",
                "device", "locale", "timezone", "idempotency_key"
            ),
            json.keySet()
        )
        assertEquals("bug", json.getString("type"))
        assertEquals("1.2.3", json.getString("app_version"))
        assertEquals("prod", json.getString("environment"))
        assertEquals("android", json.getString("platform"))
        assertEquals("fixed-key", json.getString("idempotency_key"))
    }

    @Test
    fun `never carries the fields the hub would accept but a vault must not send`() {
        val json = report.toJson()

        for (forbidden in listOf("logs", "stack_trace", "metadata", "route", "user_id", "user_email", "session_id")) {
            assertFalse("$forbidden must not be sent", json.has(forbidden))
        }
    }

    @Test
    fun `desktop reports omit the device and optional context`() {
        val json = minimal().toJson()

        assertFalse(json.has("device"))
        assertFalse(json.has("locale"))
        assertFalse(json.has("timezone"))
    }

    @Test
    fun `each report gets its own idempotency key`() {
        assertNotEquals(minimal().idempotencyKey, minimal().idempotencyKey)
    }
}
