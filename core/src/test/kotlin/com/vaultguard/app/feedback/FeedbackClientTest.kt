package com.vaultguard.app.feedback

import com.sun.net.httpserver.HttpServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.net.InetSocketAddress

/**
 * Against a loopback server rather than a mocked connection: the headers and the body
 * bytes on the wire are the contract, and a mock of `HttpURLConnection` would only prove
 * the test agrees with itself.
 */
class FeedbackClientTest {

    private lateinit var server: HttpServer
    private var received: String? = null
    private var authorization: String? = null
    private var contentType: String? = null
    private var respondWith: Pair<Int, String> = 201 to """{"id":"fb_1"}"""

    private val report = FeedbackReport(
        type = FeedbackReport.Type.FEATURE,
        message = "Please add TOTP.",
        appVersion = "2.0.0",
        environment = FeedbackReport.Environment.DEV,
        platform = FeedbackReport.Platform.DESKTOP,
        os = "Linux",
        idempotencyKey = "k-1"
    )

    @Before
    fun start() {
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/api/feedback") { exchange ->
            received = exchange.requestBody.readBytes().toString(Charsets.UTF_8)
            authorization = exchange.requestHeaders.getFirst("Authorization")
            contentType = exchange.requestHeaders.getFirst("Content-Type")
            val (status, body) = respondWith
            val bytes = body.toByteArray()
            exchange.sendResponseHeaders(status, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.start()
    }

    @After
    fun stop() = server.stop(0)

    private fun client() = FeedbackClient("http://127.0.0.1:${server.address.port}/api/feedback", "cwb_test")

    @Test
    fun `posts the report as JSON with the bearer key`() {
        val result = client().send(report)

        assertEquals(FeedbackClient.Result.Sent("fb_1", duplicate = false), result)
        assertEquals("Bearer cwb_test", authorization)
        assertEquals("application/json", contentType)
        assertEquals(report.toJson().toString(), received)
    }

    @Test
    fun `a retried key is reported as sent, not as an error`() {
        respondWith = 200 to """{"id":"fb_1","duplicate":true}"""

        assertEquals(FeedbackClient.Result.Sent("fb_1", duplicate = true), client().send(report))
    }

    @Test
    fun `a refusal carries the status and a reason`() {
        respondWith = 401 to """{"error":"bad key"}"""

        val result = client().send(report) as FeedbackClient.Result.Rejected
        assertEquals(401, result.status)
        assertTrue(result.detail.contains("key"))
    }

    @Test
    fun `nothing listening is a failure, not a crash`() {
        val result = FeedbackClient("http://127.0.0.1:1/api/feedback", "cwb_test", timeoutMillis = 2_000).send(report)

        assertTrue(result is FeedbackClient.Result.Failed)
    }

    @Test
    fun `an unconfigured build refuses before touching the network`() {
        assertTrue(FeedbackClient("", "").send(report) is FeedbackClient.Result.Failed)
    }
}
