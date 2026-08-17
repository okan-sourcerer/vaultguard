package com.vaultguard.app.ui.screens.detail

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.vaultguard.app.domain.model.Credential
import com.vaultguard.app.domain.repository.CredentialLookup
import com.vaultguard.app.domain.repository.CredentialRepository
import com.vaultguard.app.security.BreachCheckService
import com.vaultguard.app.security.BreachCheckResult
import com.vaultguard.app.security.SecureClipboard
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
    val breachResult: BreachCheckResult? = null,
    val isCheckingBreach: Boolean = false,
    /** Set when the row is gone or unreadable, so the screen can say so (finding #38). */
    val unavailable: Unavailable? = null
) {
    enum class Unavailable { NOT_FOUND, UNDECRYPTABLE, LOCKED }
}

@HiltViewModel
class CredentialDetailViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val credentialRepository: CredentialRepository,
    private val breachCheckService: BreachCheckService,
    private val clipboard: SecureClipboard
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
                _uiState.value = when (val lookup = credentialRepository.getById(credentialId)) {
                    is CredentialLookup.Found ->
                        DetailUiState(credential = lookup.credential, isLoading = false)
                    is CredentialLookup.Undecryptable ->
                        DetailUiState(
                            isLoading = false,
                            unavailable = DetailUiState.Unavailable.UNDECRYPTABLE,
                            error = lookup.detail
                        )
                    CredentialLookup.NotFound ->
                        DetailUiState(
                            isLoading = false,
                            unavailable = DetailUiState.Unavailable.NOT_FOUND
                        )
                    CredentialLookup.Locked ->
                        DetailUiState(
                            isLoading = false,
                            unavailable = DetailUiState.Unavailable.LOCKED
                        )
                }
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

    /**
     * SecureClipboard is a @Singleton; the screen used to build its own with
     * `remember { SecureClipboard(context) }`, quietly bypassing Hilt (finding #46).
     */
    fun onCopy(label: String, value: String) {
        if (value.isNotEmpty()) clipboard.copyWithAutoExpiry(label, value)
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
