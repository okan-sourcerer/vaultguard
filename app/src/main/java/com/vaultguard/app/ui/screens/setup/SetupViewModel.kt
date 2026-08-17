package com.vaultguard.app.ui.screens.setup

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.vaultguard.app.data.remote.FirebaseSyncService
import com.vaultguard.app.security.MasterPasswordManager
import com.vaultguard.app.util.MasterPasswordPolicy
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
    val isComplete: Boolean = false,
    /** There is no recovery path, so the user has to say they know that (#39). */
    val acknowledgedNoRecovery: Boolean = false
)

@HiltViewModel
class SetupViewModel @Inject constructor(
    private val masterPasswordManager: MasterPasswordManager,
    private val strengthEvaluator: PasswordStrengthEvaluator,
    private val policy: MasterPasswordPolicy,
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

    fun onAcknowledgeNoRecoveryChange(acknowledged: Boolean) {
        _uiState.value = _uiState.value.copy(acknowledgedNoRecovery = acknowledged)
    }

    fun onSetup() {
        val state = _uiState.value

        // Shared with the change-password dialog; the two used to disagree (finding #28).
        val validation = policy.validate(state.password, state.confirmPassword)
        if (validation is MasterPasswordPolicy.Result.Rejected) {
            _uiState.value = state.copy(error = validation.reason)
            return
        }
        if (!state.acknowledgedNoRecovery) {
            _uiState.value = state.copy(
                error = "Please confirm you understand the password cannot be recovered."
            )
            return
        }

        viewModelScope.launch {
            _uiState.value = state.copy(isLoading = true)
            try {
                masterPasswordManager.setup(state.password.toCharArray())
                // Nothing is published here. Setup used to push the vault config to
                // Firestore unconditionally, which quietly created a cloud vault for a
                // user who had never asked for one (#15). Sync publishes it when enabled.
                _uiState.value = _uiState.value.copy(isLoading = false, isComplete = true)
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(isLoading = false, error = "Setup failed: ${e.message}")
            }
        }
    }
}
