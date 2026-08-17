package com.vaultguard.app.ui.screens.recovery

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.vaultguard.app.data.local.db.VaultDatabaseHealthCheck
import com.vaultguard.app.data.local.db.VaultDatabaseStatus
import com.vaultguard.app.data.local.db.VaultDatabaseStatusHolder
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

data class VaultRecoveryUiState(
    val status: VaultDatabaseStatus = VaultDatabaseStatus.Healthy,
    val existingQuarantines: List<String> = emptyList(),
    val quarantinedTo: String? = null,
    val error: String? = null,
    val isWorking: Boolean = false
)

@HiltViewModel
class VaultRecoveryViewModel @Inject constructor(
    private val healthCheck: VaultDatabaseHealthCheck,
    statusHolder: VaultDatabaseStatusHolder
) : ViewModel() {

    private val _uiState = MutableStateFlow(
        VaultRecoveryUiState(status = statusHolder.status.value)
    )
    val uiState: StateFlow<VaultRecoveryUiState> = _uiState

    init {
        viewModelScope.launch {
            val quarantines = withContext(Dispatchers.IO) {
                healthCheck.existingQuarantines().map { it.name }
            }
            _uiState.value = _uiState.value.copy(existingQuarantines = quarantines)
        }
    }

    /**
     * Moves the unreadable database aside so a fresh one can be created. Renames — the
     * bytes stay on the device. Confirmed by the user before this is called.
     */
    fun onQuarantine() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isWorking = true, error = null)
            try {
                val moved = withContext(Dispatchers.IO) { healthCheck.quarantine() }
                _uiState.value = _uiState.value.copy(
                    isWorking = false,
                    quarantinedTo = moved.firstOrNull()?.second?.name
                )
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    isWorking = false,
                    error = "Could not move the database aside: ${e.message}. " +
                        "Nothing was changed."
                )
            }
        }
    }
}
