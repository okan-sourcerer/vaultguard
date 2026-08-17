package com.vaultguard.app.util

import javax.inject.Inject
import kotlin.math.ln
import kotlin.math.roundToInt

enum class StrengthLevel { WEAK, FAIR, STRONG, VERY_STRONG }

data class PasswordStrength(
    val score: Int,
    val level: StrengthLevel,
    val entropy: Double
)

class PasswordStrengthEvaluator @Inject constructor() {

    operator fun invoke(password: String): PasswordStrength {
        if (password.isEmpty()) return PasswordStrength(0, StrengthLevel.WEAK, 0.0)

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
