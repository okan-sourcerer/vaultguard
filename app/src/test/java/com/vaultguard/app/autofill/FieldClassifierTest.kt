package com.vaultguard.app.autofill

import android.text.InputType
import android.view.View
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Tests for form-field classification (finding #9).
 *
 * Getting this wrong types the user's password into a visible field, so the table below is
 * deliberately exhaustive over the text variations Android defines — the old bitwise test
 * misclassified three of them.
 */
class FieldClassifierTest {

    private fun classify(
        hints: List<String> = emptyList(),
        html: List<Pair<String, String>> = emptyList(),
        inputType: Int = 0,
        hintText: String? = null,
        idEntry: String? = null
    ) = FieldClassifier.classify(hints, html, inputType, hintText, idEntry)

    private fun textField(variation: Int) = InputType.TYPE_CLASS_TEXT or variation

    // -- Input type variations — the #9 regression -------------------------------------------

    @Test
    fun `password variations are passwords`() {
        listOf(
            "password" to InputType.TYPE_TEXT_VARIATION_PASSWORD,
            "visible password" to InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD,
            "web password" to InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD
        ).forEach { (name, variation) ->
            assertEquals(name, FieldType.PASSWORD, classify(inputType = textField(variation)))
        }
    }

    @Test
    fun `email variations are usernames, not passwords`() {
        // The headline bug: an email field is 0x21, and 0x21 and WEB_PASSWORD (0xe0) is
        // 0x20 — non-zero — so the old `and … != 0` test called it a password and filled
        // it with one.
        listOf(
            "email" to InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS,
            "web email" to InputType.TYPE_TEXT_VARIATION_WEB_EMAIL_ADDRESS
        ).forEach { (name, variation) ->
            assertEquals(name, FieldType.USERNAME, classify(inputType = textField(variation)))
        }
    }

    @Test
    fun `unrelated text variations are neither`() {
        // URI (0x11) and postal address (0x51) both collided with VISIBLE_PASSWORD (0x90)
        // under the old bitwise test.
        listOf(
            "uri" to InputType.TYPE_TEXT_VARIATION_URI,
            "postal address" to InputType.TYPE_TEXT_VARIATION_POSTAL_ADDRESS,
            "person name" to InputType.TYPE_TEXT_VARIATION_PERSON_NAME,
            "phonetic" to InputType.TYPE_TEXT_VARIATION_PHONETIC,
            "normal" to InputType.TYPE_TEXT_VARIATION_NORMAL,
            "long message" to InputType.TYPE_TEXT_VARIATION_LONG_MESSAGE
        ).forEach { (name, variation) ->
            assertEquals(name, FieldType.NONE, classify(inputType = textField(variation)))
        }
    }

    @Test
    fun `non-text input classes are ignored`() {
        assertEquals(FieldType.NONE, classify(inputType = InputType.TYPE_CLASS_NUMBER))
        assertEquals(FieldType.NONE, classify(inputType = InputType.TYPE_CLASS_DATETIME))
        assertEquals(FieldType.NONE, classify(inputType = InputType.TYPE_CLASS_PHONE))
    }

    // -- Autofill hints ---------------------------------------------------------------------

    @Test
    fun `platform hints are honoured`() {
        assertEquals(FieldType.PASSWORD, classify(hints = listOf(View.AUTOFILL_HINT_PASSWORD)))
        assertEquals(FieldType.USERNAME, classify(hints = listOf(View.AUTOFILL_HINT_USERNAME)))
    }

    @Test
    fun `the camelCase email hint is recognised`() {
        // AUTOFILL_HINT_EMAIL_ADDRESS is "emailAddress". The old code lowercased the
        // incoming hint and compared it to the constant, so this branch never fired.
        assertEquals(FieldType.USERNAME, classify(hints = listOf("emailAddress")))
        assertEquals(FieldType.USERNAME, classify(hints = listOf("emailaddress")))
        assertEquals(FieldType.USERNAME, classify(hints = listOf("EMAILADDRESS")))
    }

    @Test
    fun `web password hint spellings are recognised`() {
        assertEquals(FieldType.PASSWORD, classify(hints = listOf("current-password")))
        assertEquals(FieldType.PASSWORD, classify(hints = listOf("new-password")))
        assertEquals(FieldType.PASSWORD, classify(hints = listOf("newPassword")))
    }

    @Test
    fun `hints win over a conflicting input type`() {
        assertEquals(
            FieldType.PASSWORD,
            classify(
                hints = listOf(View.AUTOFILL_HINT_PASSWORD),
                inputType = textField(InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS)
            )
        )
    }

    // -- HTML attributes ----------------------------------------------------------------------

    @Test
    fun `html input types are honoured`() {
        assertEquals(FieldType.PASSWORD, classify(html = listOf("type" to "password")))
        assertEquals(FieldType.USERNAME, classify(html = listOf("type" to "email")))
    }

    @Test
    fun `html autocomplete is honoured`() {
        assertEquals(FieldType.PASSWORD, classify(html = listOf("autocomplete" to "current-password")))
        assertEquals(FieldType.USERNAME, classify(html = listOf("autocomplete" to "username")))
    }

    @Test
    fun `a text input named like a username is a username`() {
        assertEquals(FieldType.USERNAME, classify(html = listOf("type" to "text", "name" to "user_email")))
        assertEquals(FieldType.NONE, classify(html = listOf("type" to "text", "name" to "search")))
    }

    // -- Word fallback ---------------------------------------------------------------------------

    @Test
    fun `password words are matched on whole words`() {
        assertEquals(FieldType.PASSWORD, classify(hintText = "Password"))
        assertEquals(FieldType.PASSWORD, classify(idEntry = "login_passwd"))
        assertEquals(FieldType.PASSWORD, classify(idEntry = "userPassword"))
    }

    @Test
    fun `words merely containing pass are not passwords`() {
        // The old `contains("pass")` matched all of these.
        listOf("Passport number", "Passenger name", "Bypass code", "Compass heading")
            .forEach { assertEquals(it, FieldType.NONE, classify(hintText = it)) }
    }

    @Test
    fun `username words are matched on whole words`() {
        assertEquals(FieldType.USERNAME, classify(hintText = "Username"))
        assertEquals(FieldType.USERNAME, classify(hintText = "E-mail address"))
        assertEquals(FieldType.USERNAME, classify(idEntry = "accountName"))
    }

    @Test
    fun `unrelated labels are neither`() {
        listOf("Search", "First name", "Postcode", "Card number", "Street address")
            .forEach { assertEquals(it, FieldType.NONE, classify(hintText = it)) }
    }

    @Test
    fun `nothing to go on yields none`() {
        assertEquals(FieldType.NONE, classify())
        assertEquals(FieldType.NONE, classify(hintText = "", idEntry = ""))
    }
}
