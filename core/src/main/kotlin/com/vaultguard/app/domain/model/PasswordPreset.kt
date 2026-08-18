package com.vaultguard.app.domain.model

data class PasswordPreset(
    val id: String,
    val name: String,
    val config: PasswordGeneratorConfig,
    val isDefault: Boolean = false
) {
    companion object {
        const val DEFAULT_ID = "default"

        val DEFAULT = PasswordPreset(
            id = DEFAULT_ID,
            name = "Default",
            config = PasswordGeneratorConfig(),
            isDefault = true
        )
    }
}
