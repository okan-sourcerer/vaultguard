package com.vaultguard.app.util

import javax.inject.Inject
import kotlin.math.ln
import kotlin.math.roundToInt

enum class StrengthLevel { WEAK, FAIR, STRONG, VERY_STRONG }

data class PasswordStrength(
    val score: Int,
    val level: StrengthLevel,
    val entropy: Double,
    /** Set when the password is a known-common one, whatever its entropy says. */
    val isCommon: Boolean = false
) {
    val isWeak: Boolean get() = level == StrengthLevel.WEAK
}

class PasswordStrengthEvaluator @Inject constructor() {

    private companion object {
        /** Three distinct characters is not a password, whatever its length. */
        const val MINIMUM_DISTINCT_CHARACTERS = 3
    }

    operator fun invoke(password: String): PasswordStrength {
        if (password.isEmpty()) return PasswordStrength(0, StrengthLevel.WEAK, 0.0)

        // Checked before the arithmetic, because entropy cannot see this. `Password1!`
        // draws on four character classes across ten characters and computes to 65 bits —
        // comfortably STRONG — while sitting near the top of every cracking dictionary
        // (finding #27). A password that is a known one wearing decoration is weak no
        // matter what the maths says.
        if (CommonPasswords.isCommon(password)) {
            return PasswordStrength(0, StrengthLevel.WEAK, 0.0, isCommon = true)
        }

        // A password built from a handful of characters is guessable however long it is:
        // "aaaaaaaaaaaaaaaa" is 16 characters and computes to 75 bits, which the level
        // thresholds would call STRONG. Length only buys entropy when the characters vary.
        val distinctCharacters = password.toSet().size
        if (distinctCharacters <= MINIMUM_DISTINCT_CHARACTERS) {
            return PasswordStrength(0, StrengthLevel.WEAK, 0.0)
        }

        val poolSize = calculatePoolSize(password)
        val entropy = password.length * (ln(poolSize.toDouble()) / ln(2.0))

        val penalties = calculatePenalties(password)
        val adjustedEntropy = (entropy * (1.0 - penalties)).coerceAtLeast(0.0)

        val score = entropyToScore(adjustedEntropy)
        val level = when {
            adjustedEntropy < 40 -> StrengthLevel.WEAK
            adjustedEntropy < 60 -> StrengthLevel.FAIR
            adjustedEntropy < 80 -> StrengthLevel.STRONG
            else -> StrengthLevel.VERY_STRONG
        }

        return PasswordStrength(score, level, adjustedEntropy)
    }

    private fun calculatePoolSize(password: String): Int {
        var size = 0
        if (password.any { it.isLowerCase() }) size += 26
        if (password.any { it.isUpperCase() }) size += 26
        if (password.any { it.isDigit() }) size += 10
        if (password.any { !it.isLetterOrDigit() }) size += 33
        return size.coerceAtLeast(1)
    }

    private fun calculatePenalties(password: String): Double {
        var penalty = 0.0

        // Repeated characters
        val charCounts = password.groupBy { it.lowercaseChar() }
        val maxRepeat = charCounts.values.maxOfOrNull { it.size } ?: 0
        if (maxRepeat > password.length / 3) penalty += 0.15

        // Low variety over a long password: the extra length is mostly repetition, and
        // contributes far less than the naive length × log2(pool) suggests.
        if (password.length >= 8 && charCounts.size * 3 < password.length) penalty += 0.2

        // Sequential characters (abc, 123)
        var sequential = 0
        for (i in 0 until password.length - 2) {
            val a = password[i].code
            val b = password[i + 1].code
            val c = password[i + 2].code
            if (b - a == 1 && c - b == 1) sequential++
            if (a - b == 1 && b - c == 1) sequential++
        }
        if (sequential > 0) penalty += (sequential * 0.05).coerceAtMost(0.2)

        // Keyboard walks (qwerty, asdf)
        val keyboardRows = listOf("qwertyuiop", "asdfghjkl", "zxcvbnm")
        val lowerPassword = password.lowercase()
        for (row in keyboardRows) {
            for (i in 0..row.length - 4) {
                if (lowerPassword.contains(row.substring(i, i + 4))) {
                    penalty += 0.1
                }
            }
        }

        return penalty.coerceAtMost(0.5)
    }

    private fun entropyToScore(entropy: Double): Int {
        return ((entropy / 120.0) * 100).roundToInt().coerceIn(0, 100)
    }
}
