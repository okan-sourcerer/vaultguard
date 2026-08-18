package com.vaultguard.app.domain.usecase

import com.vaultguard.app.domain.model.PasswordGeneratorConfig
import java.security.SecureRandom
import javax.inject.Inject

class GeneratePasswordUseCase @Inject constructor() {

    companion object {
        private const val UPPERCASE = "ABCDEFGHIJKLMNOPQRSTUVWXYZ"
        private const val LOWERCASE = "abcdefghijklmnopqrstuvwxyz"
        private const val DIGITS = "0123456789"
        private const val AMBIGUOUS_CHARS = "0O1lI"
    }

    private val secureRandom = SecureRandom()

    operator fun invoke(config: PasswordGeneratorConfig): String {
        val pools = mutableListOf<String>()

        if (config.includeUppercase) pools.add(filterAmbiguous(UPPERCASE, config.excludeAmbiguous))
        if (config.includeLowercase) pools.add(filterAmbiguous(LOWERCASE, config.excludeAmbiguous))
        if (config.includeDigits) pools.add(filterAmbiguous(DIGITS, config.excludeAmbiguous))
        if (config.includeSymbols) pools.add(config.customSymbols)

        if (pools.isEmpty()) return ""

        val allChars = pools.joinToString("")
        if (allChars.isEmpty()) return ""

        val password = CharArray(config.length)

        // Guarantee at least one character from each enabled pool
        val guaranteed = pools.mapIndexed { index, pool ->
            index to pool[secureRandom.nextInt(pool.length)]
        }
        for ((index, char) in guaranteed) {
            password[index] = char
        }

        // Fill remaining positions from the combined pool
        for (i in pools.size until config.length) {
            password[i] = allChars[secureRandom.nextInt(allChars.length)]
        }

        // Fisher-Yates shuffle
        for (i in password.size - 1 downTo 1) {
            val j = secureRandom.nextInt(i + 1)
            val temp = password[i]
            password[i] = password[j]
            password[j] = temp
        }

        return String(password)
    }

    private fun filterAmbiguous(pool: String, exclude: Boolean): String {
        return if (exclude) pool.filter { it !in AMBIGUOUS_CHARS } else pool
    }
}
