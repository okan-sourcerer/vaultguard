package com.vaultguard.app.autofill

import com.vaultguard.app.domain.model.Credential
import org.junit.Assert.assertEquals
import org.junit.Test

/** Finding #56, and the boundaries it shares with #55. */
class SaveDecisionTest {

    private fun credential(id: String, username: String, password: String) =
        Credential(id = id, siteName = "example.com", username = username, password = password)

    @Test
    fun `an unknown account is offered for saving`() {
        val outcome = SaveDecision.decide("okan", "hunter2", emptyList())

        assertEquals(SaveDecision.Outcome.CreateNew, outcome)
    }

    @Test
    fun `a second account on a site already used is offered for saving`() {
        val outcome = SaveDecision.decide(
            "work@example.com",
            "hunter2",
            listOf(credential("1", "personal@example.com", "other"))
        )

        assertEquals(SaveDecision.Outcome.CreateNew, outcome)
    }

    @Test
    fun `signing in again with the stored password says nothing`() {
        val outcome = SaveDecision.decide(
            "okan",
            "hunter2",
            listOf(credential("1", "okan", "hunter2"))
        )

        assertEquals(SaveDecision.Outcome.Ignore, outcome)
    }

    @Test
    fun `a rotated password is offered as an update, not a new entry`() {
        // The case the old duplicate rule threw away: same account, new password.
        val outcome = SaveDecision.decide(
            "okan",
            "the-new-one",
            listOf(credential("1", "okan", "the-old-one"))
        )

        assertEquals(SaveDecision.Outcome.UpdateExisting("1"), outcome)
    }

    @Test
    fun `the update names the entry that actually matched`() {
        val outcome = SaveDecision.decide(
            "work@example.com",
            "rotated",
            listOf(
                credential("1", "personal@example.com", "personal-pw"),
                credential("2", "work@example.com", "work-pw")
            )
        )

        assertEquals(SaveDecision.Outcome.UpdateExisting("2"), outcome)
    }

    @Test
    fun `a password-only screen with one known account can be attributed`() {
        // A re-authentication prompt after the password was changed elsewhere.
        val outcome = SaveDecision.decide(
            "",
            "the-new-one",
            listOf(credential("1", "okan", "the-old-one"))
        )

        assertEquals(SaveDecision.Outcome.UpdateExisting("1"), outcome)
    }

    @Test
    fun `a password-only screen matching the stored password says nothing`() {
        val outcome = SaveDecision.decide(
            "",
            "hunter2",
            listOf(credential("1", "okan", "hunter2"))
        )

        assertEquals(SaveDecision.Outcome.Ignore, outcome)
    }

    @Test
    fun `a password-only screen with several accounts guesses at none of them`() {
        // Updating the wrong account is worse than doing nothing, and creating an entry
        // here is exactly the blank-username duplicate of #55.
        val outcome = SaveDecision.decide(
            "",
            "something-new",
            listOf(
                credential("1", "personal@example.com", "a"),
                credential("2", "work@example.com", "b")
            )
        )

        assertEquals(SaveDecision.Outcome.Ignore, outcome)
    }

    @Test
    fun `a password-only screen on a site with nothing saved still offers to save`() {
        val outcome = SaveDecision.decide("", "hunter2", emptyList())

        assertEquals(SaveDecision.Outcome.CreateNew, outcome)
    }

    @Test
    fun `a locked vault offers to save and lets the save screen ask for the master password`() {
        // findMatchingCredentials returns nothing while locked, which must not be read as
        // "nothing is stored" and silently drop a password the user just typed (#35).
        val outcome = SaveDecision.decide("okan", "hunter2", emptyList())

        assertEquals(SaveDecision.Outcome.CreateNew, outcome)
    }

    // -- Linking to an entry saved for another surface ----------------------------------

    private fun entry(id: String, username: String, password: String, url: String = "https://site.com") =
        Credential(id = id, siteName = id, url = url, username = username, password = password)

    @Test
    fun `an identical account saved elsewhere is offered for linking, not duplicated`() {
        val web = entry("web", "okan", "pw", url = "https://site.com")

        val outcome = SaveDecision.decide("okan", "pw", known = emptyList(), everything = listOf(web))

        assertEquals(SaveDecision.Outcome.LinkExisting("web"), outcome)
    }

    @Test
    fun `a different password elsewhere is not the same account`() {
        val web = entry("web", "okan", "other")

        assertEquals(SaveDecision.Outcome.CreateNew, SaveDecision.decide("okan", "pw", emptyList(), listOf(web)))
    }

    @Test
    fun `two candidate twins make the link a guess, so a new entry is offered`() {
        val a = entry("a", "okan", "pw")
        val b = entry("b", "okan", "pw", url = "https://other.com")

        assertEquals(SaveDecision.Outcome.CreateNew, SaveDecision.decide("okan", "pw", emptyList(), listOf(a, b)))
    }

    @Test
    fun `a site match still takes precedence over linking`() {
        val here = entry("here", "okan", "old")
        val elsewhere = entry("elsewhere", "okan", "pw", url = "https://other.com")

        // Known for this site with a different password: an update, not a link.
        assertEquals(
            SaveDecision.Outcome.UpdateExisting("here"),
            SaveDecision.decide("okan", "pw", listOf(here), listOf(here, elsewhere))
        )
    }

    @Test
    fun `without a username nothing is linked`() {
        val web = entry("web", "", "pw")
        assertEquals(SaveDecision.Outcome.CreateNew, SaveDecision.decide("", "pw", emptyList(), listOf(web)))
    }
}
