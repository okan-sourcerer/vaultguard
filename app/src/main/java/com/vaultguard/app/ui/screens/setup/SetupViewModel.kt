package com.vaultguard.app.ui.screens.setup

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.vaultguard.app.data.remote.FirebaseSyncService
import com.vaultguard.app.security.MasterPasswordManager
import com.vaultguard.app.util.PasswordStrength
import com.vaultguard.app.util.PasswordStrengthEvaluator
import com.vaultguard.app.util.StrengthLevel
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

data class SetupUiState(
    val password: String = "",
    val confirmPassword: String = "",
    val strength: PasswordStrength = PasswordStrength(0, StrengthLevel.WEAK, 0.0),
    val error: String? = null,
    val isLoading: Boolean = false,
    val isComplete: Boolean = false
)

@HiltViewModel
class SetupViewModel @Inject constructor(
    private val masterPasswordManager: MasterPasswordManager,
    private val strengthEvaluator: PasswordStrengthEvaluator,
    private val syncService: FirebaseSyncService
) : ViewModel() {

    private val _uiState = MutableStateFlow(SetupUiState())
    val uiState: StateFlow<SetupUiState> = _uiState

    fun onPasswordChange(password: String) {
        _uiState.value = _uiState.value.copy(
            password = password,
            strength = strengthEvaluator(password),
            error = null
        )
    }

    fun onConfirmPasswordChange(confirmPassword: String) {
        _uiState.value = _uiState.value.copy(
            confirmPassword = confirmPassword,
            error = null
        )
    }

    fun onSetup() {
        val state = _uiState.value
        when {
            state.password.length < 8 -> {
                _uiState.value = state.copy(error = "Password must be at least 8 characters")
                return
            }
            state.password != state.confirmPassword -> {
                _uiState.value = state.copy(error = "Passwords do not match")
                return
            }
            state.strength.level == StrengthLevel.WEAK -> {
                _uiState.value = state.copy(error = "Password is too weak")
                return
            }
        }

        viewModelScope.launch {
            _uiState.value = state.copy(isLoading = true)
            try {
                masterPasswordManager.setup(state.password.toCharArray())
                // Push vault config to Firestore for cross-device recovery (non-fatal)
                try {
                    val (ciphertext, iv) = masterPasswordManager.getVerificationData()
                    syncService.pushVaultConfig(masterPasswordManager.getSalt(), ciphertext, iv)
                } catch (_: Exception) { }
                _uiState.value = _uiState.value.copy(isLoading = false, isComplete = true)
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(isLoading = false, error = "Setup failed: ${e.message}")
            }
        }
    }
}
