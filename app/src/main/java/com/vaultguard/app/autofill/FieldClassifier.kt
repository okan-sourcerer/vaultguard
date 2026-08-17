package com.vaultguard.app.autofill

import android.text.InputType
import android.view.View

enum class FieldType { USERNAME, PASSWORD, NONE }

/**
 * Decides what a form field is, from values already extracted out of a `ViewNode`.
 *
 * Separated from [StructureParser] so it can be exercised on the host JVM — a `ViewNode`
 * cannot be constructed in a unit test, and this is the part where being wrong types the
 * user's password into the wrong box.
 */
object FieldClassifier {

    /**
     * Keywords used only as a last resort, matched on **word boundaries**.
     *
     * The previous implementation used `contains("pass")`, which classified "passport
     * number" and "passenger name" as password fields.
     */
    private val PASSWORD_WORDS = setOf(
        "password", "passwd", "passphrase", "pwd", "pin", "passcode"
    )
    // Entries must be single tokens: [tokenize] splits on punctuation, so a hyphenated
    // entry like "e-mail" could never match. "mail" covers that spelling instead —
    // "Mailing address" tokenizes to "mailing", so it stays unmatched.
    private val USERNAME_WORDS = setOf(
        "username", "user", "userid", "login", "email", "mail", "account", "identifier"
    )

    fun classify(
        autofillHints: List<String>,
        htmlAttributes: List<Pair<String, String>>,
        inputType: Int,
        hintText: String?,
        idEntry: String?
    ): FieldType {
        fromAutofillHints(autofillHints)?.let { return it }
        fromHtml(htmlAttributes)?.let { return it }
        fromInputType(inputType)?.let { return it }
        return fromWords(hintText, idEntry)
    }

    /**
     * Autofill hints are the platform's own answer and are trusted first.
     *
     * Compared case-insensitively: the constants are camelCase (`emailAddress`,
     * `newPassword`), and the old code lowercased the incoming hint before comparing it
     * against them, so every multi-word constant silently never matched.
     */
    private fun fromAutofillHints(hints: List<String>): FieldType? {
        for (hint in hints) {
            when {
                hint.equals(View.AUTOFILL_HINT_PASSWORD, ignoreCase = true) ||
                    hint.equals("current-password", ignoreCase = true) ||
                    hint.equals("new-password", ignoreCase = true) ||
                    hint.equals("newPassword", ignoreCase = true) -> return FieldType.PASSWORD

                hint.equals(View.AUTOFILL_HINT_USERNAME, ignoreCase = true) ||
                    hint.equals(View.AUTOFILL_HINT_EMAIL_ADDRESS, ignoreCase = true) ||
                    hint.equals("email", ignoreCase = true) ||
                    hint.equals("login", ignoreCase = true) -> return FieldType.USERNAME
            }
        }
        return null
    }

    private fun fromHtml(attributes: List<Pair<String, String>>): FieldType? {
        val byName = attributes.associate { (k, v) -> k.lowercase() to v }

        byName["autocomplete"]?.lowercase()?.let { value ->
            when {
                value.contains("password") -> return FieldType.PASSWORD
                value.contains("username") || value.contains("email") -> return FieldType.USERNAME
            }
        }

        when (byName["type"]?.lowercase()) {
            "password" -> return FieldType.PASSWORD
            "email" -> return FieldType.USERNAME
            "text" -> {
                val name = byName["name"] ?: byName["id"] ?: ""
                if (containsWord(name, USERNAME_WORDS)) return FieldType.USERNAME
            }
        }
        return null
    }

    /**
     * `TYPE_TEXT_VARIATION_*` are values within [InputType.TYPE_MASK_VARIATION], not
     * independent bits.
     *
     * The old code tested them with `and … != 0`, which is wrong in a way that reliably
     * misfires: a plain email field is `0x21`, and `0x21 and TYPE_TEXT_VARIATION_WEB_PASSWORD`
     * (`0xe0`) is `0x20` — non-zero, so it was classified as a password and filled with one.
     * URI (`0x11`) and postal-address (`0x51`) fields collided with
     * `TYPE_TEXT_VARIATION_VISIBLE_PASSWORD` (`0x90`) the same way.
     */
    private fun fromInputType(inputType: Int): FieldType? {
        if (inputType and InputType.TYPE_MASK_CLASS != InputType.TYPE_CLASS_TEXT) return null

        return when (inputType and InputType.TYPE_MASK_VARIATION) {
            InputType.TYPE_TEXT_VARIATION_PASSWORD,
            InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD,
            InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD -> FieldType.PASSWORD

            InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS,
            InputType.TYPE_TEXT_VARIATION_WEB_EMAIL_ADDRESS -> FieldType.USERNAME

            else -> null
        }
    }

    private fun fromWords(hintText: String?, idEntry: String?): FieldType {
        val words = tokenize(hintText).plus(tokenize(idEntry))
        if (words.isEmpty()) return FieldType.NONE

        if (words.any { it in PASSWORD_WORDS }) return FieldType.PASSWORD
        if (words.any { it in USERNAME_WORDS }) return FieldType.USERNAME
        return FieldType.NONE
    }

    private fun containsWord(text: String, words: Set<String>): Boolean =
        tokenize(text).any { it in words }

    /** Splits on anything that is not a letter or digit, plus camelCase boundaries. */
    private fun tokenize(text: String?): List<String> {
        if (text.isNullOrBlank()) return emptyList()
        return text
            .replace(Regex("([a-z0-9])([A-Z])"), "$1 $2")
            .split(Regex("[^A-Za-z0-9]+"))
            .filter { it.isNotEmpty() }
            .map { it.lowercase() }
    }
}
