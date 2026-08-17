package com.vaultguard.app.ui.screens.unlock

import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.vaultguard.app.security.BiometricAuthManager
import com.vaultguard.app.security.MasterPasswordManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

data class UnlockUiState(
    val password: String = "",
    val error: String? = null,
    val isLoading: Boolean = false,
    val isUnlocked: Boolean = false,
    val failedAttempts: Int = 0,
    val isLockedOut: Boolean = false,
    val lockoutSeconds: Int = 0,
    val biometricAvailable: Boolean = false
)

@HiltViewModel
class UnlockViewModel @Inject constructor(
    private val masterPasswordManager: MasterPasswordManager,
    private val biometricAuthManager: BiometricAuthManager
) : ViewModel() {

    private val _uiState = MutableStateFlow(UnlockUiState())
    val uiState: StateFlow<UnlockUiState> = _uiState

    val pendingMessage: StateFlow<String?> = masterPasswordManager.pendingLockMessage

    fun consumePendingMessage() = masterPasswordManager.consumeLockMessage()

    init {
        _uiState.value = _uiState.value.copy(
            biometricAvailable = biometricAuthManager.isBiometricEnabled
        )
    }

    fun onPasswordChange(password: String) {
        _uiState.value = _uiState.value.copy(password = password, error = null)
    }

    fun onUnlock() {
        val state = _uiState.value
        if (state.isLockedOut || state.isLoading) return

        viewModelScope.launch {
            _uiState.value = state.copy(isLoading = true, error = null)
            val success = masterPasswordManager.unlock(state.password.toCharArray())
            if (success) {
                _uiState.value = _uiState.value.copy(isLoading = false, isUnlocked = true)
            } else {
                val attempts = state.failedAttempts + 1
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    error = "Incorrect master password",
                    failedAttempts = attempts,
                    password = ""
                )
                if (attempts >= 3) {
                    startLockout(attempts)
                }
            }
        }
    }

    fun onBiometricUnlock(activity: FragmentActivity) {
        biometricAuthManager.authenticateAndUnwrapKey(activity) { vaultKey ->
            when {
                // Cancellation and hardware failure both arrive as null. Saying nothing
                // left the user tapping a fingerprint icon that appeared to do nothing
                // (finding #32).
                vaultKey == null -> _uiState.value = _uiState.value.copy(
                    error = "Biometric unlock failed or was cancelled. " +
                        "Enter your master password instead."
                )

                // The unwrapped key does not open the vault. The realistic cause is a
                // master password change, which leaves the wrapped copy stale
                // (finding #6). Accepting it would unlock into an empty-looking vault,
                // which is what finding #7 allowed.
                !masterPasswordManager.unlockWithKey(vaultKey) -> {
                    biometricAuthManager.disableBiometric()
                    _uiState.value = _uiState.value.copy(
                        biometricAvailable = false,
                        error = "Biometric unlock is out of date — most likely your master " +
                            "password changed. It has been turned off. Unlock with your " +
                            "master password, then re-enable it in Settings."
                    )
                }

                else -> _uiState.value = _uiState.value.copy(isUnlocked = true, error = null)
            }
        }
    }

    private fun startLockout(attempts: Int) {
        val delaySeconds = when {
            attempts < 3 -> 0
            else -> (1 shl (attempts - 3).coerceAtMost(4)) * 2
        }
        if (delaySeconds == 0) return

        _uiState.value = _uiState.value.copy(isLockedOut = true, lockoutSeconds = delaySeconds)

        viewModelScope.launch {
            for (i in delaySeconds downTo 1) {
                _uiState.value = _uiState.value.copy(lockoutSeconds = i)
                delay(1000)
            }
            _uiState.value = _uiState.value.copy(isLockedOut = false, lockoutSeconds = 0)
        }
    }
}
