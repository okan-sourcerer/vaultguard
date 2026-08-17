package com.vaultguard.app.security

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.security.MessageDigest

/**
 * Tests for the breach check (finding #14).
 *
 * The old result type had only a boolean, so every failure — no network, a 503, a garbled
 * response — became `isBreached = false` and the screen showed a green "Not found in any
 * breaches" for a check that never ran. False reassurance is worse than no feature: it
 * invites the user to stop worrying about a password nobody looked at.
 */
class BreachCheckServiceTest {

    private fun sha1(input: String): String =
        MessageDigest.getInstance("SHA-1")
            .digest(input.toByteArray())
            .joinToString("") { "%02X".format(it) }

    private fun service(source: PwnedRangeSource) = BreachCheckService(source)

    private fun sourceReturning(body: String) = PwnedRangeSource { body }

    private fun sourceThrowing(error: Exception) = object : PwnedRangeSource {
        override suspend fun fetch(prefix: String): String = throw error
    }

    /** A range containing [password]'s suffix with [count] occurrences. */
    private fun rangeContaining(password: String, count: Int, extra: String = ""): String {
        val suffix = sha1(password).substring(5)
        return buildString {
            append("0000000000000000000000000000000000A:12\n")
            append("$suffix:$count\n")
            append("FFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFF:3\n")
            append(extra)
        }
    }

    // -- Failures are not all-clears --------------------------------------------------

    @Test
    fun `a network failure reports unavailable, not safe`() = runTest {
        val result = service(sourceThrowing(IOException("Unable to resolve host"))).check("hunter2")

        assertTrue("must not be reported as safe", result is BreachCheckResult.Unavailable)
        assertTrue((result as BreachCheckResult.Unavailable).reason.isNotEmpty())
    }

    @Test
    fun `an http error reports unavailable`() = runTest {
        // The source raises for any non-200; it used to turn one into an empty body, which
        // read as "no match" and produced the same green tick as a real all-clear.
        val result = service(sourceThrowing(IOException("The breach database returned HTTP 503")))
            .check("hunter2")

        assertTrue(result is BreachCheckResult.Unavailable)
        assertTrue((result as BreachCheckResult.Unavailable).reason.contains("503"))
    }

    @Test
    fun `an unexpected error reports unavailable`() = runTest {
        val result = service(sourceThrowing(IllegalStateException("boom"))).check("hunter2")

        assertTrue(result is BreachCheckResult.Unavailable)
    }

    @Test
    fun `an empty response is a genuine all-clear`() = runTest {
        // A 200 with nothing in it really does mean no match in that range.
        assertEquals(BreachCheckResult.Safe, service(sourceReturning("")).check("hunter2"))
    }

    // -- Matching ------------------------------------------------------------------------

    @Test
    fun `a listed password is reported breached with its count`() = runTest {
        val result = service(sourceReturning(rangeContaining("hunter2", 17))).check("hunter2")

        assertEquals(BreachCheckResult.Breached(17), result)
    }

    @Test
    fun `an unlisted password is safe`() = runTest {
        val result = service(sourceReturning(rangeContaining("hunter2", 17))).check("a-different-one")

        assertEquals(BreachCheckResult.Safe, result)
    }

    @Test
    fun `matching ignores hash case`() = runTest {
        val body = rangeContaining("hunter2", 5).lowercase()

        assertEquals(BreachCheckResult.Breached(5), service(sourceReturning(body)).check("hunter2"))
    }

    @Test
    fun `carriage returns do not break parsing`() = runTest {
        val body = rangeContaining("hunter2", 9).replace("\n", "\r\n")

        assertEquals(BreachCheckResult.Breached(9), service(sourceReturning(body)).check("hunter2"))
    }

    // -- Padding -------------------------------------------------------------------------------

    @Test
    fun `a padding entry is not a breach`() = runTest {
        // Add-Padding mixes in invented suffixes so the response size reveals nothing about
        // how many real matches there were. They carry a count of zero; treating one as a
        // hit would report a password breached zero times.
        val result = service(sourceReturning(rangeContaining("hunter2", count = 0))).check("hunter2")

        assertEquals(BreachCheckResult.Safe, result)
    }

    @Test
    fun `padding alongside a real hit does not hide it`() = runTest {
        val body = rangeContaining("hunter2", count = 4, extra = "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA:0\n")

        assertEquals(BreachCheckResult.Breached(4), service(sourceReturning(body)).check("hunter2"))
    }

    // -- Malformed input ---------------------------------------------------------------------------

    @Test
    fun `garbled lines are skipped rather than crashing`() = runTest {
        val suffix = sha1("hunter2").substring(5)
        val body = """
            not-a-line
            missing-count:
            :99
            ABC:notanumber
            $suffix:7
        """.trimIndent()

        assertEquals(BreachCheckResult.Breached(7), service(sourceReturning(body)).check("hunter2"))
    }

    @Test
    fun `a wholly garbled response is safe rather than a crash`() = runTest {
        val result = service(sourceReturning("<html>503 Service Unavailable</html>")).check("hunter2")

        // The service answered with something unparseable. Nothing matched, which is the
        // honest reading — the transport-level failures are the ones flagged unavailable.
        assertEquals(BreachCheckResult.Safe, result)
    }

    // -- k-anonymity ---------------------------------------------------------------------------------

    @Test
    fun `only the first five hash characters are sent`() = runTest {
        var requested: String? = null
        val spy = object : PwnedRangeSource {
            override suspend fun fetch(prefix: String): String {
                requested = prefix
                return ""
            }
        }

        service(spy).check("hunter2")

        assertEquals(5, requested!!.length)
        assertEquals(sha1("hunter2").take(5), requested)
    }

    @Test
    fun `the password never appears in the request`() = runTest {
        var requested: String? = null
        val spy = object : PwnedRangeSource {
            override suspend fun fetch(prefix: String): String {
                requested = prefix
                return ""
            }
        }

        service(spy).check("hunter2")

        assertTrue(!requested!!.contains("hunter2", ignoreCase = true))
    }

    @Test
    fun `an empty password is not sent anywhere`() = runTest {
        var called = false
        val spy = object : PwnedRangeSource {
            override suspend fun fetch(prefix: String): String {
                called = true
                return ""
            }
        }

        assertEquals(BreachCheckResult.Safe, service(spy).check(""))
        assertTrue("nothing to check, so nothing should be requested", !called)
    }
}
