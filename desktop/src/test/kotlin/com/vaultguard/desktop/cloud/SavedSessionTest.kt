package com.vaultguard.desktop.cloud

import com.vaultguard.app.security.KeyDerivation
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import javax.crypto.SecretKey

class SavedSessionTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private val keyDerivation = KeyDerivation()
    private val salt = ByteArray(16) { it.toByte() }

    private fun keyFor(password: String): SecretKey =
        keyDerivation.deriveKey(password.toCharArray(), salt)

    private fun file(): File = File(temporaryFolder.root, "desktop-session.json")

    private val session = SavedSession(refreshToken = "a-refresh-token", email = "okan@example.com")

    @Test
    fun `a saved session reopens with the same master key`() {
        val target = file()
        SavedSession.save(session, keyFor("master"), salt, target)

        val reopened = SavedSession.open(keyFor("master"), target)!!

        assertEquals("a-refresh-token", reopened.refreshToken)
        assertEquals("okan@example.com", reopened.email)
    }

    @Test
    fun `the wrong master password opens nothing`() {
        val target = file()
        SavedSession.save(session, keyFor("master"), salt, target)

        // Null rather than an exception: a file left from a previous master password looks
        // exactly the same as a typo, and signing in again fixes both.
        assertNull(SavedSession.open(keyFor("not the master"), target))
    }

    @Test
    fun `the refresh token never touches the disk in the clear`() {
        val target = file()
        SavedSession.save(session, keyFor("master"), salt, target)

        val raw = target.readText()
        assertFalse("the refresh token was written in the clear", raw.contains("a-refresh-token"))
        assertFalse("the email was written in the clear", raw.contains("okan@example.com"))
    }

    @Test
    fun `the salt is readable without the master password`() {
        val target = file()
        SavedSession.save(session, keyFor("master"), salt, target)

        // Deliberately in the clear: the next launch has to derive the master key *before*
        // it can reach Firestore, because the token that reaches Firestore is sealed under
        // that key. The salt is not secret — the same value is in the vault document.
        assertArrayEquals(salt, SavedSession.saltOf(target))
    }

    @Test
    fun `no file means no salt and no session`() {
        val target = file()
        assertNull(SavedSession.saltOf(target))
        assertNull(SavedSession.open(keyFor("master"), target))
    }

    @Test
    fun `a damaged file is treated as absent, not as an error`() {
        val target = file()
        target.writeText("this is not json")

        assertNull(SavedSession.saltOf(target))
        assertNull(SavedSession.open(keyFor("master"), target))
    }

    @Test
    fun `a file from a future version is ignored`() {
        val target = file()
        SavedSession.save(session, keyFor("master"), salt, target)
        val bumped = JSONObject(target.readText()).put("version", 99)
        target.writeText(bumped.toString())

        assertNull(SavedSession.saltOf(target))
        assertNull(SavedSession.open(keyFor("master"), target))
    }

    @Test
    fun `clearing removes the file`() {
        val target = file()
        SavedSession.save(session, keyFor("master"), salt, target)

        assertTrue(SavedSession.clear(target))
        assertFalse(target.exists())
        assertFalse("clearing twice is not a failure to report", SavedSession.clear(target))
    }

    @Test
    fun `saving twice leaves no partial file behind`() {
        val target = file()
        SavedSession.save(session, keyFor("master"), salt, target)
        SavedSession.save(session.copy(refreshToken = "a-newer-token"), keyFor("master"), salt, target)

        val strays = target.parentFile.listFiles { candidate: File -> candidate.name.contains(".part") }
        assertEquals(0, strays?.size)
        assertEquals("a-newer-token", SavedSession.open(keyFor("master"), target)!!.refreshToken)
    }

    @Test
    fun `a session without an email round-trips`() {
        val target = file()
        SavedSession.save(SavedSession("token-only", null), keyFor("master"), salt, target)

        val reopened = SavedSession.open(keyFor("master"), target)!!
        assertEquals("token-only", reopened.refreshToken)
        assertNull(reopened.email)
    }
}
