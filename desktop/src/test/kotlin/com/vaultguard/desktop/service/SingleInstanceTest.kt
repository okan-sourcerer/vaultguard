package com.vaultguard.desktop.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.concurrent.TimeUnit

class SingleInstanceTest {

    private val lockFile = File(Files.createTempDirectory("vaultguard-lock").toFile(), "service.lock")

    @Test
    fun `a second process cannot take a held lock, and can once it is released`() {
        assertTrue(SingleInstance.acquire(lockFile))

        // The claim is about another *process*: a FileLock is per JVM, so a same-JVM check
        // would exercise a different code path from the one a second launch hits.
        assertEquals("held", probe())

        SingleInstance.release()
        assertEquals("acquired", probe())
    }

    @Test
    fun `the same process asking twice is told no rather than crashing`() {
        assertTrue(SingleInstance.acquire(lockFile))
        assertFalse(SingleInstance.acquire(lockFile))
        SingleInstance.release()
    }

    private fun probe(): String {
        val java = File(System.getProperty("java.home"), "bin/java").absolutePath
        val process = ProcessBuilder(
            java, "-cp", System.getProperty("java.class.path"),
            SingleInstanceProbe::class.java.name, lockFile.absolutePath
        ).redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().readText()
        assertTrue("probe did not finish", process.waitFor(30, TimeUnit.SECONDS))
        return output.trim().lines().last()
    }
}
