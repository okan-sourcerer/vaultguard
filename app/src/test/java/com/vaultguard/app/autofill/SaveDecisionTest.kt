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
}
