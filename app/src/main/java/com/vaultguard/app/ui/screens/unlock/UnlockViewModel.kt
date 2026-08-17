package com.vaultguard.app.ui.screens.unlock

import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.vaultguard.app.security.BiometricAuthManager
import com.vaultguard.app.domain.usecase.UnlockVaultUseCase
import com.vaultguard.app.security.MasterPasswordManager
import com.vaultguard.app.security.UnlockThrottle
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
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
    private val biometricAuthManager: BiometricAuthManager,
    private val unlockVaultUseCase: UnlockVaultUseCase,
    private val throttle: UnlockThrottle
) : ViewModel() {

    private var countdownJob: Job? = null

    private val _uiState = MutableStateFlow(UnlockUiState())
    val uiState: StateFlow<UnlockUiState> = _uiState

    val pendingMessage: StateFlow<String?> = masterPasswordManager.pendingLockMessage

    fun consumePendingMessage() = masterPasswordManager.consumeLockMessage()

    init {
        _uiState.value = _uiState.value.copy(
            biometricAvailable = biometricAuthManager.isBiometricEnabled,
            failedAttempts = throttle.failedAttempts
        )
        // A lockout outlives the process now, so the screen has to pick up one already in
        // progress rather than starting from zero (finding #12).
        (throttle.state() as? UnlockThrottle.State.LockedOut)?.let {
            startCountdown(it.remainingSeconds)
        }
    }

    fun onPasswordChange(password: String) {
        _uiState.value = _uiState.value.copy(password = password, error = null)
    }

    fun onUnlock() {
        val state = _uiState.value
        if (state.isLockedOut || state.isLoading) return

        viewModelScope.launch {
            _uiState.value = state.copy(isLoading = true, error = null)

            when (val result = unlockVaultUseCase(state.password.toCharArray())) {
                UnlockVaultUseCase.Result.Success ->
                    _uiState.value = _uiState.value.copy(isLoading = false, isUnlocked = true)

                // The password was right; the vault itself is the problem. Not a failed
                // attempt, so it must not count toward lockout.
                is UnlockVaultUseCase.Result.VaultUnreadable ->
                    _uiState.value = _uiState.value.copy(
                        isLoading = false,
                        error = result.detail,
                        password = ""
                    )

                UnlockVaultUseCase.Result.WrongPassword ->
                    _uiState.value = _uiState.value.copy(
                        isLoading = false,
                        error = "Incorrect master password",
                        failedAttempts = throttle.failedAttempts,
                        password = ""
                    )

                is UnlockVaultUseCase.Result.Throttled -> {
                    _uiState.value = _uiState.value.copy(
                        isLoading = false,
                        error = "Too many incorrect attempts",
                        failedAttempts = result.failedAttempts,
                        password = ""
                    )
                    startCountdown(result.remainingSeconds)
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

                // The unwrapped key is not the current vault key. Since the vault key
                // survives a master-password change, the remaining causes are an enrolment
                // predating the vault-key layout, or a vault replaced from another device.
                // Accepting it regardless would unlock into an empty-looking vault, which
                // is what finding #7 allowed.
                !masterPasswordManager.unlockWithKey(vaultKey) -> {
                    biometricAuthManager.disableBiometric()
                    _uiState.value = _uiState.value.copy(
                        biometricAvailable = false,
                        error = "Biometric unlock is out of date and has been turned off. " +
                            "Unlock with your master password, then re-enable it in Settings."
                    )
                }

                else -> _uiState.value = _uiState.value.copy(isUnlocked = true, error = null)
            }
        }
    }

    /** Counts the lockout down for display. The throttle itself is the authority. */
    private fun startCountdown(seconds: Int) {
        countdownJob?.cancel()
        countdownJob = viewModelScope.launch {
            for (remaining in seconds downTo 1) {
                _uiState.value = _uiState.value.copy(isLockedOut = true, lockoutSeconds = remaining)
                delay(1000)
            }
            _uiState.value = _uiState.value.copy(isLockedOut = false, lockoutSeconds = 0)
        }
    }
}
