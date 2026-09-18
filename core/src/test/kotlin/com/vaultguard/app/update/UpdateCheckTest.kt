package com.vaultguard.app.update

import com.sun.net.httpserver.HttpServer
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.net.InetSocketAddress
import java.nio.file.Files

class UpdateCheckTest {

    private lateinit var server: HttpServer
    private var body = ""
    private var status = 200
    private var userAgent: String? = null

    @Before
    fun start() {
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange ->
            userAgent = exchange.requestHeaders.getFirst("User-Agent")
            val bytes = body.toByteArray()
            exchange.sendResponseHeaders(status, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.start()
    }

    @After
    fun stop() = server.stop(0)

    private fun check(current: String) =
        UpdateCheck(current, repository = "o/r", apiBase = "http://127.0.0.1:${server.address.port}")

    private fun release(tag: String) = JSONObject()
        .put("tag_name", tag)
        .put("html_url", "https://github.com/o/r/releases/tag/$tag")
        .put(
            "assets", org.json.JSONArray()
                .put(JSONObject().put("name", "VaultGuard-windows.msi").put("browser_download_url", "https://x/msi"))
                .put(JSONObject().put("name", "SHA256SUMS").put("browser_download_url", "https://x/sums"))
        ).toString()

    @Test
    fun `versions compare numerically, with or without the v`() {
        assertEquals(Version(1, 2, 3), Version.parse("v1.2.3"))
        assertEquals(Version(1, 2, 3), Version.parse("1.2.3"))
        assertEquals(Version(1, 0, 0), Version.parse("1"))
        assertEquals(Version(0, 0, 42), Version.parse("0.0.42-manual"))
        assertNull(Version.parse("dev"))
        assertTrue(Version.parse("1.10.0")!! > Version.parse("1.9.9")!!)
        assertTrue(Version.parse("2.0.0")!! > Version.parse("1.99.99")!!)
    }

    @Test
    fun `a newer release is reported with its assets`() {
        body = release("v1.1.0")

        val result = check("1.0.0").check() as UpdateCheck.Result.Available
        assertEquals(Version(1, 1, 0), result.release.version)
        assertEquals("https://x/msi", result.release.assets["VaultGuard-windows.msi"])
        assertEquals("https://x/sums", result.release.assets["SHA256SUMS"])
        assertEquals("VaultGuard/1.0.0", userAgent)
    }

    @Test
    fun `the same or an older release means up to date`() {
        body = release("v1.0.0")
        assertTrue(check("1.0.0").check() is UpdateCheck.Result.UpToDate)
        assertTrue(check("1.0.1").check() is UpdateCheck.Result.UpToDate)
    }

    @Test
    fun `a build without a version does not ask the network`() {
        body = release("v9.9.9")
        val result = check("dev").check()
        assertTrue(result is UpdateCheck.Result.Failed)
        assertNull(userAgent)
    }

    @Test
    fun `an unhappy GitHub is a failure, not an update and not a crash`() {
        status = 403
        body = """{"message":"rate limited"}"""
        assertTrue(check("1.0.0").check() is UpdateCheck.Result.Failed)

        status = 200
        body = "<html>maintenance</html>"
        assertTrue(check("1.0.0").check() is UpdateCheck.Result.Failed)
    }

    @Test
    fun `sums parse as sha256sum writes them and verify a file`() {
        val dir = Files.createTempDirectory("sums").toFile()
        val file = File(dir, "VaultGuard-windows.msi").apply { writeText("hello") }
        val hello = "2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824"

        val sums = Sha256Sums.parse(
            """
            $hello  VaultGuard-windows.msi
            ${"0".repeat(64)}  VaultGuard-macos.dmg
            not a sums line
            """.trimIndent()
        )
        assertEquals(hello, sums["VaultGuard-windows.msi"])
        assertEquals(2, sums.size)
        assertTrue(Sha256Sums.matches(file, hello))
        assertTrue(Sha256Sums.matches(file, hello.uppercase()))
        assertFalse(Sha256Sums.matches(file, "0".repeat(64)))
    }
}
