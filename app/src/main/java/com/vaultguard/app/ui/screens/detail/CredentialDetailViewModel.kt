package com.vaultguard.app.ui.screens.detail

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.vaultguard.app.domain.model.Credential
import com.vaultguard.app.domain.repository.CredentialRepository
import com.vaultguard.app.security.BreachCheckService
import com.vaultguard.app.security.BreachResult
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

data class DetailUiState(
    val credential: Credential? = null,
    val isLoading: Boolean = true,
    val isDeleted: Boolean = false,
    val error: String? = null,
    val breachResult: BreachResult? = null,
    val isCheckingBreach: Boolean = false
)

@HiltViewModel
class CredentialDetailViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val credentialRepository: CredentialRepository,
    private val breachCheckService: BreachCheckService
) : ViewModel() {

    private val credentialId: String = checkNotNull(savedStateHandle["id"])

    private val _uiState = MutableStateFlow(DetailUiState())
    val uiState: StateFlow<DetailUiState> = _uiState

    init {
        loadCredential()
    }

    fun refresh() {
        loadCredential()
    }

    private fun loadCredential() {
        viewModelScope.launch {
            try {
                val credential = credentialRepository.getById(credentialId)
                _uiState.value = DetailUiState(credential = credential, isLoading = false)
            } catch (e: Exception) {
                _uiState.value = DetailUiState(isLoading = false, error = e.message)
            }
        }
    }

    fun onCheckBreach() {
        val password = _uiState.value.credential?.password ?: return
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isCheckingBreach = true, breachResult = null)
            val result = breachCheckService.check(password)
            _uiState.value = _uiState.value.copy(isCheckingBreach = false, breachResult = result)
        }
    }

    fun onDelete() {
        viewModelScope.launch {
            try {
                credentialRepository.delete(credentialId)
                _uiState.value = _uiState.value.copy(isDeleted = true)
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(error = "Delete failed: ${e.message}")
            }
        }
    }
}
