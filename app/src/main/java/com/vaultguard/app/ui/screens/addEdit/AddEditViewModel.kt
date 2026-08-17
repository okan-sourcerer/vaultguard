package com.vaultguard.app.ui.screens.addEdit

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.vaultguard.app.data.repository.PasswordPresetRepository
import com.vaultguard.app.domain.model.Credential
import com.vaultguard.app.domain.model.PasswordPreset
import com.vaultguard.app.domain.repository.CredentialRepository
import com.vaultguard.app.domain.usecase.GeneratePasswordUseCase
import com.vaultguard.app.util.PasswordStrength
import com.vaultguard.app.util.PasswordStrengthEvaluator
import com.vaultguard.app.util.StrengthLevel
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.util.UUID
import javax.inject.Inject

data class AddEditUiState(
    val id: String = "",
    val siteName: String = "",
    val appName: String = "",
    val url: String = "",
    val username: String = "",
    val password: String = "",
    val notes: String = "",
    val category: String = "",
    val tags: String = "",
    val isPinned: Boolean = false,
    val createdAt: Long = 0L,
    val strength: PasswordStrength = PasswordStrength(0, StrengthLevel.WEAK, 0.0),
    val presets: List<PasswordPreset> = emptyList(),
    val selectedPresetId: String = PasswordPreset.DEFAULT_ID,
    val isEditing: Boolean = false,
    val isLoading: Boolean = false,
    val isSaved: Boolean = false,
    val error: String? = null
)

@HiltViewModel
class AddEditViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val credentialRepository: CredentialRepository,
    private val generatePasswordUseCase: GeneratePasswordUseCase,
    private val strengthEvaluator: PasswordStrengthEvaluator,
    private val presetRepository: PasswordPresetRepository
) : ViewModel() {

    private val credentialId: String? = savedStateHandle["id"]

    private val _uiState = MutableStateFlow(AddEditUiState())
    val uiState: StateFlow<AddEditUiState> = _uiState

    init {
        loadPresets()
        if (credentialId != null) {
            loadCredential(credentialId)
        }
    }

    private fun loadPresets() {
        val presets = presetRepository.getAll()
        _uiState.value = _uiState.value.copy(presets = presets)
    }

    private fun loadCredential(id: String) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true)
            try {
                val credential = credentialRepository.getById(id)
                if (credential != null) {
                    _uiState.value = AddEditUiState(
                        id = credential.id,
                        siteName = credential.siteName,
                        appName = credential.appName,
                        url = credential.url,
                        username = credential.username,
                        password = credential.password,
                        notes = credential.notes,
                        category = credential.category,
                        tags = credential.tags.joinToString(", "),
                        isPinned = credential.isPinned,
                        createdAt = credential.createdAt,
                        strength = strengthEvaluator(credential.password),
                        isEditing = true,
                        isLoading = false
                    )
                }
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(isLoading = false, error = e.message)
            }
        }
    }

    fun onSiteNameChange(value: String) { _uiState.value = _uiState.value.copy(siteName = value, error = null) }
    fun onAppNameChange(value: String) { _uiState.value = _uiState.value.copy(appName = value) }
    fun onUrlChange(value: String) { _uiState.value = _uiState.value.copy(url = value) }
    fun onUsernameChange(value: String) { _uiState.value = _uiState.value.copy(username = value) }
    fun onPasswordChange(value: String) {
        _uiState.value = _uiState.value.copy(password = value, strength = strengthEvaluator(value), error = null)
    }
    fun onNotesChange(value: String) { _uiState.value = _uiState.value.copy(notes = value) }
    fun onCategoryChange(value: String) { _uiState.value = _uiState.value.copy(category = value) }
    fun onTagsChange(value: String) { _uiState.value = _uiState.value.copy(tags = value) }
    fun onPinnedChange(value: Boolean) { _uiState.value = _uiState.value.copy(isPinned = value) }

    fun onSelectPreset(presetId: String) {
        _uiState.value = _uiState.value.copy(selectedPresetId = presetId)
        onGeneratePassword()
    }

    fun onGeneratePassword() {
        val preset = _uiState.value.presets.find { it.id == _uiState.value.selectedPresetId }
        val config = preset?.config ?: com.vaultguard.app.domain.model.PasswordGeneratorConfig()
        val password = generatePasswordUseCase(config)
        _uiState.value = _uiState.value.copy(
            password = password,
            strength = strengthEvaluator(password)
        )
    }

    fun onSave() {
        val state = _uiState.value
        if (state.siteName.isBlank()) {
            _uiState.value = state.copy(error = "Site name is required")
            return
        }
        if (state.password.isBlank()) {
            _uiState.value = state.copy(error = "Password is required")
            return
        }

        viewModelScope.launch {
            _uiState.value = state.copy(isLoading = true)
            try {
                val credential = Credential(
                    id = state.id.ifEmpty { UUID.randomUUID().toString() },
                    siteName = state.siteName.trim(),
                    appName = state.appName.trim(),
                    url = state.url.trim(),
                    username = state.username.trim(),
                    password = state.password,
                    notes = state.notes.trim(),
                    category = state.category.trim(),
                    tags = state.tags.split(",").map { it.trim() }.filter { it.isNotEmpty() },
                    isPinned = state.isPinned,
                    createdAt = if (state.isEditing) state.createdAt else System.currentTimeMillis()
                )
                credentialRepository.save(credential)
                _uiState.value = _uiState.value.copy(isLoading = false, isSaved = true)
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(isLoading = false, error = "Save failed: ${e.message}")
            }
        }
    }
}
