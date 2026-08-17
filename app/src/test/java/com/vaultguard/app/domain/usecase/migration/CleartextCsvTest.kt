package com.vaultguard.app.domain.usecase.migration

import com.vaultguard.app.domain.model.Credential
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for the temporary cleartext migration CSV.
 *
 * These matter more than the "temporary" label suggests: this file is the only trustworthy
 * way out of the vault until backup v2 lands, so a silent escaping bug here would corrupt
 * the one copy of the passwords that still works. Generated passwords routinely contain
 * commas and quotes.
 *
 * Delete alongside the `migration` package.
 */
class CleartextCsvTest {

    private fun credential(
        siteName: String = "GitHub",
        appName: String = "",
        url: String = "https://github.com",
        username: String = "okan",
        password: String = "hunter2",
        notes: String = "",
        category: String = "Development",
        tags: List<String> = emptyList(),
        isPinned: Boolean = false,
        linkedPackages: List<String> = emptyList(),
        linkedDomains: List<String> = emptyList()
    ) = Credential(
        id = "id", siteName = siteName, appName = appName, url = url, username = username,
        password = password, notes = notes, category = category, tags = tags,
        isPinned = isPinned, linkedPackages = linkedPackages, linkedDomains = linkedDomains
    )

    @Test
    fun `writes the bitwarden header`() {
        val csv = CleartextCsv.write(emptyList())

        assertEquals(
            "folder,favorite,type,name,notes,fields,reprompt," +
                "login_uri,login_username,login_password,login_totp",
            csv.lineSequence().first()
        )
    }

    @Test
    fun `writes one record per credential`() {
        val csv = CleartextCsv.write(List(41) { credential(siteName = "site$it") })

        assertEquals(41, CleartextCsv.read(csv).size)
    }

    @Test
    fun `maps fields onto the bitwarden columns`() {
        val rows = CleartextCsv.read(
            CleartextCsv.write(
                listOf(
                    credential(
                        appName = "GitHub", url = "https://github.com", username = "okan",
                        password = "hunter2", notes = "note", category = "Development",
                        isPinned = true
                    )
                )
            )
        )

        val row = rows.single()
        assertEquals("GitHub", row.name)
        assertEquals("okan", row.username)
        assertEquals("hunter2", row.password)
        assertEquals("https://github.com", row.uri)
        assertEquals("note", row.notes)
        assertEquals("Development", row.folder)
        assertTrue(row.favorite)
    }

    @Test
    fun `name falls back to siteName when appName is blank`() {
        val row = CleartextCsv.read(
            CleartextCsv.write(listOf(credential(appName = "", siteName = "github.com")))
        ).single()

        assertEquals("github.com", row.name)
    }

    // -- Escaping: the part that silently corrupts data if wrong ---------------------

    @Test
    fun `round-trips a password containing commas`() {
        val password = "a,b,c,,d"

        val row = CleartextCsv.read(CleartextCsv.write(listOf(credential(password = password)))).single()

        assertEquals(password, row.password)
    }

    @Test
    fun `round-trips a password containing double quotes`() {
        val password = """say "hello" now"""

        val row = CleartextCsv.read(CleartextCsv.write(listOf(credential(password = password)))).single()

        assertEquals(password, row.password)
    }

    @Test
    fun `round-trips a password that is only quotes and commas`() {
        val password = """","",","""

        val row = CleartextCsv.read(CleartextCsv.write(listOf(credential(password = password)))).single()

        assertEquals(password, row.password)
    }

    @Test
    fun `round-trips notes containing newlines`() {
        val notes = "line one\nline two\nline three"

        val row = CleartextCsv.read(CleartextCsv.write(listOf(credential(notes = notes)))).single()

        assertEquals(notes, row.notes)
    }

    @Test
    fun `round-trips notes containing a quoted comma and newline together`() {
        val notes = "first, \"quoted\"\nsecond, line"

        val row = CleartextCsv.read(CleartextCsv.write(listOf(credential(notes = notes)))).single()

        assertEquals(notes, row.notes)
    }

    @Test
    fun `round-trips unicode`() {
        val row = CleartextCsv.read(
            CleartextCsv.write(listOf(credential(password = "şifre-🔐-密码", notes = "çğıöşü")))
        ).single()

        assertEquals("şifre-🔐-密码", row.password)
        assertEquals("çğıöşü", row.notes)
    }

    @Test
    fun `a record containing a newline does not split into two records`() {
        val csv = CleartextCsv.write(
            listOf(credential(notes = "a\nb\nc"), credential(siteName = "Second"))
        )

        assertEquals(2, CleartextCsv.read(csv).size)
    }

    // -- Custom fields ---------------------------------------------------------------

    @Test
    fun `round-trips tags and linked identifiers through the fields column`() {
        val row = CleartextCsv.read(
            CleartextCsv.write(
                listOf(
                    credential(
                        tags = listOf("work", "2fa"),
                        linkedPackages = listOf("com.github.android"),
                        linkedDomains = listOf("github.com", "gist.github.com")
                    )
                )
            )
        ).single()

        assertEquals(listOf("work", "2fa"), row.tags)
        assertEquals(listOf("com.github.android"), row.linkedPackages)
        assertEquals(listOf("github.com", "gist.github.com"), row.linkedDomains)
    }

    @Test
    fun `omits the fields column when there is nothing to put in it`() {
        val row = CleartextCsv.read(CleartextCsv.write(listOf(credential()))).single()

        assertEquals(emptyList<String>(), row.tags)
        assertEquals(emptyList<String>(), row.linkedPackages)
    }

    // -- Reading files this app did not write ----------------------------------------

    @Test
    fun `reads a hand-written file with LF line endings`() {
        val csv = "name,login_username,login_password\nGitHub,okan,hunter2\n"

        val row = CleartextCsv.read(csv).single()

        assertEquals("GitHub", row.name)
        assertEquals("hunter2", row.password)
    }

    @Test
    fun `tolerates a missing column`() {
        val csv = "name,login_password\r\nGitHub,hunter2\r\n"

        val row = CleartextCsv.read(csv).single()

        assertEquals("GitHub", row.name)
        assertEquals("", row.username)
    }

    @Test
    fun `ignores blank trailing lines`() {
        val csv = "name,login_password\r\nGitHub,hunter2\r\n\r\n\r\n"

        assertEquals(1, CleartextCsv.read(csv).size)
    }

    @Test
    fun `reads an empty document as no rows`() {
        assertEquals(emptyList<CleartextCsv.Row>(), CleartextCsv.read(""))
    }

    @Test
    fun `header-only document yields no rows`() {
        assertEquals(0, CleartextCsv.read(CleartextCsv.write(emptyList())).size)
    }
}
