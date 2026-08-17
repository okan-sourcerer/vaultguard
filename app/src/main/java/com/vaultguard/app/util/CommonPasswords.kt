package com.vaultguard.app.util

/**
 * The passwords that get guessed first.
 *
 * A entropy calculation cannot see these: `Password1!` draws on all four character classes
 * over ten characters and scores as comfortably strong, while sitting near the top of every
 * cracking dictionary in existence (finding #27).
 *
 * This is a few hundred entries, not a real wordlist. It is meant to catch the passwords
 * someone reaches for when they are not thinking, not to be exhaustive — shipping a
 * million-line dictionary in the APK is not worth it for a personal vault. Matching happens
 * against a *normalised* form, so the usual decorations do not disguise the underlying word.
 */
object CommonPasswords {

    /**
     * Reduces the ways a common password gets dressed up: character case, leet
     * substitutions, and trailing digits or punctuation.
     *
     * `P@ssw0rd123!` and `Password1!` both reduce to `password`.
     */
    fun normalise(password: String): String {
        // Order matters. De-leeting first turns the trailing "123" of "password123" into
        // the letters "i2e", which the trim below then cannot remove — leaving
        // "passwordi2e" and matching nothing. Strip the decoration first, substitute after.
        val trimmed = password.lowercase().trim { !it.isLetter() }

        return trimmed
            .map { char ->
                when (char) {
                    '@' -> 'a'
                    '0' -> 'o'
                    '1', '!', '|' -> 'i'
                    '3' -> 'e'
                    '$', '5' -> 's'
                    '7' -> 't'
                    '4' -> 'a'
                    '+' -> 't'
                    else -> char
                }
            }
            .joinToString("")
    }

    /**
     * True when the password is essentially one of these with decoration on top.
     *
     * Requires the *whole* thing to reduce to a listed entry. A passphrase that merely
     * contains "password" somewhere is not penalised — only one that is that word wearing
     * a hat.
     */
    fun isCommon(password: String): Boolean {
        if (password.isEmpty()) return false
        if (password.lowercase() in ENTRIES) return true
        return normalise(password) in ENTRIES
    }

    private val ENTRIES: Set<String> = setOf(
        // Perennial top-20
        "password", "123456", "12345678", "123456789", "1234567890", "12345", "1234567",
        "qwerty", "qwertyuiop", "abc123", "111111", "123123", "1234", "iloveyou",
        "000000", "admin", "welcome", "monkey", "login", "letmein", "dragon", "passw0rd",
        "master", "hello", "freedom", "whatever", "qazwsx", "trustno", "starwars",
        // Keyboard walks
        "asdf", "asdfgh", "asdfghjkl", "zxcvbn", "zxcvbnm", "qwertz", "azerty",
        "1qaz2wsx", "qwe123", "1q2w3e4r", "1q2w3e", "q1w2e3r4",
        // Names and words that recur
        "sunshine", "princess", "football", "baseball", "superman", "batman", "michael",
        "shadow", "ashley", "bailey", "jordan", "hunter", "harley", "ranger", "buster",
        "soccer", "hockey", "killer", "george", "andrew", "charlie", "thomas", "jessica",
        "pepper", "daniel", "summer", "amanda", "joshua", "cheese", "maggie", "biteme",
        "banana", "chelsea", "diamond", "yellow", "orange", "purple", "silver", "internet",
        "computer", "samsung", "google", "facebook", "chocolate", "nicole", "jennifer",
        "hannah", "matthew", "access", "flower", "mustang", "shadow1", "secret", "ginger",
        "cookie", "shopping", "liverpool", "arsenal", "chelsea1", "barcelona", "juventus",
        // Vault- and account-flavoured
        "vault", "vaultguard", "mypassword", "newpassword", "changeme", "temporary",
        "temppassword", "default", "guest", "root", "toor", "test", "testing", "demo",
        "user", "username", "administrator", "manager", "system", "backup",
        // Turkish, since this vault's owner is a Turkish speaker
        "sifre", "parola", "sifre123", "galatasaray", "fenerbahce", "besiktas",
        "trabzonspor", "merhaba", "seninle", "askim", "canim", "bebegim", "istanbul",
        "ankara", "turkiye", "kartal", "aslan", "kanarya",
        // Patterns people believe are clever
        "letmein1", "iloveyou1", "password12", "adminadmin", "passpass", "abcabc",
        "aaaaaa", "bbbbbb", "zzzzzz", "qwerty123", "welcome1", "monkey1", "dragon1"
    )
}
