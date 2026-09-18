package com.vaultguard.app.ui.screens.addEdit

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.vaultguard.app.data.repository.PasswordPresetRepository
import com.vaultguard.app.domain.model.Credential
import com.vaultguard.app.domain.model.PasswordPreset
import com.vaultguard.app.domain.repository.CredentialLookup
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
    /**
     * Carried, not edited. The form has no field for these, and rebuilding the credential
     * without them erased the two signals `CredentialMatcher` ranks highest, so editing an
     * autofill-captured entry stopped autofill offering it (finding #61).
     */
    val linkedPackages: List<String> = emptyList(),
    val linkedDomains: List<String> = emptyList(),
    val strength: PasswordStrength = PasswordStrength(0, StrengthLevel.WEAK, 0.0),
    val presets: List<PasswordPreset> = emptyList(),
    val selectedPresetId: String = PasswordPreset.DEFAULT_ID,
    val isEditing: Boolean = false,
    val isLoading: Boolean = false,
    val isSaved: Boolean = false,
    /** The row is missing, locked or unreadable — saving would overwrite it (#38). */
    val isUnavailable: Boolean = false,
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

    /**
     * The editable fields as they stood when the screen opened, for detecting unsaved work
     * (finding #60). Only the fields a user can type into: comparing whole UI states would
     * count a recomputed strength score or a loaded preset list as an edit.
     */
    private data class FormSnapshot(
        val siteName: String,
        val appName: String,
        val url: String,
        val username: String,
        val password: String,
        val notes: String,
        val category: String,
        val tags: String,
        val isPinned: Boolean
    )

    private fun AddEditUiState.snapshot() = FormSnapshot(
        siteName, appName, url, username, password, notes, category, tags, isPinned
    )

    private var pristine: FormSnapshot = AddEditUiState().snapshot()

    /** False once saved — the screen is leaving on purpose at that point. */
    val hasUnsavedChanges: Boolean
        get() = !_uiState.value.isSaved && _uiState.value.snapshot() != pristine

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
                when (val lookup = credentialRepository.getById(id)) {
                    is CredentialLookup.Found -> {
                        val credential = lookup.credential
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
                            linkedPackages = credential.linkedPackages,
                            linkedDomains = credential.linkedDomains,
                            strength = strengthEvaluator(credential.password),
                            presets = _uiState.value.presets,
                            isEditing = true,
                            isLoading = false
                        )
                        pristine = _uiState.value.snapshot()
                    }
                    // Every branch below must clear isLoading. The previous code only
                    // handled the found case, so a missing row left the screen spinning
                    // forever (finding #38).
                    is CredentialLookup.Undecryptable -> _uiState.value = _uiState.value.copy(
                        isLoading = false,
                        isUnavailable = true,
                        error = "This credential could not be decrypted, so it cannot be " +
                            "edited. Do not overwrite it — it is still stored on the device."
                    )
                    CredentialLookup.NotFound -> _uiState.value = _uiState.value.copy(
                        isLoading = false,
                        isUnavailable = true,
                        error = "This credential no longer exists."
                    )
                    CredentialLookup.Locked -> _uiState.value = _uiState.value.copy(
                        isLoading = false,
                        isUnavailable = true,
                        error = "The vault is locked."
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

    /**
     * Other websites and apps this entry fills. The matcher ranks these above the url,
     * autofill's save flow adds to them, and until now nothing showed them (they were
     * carried through editing invisibly, #61). A domain is normalised to its host; an
     * app is a package name as-is.
     */
    fun onAddLinkedDomain(value: String) {
        val host = com.vaultguard.app.autofill.CredentialMatcher.normaliseHost(value) ?: return
        _uiState.value = _uiState.value.copy(linkedDomains = (_uiState.value.linkedDomains + host).distinct())
    }

    fun onRemoveLinkedDomain(value: String) {
        _uiState.value = _uiState.value.copy(linkedDomains = _uiState.value.linkedDomains - value)
    }

    fun onAddLinkedPackage(value: String) {
        val pkg = value.trim().lowercase().takeIf { it.contains('.') } ?: return
        _uiState.value = _uiState.value.copy(linkedPackages = (_uiState.value.linkedPackages + pkg).distinct())
    }

    fun onRemoveLinkedPackage(value: String) {
        _uiState.value = _uiState.value.copy(linkedPackages = _uiState.value.linkedPackages - value)
    }
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
        if (state.isUnavailable) {
            // Saving here would replace a row we could not read with a blank one.
            _uiState.value = state.copy(error = "This credential cannot be edited.")
            return
        }
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
                    linkedPackages = state.linkedPackages,
                    linkedDomains = state.linkedDomains,
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
