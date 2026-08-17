package com.vaultguard.app.ui.screens.generator

import androidx.lifecycle.ViewModel
import com.vaultguard.app.data.repository.PasswordPresetRepository
import com.vaultguard.app.domain.model.PasswordGeneratorConfig
import com.vaultguard.app.domain.model.PasswordPreset
import com.vaultguard.app.domain.usecase.GeneratePasswordUseCase
import com.vaultguard.app.util.PasswordStrength
import com.vaultguard.app.util.PasswordStrengthEvaluator
import com.vaultguard.app.util.StrengthLevel
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject

data class GeneratorUiState(
    val password: String = "",
    val config: PasswordGeneratorConfig = PasswordGeneratorConfig(),
    val strength: PasswordStrength = PasswordStrength(0, StrengthLevel.WEAK, 0.0),
    val history: List<String> = emptyList(),
    val presets: List<PasswordPreset> = emptyList(),
    val selectedPresetId: String = PasswordPreset.DEFAULT_ID,
    val showSaveDialog: Boolean = false,
    val savePresetName: String = ""
)

@HiltViewModel
class PasswordGeneratorViewModel @Inject constructor(
    private val generatePasswordUseCase: GeneratePasswordUseCase,
    private val strengthEvaluator: PasswordStrengthEvaluator,
    private val presetRepository: PasswordPresetRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(GeneratorUiState())
    val uiState: StateFlow<GeneratorUiState> = _uiState

    init {
        loadPresets()
        generate()
    }

    private fun loadPresets() {
        val presets = presetRepository.getAll()
        val selected = presets.find { it.id == _uiState.value.selectedPresetId } ?: presets.first()
        _uiState.value = _uiState.value.copy(
            presets = presets,
            selectedPresetId = selected.id,
            config = selected.config
        )
    }

    fun generate() {
        val password = generatePasswordUseCase(_uiState.value.config)
        val history = (_uiState.value.history + password).takeLast(5)
        _uiState.value = _uiState.value.copy(
            password = password,
            strength = strengthEvaluator(password),
            history = history
        )
    }

    fun onSelectPreset(presetId: String) {
        val preset = _uiState.value.presets.find { it.id == presetId } ?: return
        _uiState.value = _uiState.value.copy(
            selectedPresetId = presetId,
            config = preset.config
        )
        generate()
    }

    fun onLengthChange(length: Int) {
        updateConfig(_uiState.value.config.copy(length = length))
    }

    fun onUppercaseToggle(enabled: Boolean) {
        updateConfig(_uiState.value.config.copy(includeUppercase = enabled))
    }

    fun onLowercaseToggle(enabled: Boolean) {
        updateConfig(_uiState.value.config.copy(includeLowercase = enabled))
    }

    fun onDigitsToggle(enabled: Boolean) {
        updateConfig(_uiState.value.config.copy(includeDigits = enabled))
    }

    fun onSymbolsToggle(enabled: Boolean) {
        updateConfig(_uiState.value.config.copy(includeSymbols = enabled))
    }

    fun onExcludeAmbiguousToggle(enabled: Boolean) {
        updateConfig(_uiState.value.config.copy(excludeAmbiguous = enabled))
    }

    private fun updateConfig(config: PasswordGeneratorConfig) {
        _uiState.value = _uiState.value.copy(config = config)
        generate()
    }

    // Save dialog
    fun onShowSaveDialog() {
        _uiState.value = _uiState.value.copy(showSaveDialog = true, savePresetName = "")
    }

    fun onDismissSaveDialog() {
        _uiState.value = _uiState.value.copy(showSaveDialog = false, savePresetName = "")
    }

    fun onSavePresetNameChange(name: String) {
        _uiState.value = _uiState.value.copy(savePresetName = name)
    }

    fun onSavePreset() {
        val name = _uiState.value.savePresetName.trim()
        if (name.isEmpty()) return
        val preset = PasswordPreset(
            id = "",
            name = name,
            config = _uiState.value.config
        )
        val saved = presetRepository.save(preset)
        _uiState.value = _uiState.value.copy(showSaveDialog = false, savePresetName = "")
        loadPresets()
        _uiState.value = _uiState.value.copy(selectedPresetId = saved.id)
    }

    fun onUpdateCurrentPreset() {
        val currentId = _uiState.value.selectedPresetId
        val current = _uiState.value.presets.find { it.id == currentId } ?: return
        presetRepository.save(current.copy(config = _uiState.value.config))
        loadPresets()
    }

    fun onDeletePreset(presetId: String) {
        presetRepository.delete(presetId)
        loadPresets()
        // If deleted was selected, switch to default
        if (_uiState.value.selectedPresetId == presetId) {
            onSelectPreset(PasswordPreset.DEFAULT_ID)
        }
    }
}
