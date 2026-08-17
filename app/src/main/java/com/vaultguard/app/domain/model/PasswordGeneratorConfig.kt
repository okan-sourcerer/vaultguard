package com.vaultguard.app.domain.model

data class PasswordGeneratorConfig(
    val length: Int = 20,
    val includeUppercase: Boolean = true,
    val includeLowercase: Boolean = true,
    val includeDigits: Boolean = true,
    val includeSymbols: Boolean = true,
    val excludeAmbiguous: Boolean = false,
    val customSymbols: String = "!@#\$%^&*()-_=+[]{}|;:,.<>?"
)
