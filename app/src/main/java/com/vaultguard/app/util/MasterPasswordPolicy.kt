package com.vaultguard.app.util

import javax.inject.Inject

/**
 * The one place that decides whether a master password is acceptable.
 *
 * Setup and change-password each carried their own rules, and they disagreed: setup wanted
 * at least 8 characters *and* a non-WEAK rating, while change-password checked only the
 * length. So the vault could be moved to a password that setup would have rejected
 * (finding #28), and nothing stopped it being changed to the same one it already was.
 *
 * The bar is higher than for the passwords stored *inside* the vault, deliberately. This
 * one protects everything else, cannot be looked up anywhere, and has no recovery path.
 */
class MasterPasswordPolicy @Inject constructor(
    private val strengthEvaluator: PasswordStrengthEvaluator
) {
    companion object {
        const val MINIMUM_LENGTH = 12
    }

    sealed interface Result {
        data object Acceptable : Result
        data class Rejected(val reason: String) : Result
    }

    /**
     * @param confirmation the second field, when the caller has one.
     * @param currentPassword the password being replaced, so a no-op change is caught.
     */
    fun validate(
        password: String,
        confirmation: String? = null,
        currentPassword: String? = null
    ): Result {
        if (password.length < MINIMUM_LENGTH) {
            return Result.Rejected(
                "Use at least $MINIMUM_LENGTH characters. A passphrase of a few unrelated " +
                    "words is easier to remember and harder to guess than a short mixture " +
                    "of symbols."
            )
        }

        if (confirmation != null && password != confirmation) {
            return Result.Rejected("The two passwords do not match.")
        }

        if (currentPassword != null && password == currentPassword) {
            return Result.Rejected("That is already your master password.")
        }

        val strength = strengthEvaluator(password)
        if (strength.isCommon) {
            return Result.Rejected(
                "That is one of the most commonly guessed passwords, even with numbers or " +
                    "symbols added. Choose something unrelated to it."
            )
        }
        if (strength.isWeak) {
            return Result.Rejected(
                "That password is too easy to guess. Try a longer passphrase, or add words " +
                    "rather than characters."
            )
        }

        return Result.Acceptable
    }
}
