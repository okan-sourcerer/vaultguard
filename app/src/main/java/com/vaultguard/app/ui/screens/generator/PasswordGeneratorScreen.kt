package com.vaultguard.app.ui.screens.generator

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Save
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import com.vaultguard.app.security.SecureClipboard
import com.vaultguard.app.ui.components.PasswordStrengthIndicator

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PasswordGeneratorScreen(
    forResult: Boolean = false,
    onUsePassword: (String) -> Unit = {},
    onNavigateBack: () -> Unit,
    viewModel: PasswordGeneratorViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    val context = LocalContext.current
    val clipboard = remember { SecureClipboard(context) }

    // Save preset dialog
    if (uiState.showSaveDialog) {
        AlertDialog(
            onDismissRequest = viewModel::onDismissSaveDialog,
            title = { Text("Save as Preset") },
            text = {
                OutlinedTextField(
                    value = uiState.savePresetName,
                    onValueChange = viewModel::onSavePresetNameChange,
                    label = { Text("Preset name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                TextButton(
                    onClick = viewModel::onSavePreset,
                    enabled = uiState.savePresetName.isNotBlank()
                ) { Text("Save") }
            },
            dismissButton = {
                TextButton(onClick = viewModel::onDismissSaveDialog) { Text("Cancel") }
            }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Password Generator") },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(16.dp)
                .verticalScroll(rememberScrollState())
        ) {
            // Preset selector
            var presetExpanded by remember { mutableStateOf(false) }
            val selectedPreset = uiState.presets.find { it.id == uiState.selectedPresetId }

            Text("Preset", style = MaterialTheme.typography.titleMedium)
            Spacer(modifier = Modifier.height(4.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                ExposedDropdownMenuBox(
                    expanded = presetExpanded,
                    onExpandedChange = { presetExpanded = it },
                    modifier = Modifier.weight(1f)
                ) {
                    OutlinedTextField(
                        value = selectedPreset?.name ?: "Default",
                        onValueChange = {},
                        readOnly = true,
                        singleLine = true,
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = presetExpanded) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable)
                    )
                    ExposedDropdownMenu(
                        expanded = presetExpanded,
                        onDismissRequest = { presetExpanded = false }
                    ) {
                        uiState.presets.forEach { preset ->
                            DropdownMenuItem(
                                text = {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(preset.name)
                                        if (!preset.isDefault) {
                                            IconButton(
                                                onClick = {
                                                    viewModel.onDeletePreset(preset.id)
                                                    presetExpanded = false
                                                }
                                            ) {
                                                Icon(
                                                    Icons.Default.Delete,
                                                    contentDescription = "Delete preset",
                                                    tint = MaterialTheme.colorScheme.error
                                                )
                                            }
                                        }
                                    }
                                },
                                onClick = {
                                    viewModel.onSelectPreset(preset.id)
                                    presetExpanded = false
                                }
                            )
                        }
                    }
                }
            }

            // Save / Update buttons
            Spacer(modifier = Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedButton(
                    onClick = viewModel::onShowSaveDialog,
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(Icons.Default.Save, contentDescription = null)
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Save as New")
                }
                if (selectedPreset != null && !selectedPreset.isDefault) {
                    OutlinedButton(
                        onClick = viewModel::onUpdateCurrentPreset,
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("Update Preset")
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Generated password display
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = uiState.password,
                        style = MaterialTheme.typography.headlineSmall,
                        fontFamily = FontFamily.Monospace,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    PasswordStrengthIndicator(strength = uiState.strength)
                    Spacer(modifier = Modifier.height(8.dp))
                    Row {
                        IconButton(onClick = {
                            clipboard.copyWithAutoExpiry("Password", uiState.password)
                        }) {
                            Icon(Icons.Default.ContentCopy, contentDescription = "Copy")
                        }
                        IconButton(onClick = viewModel::generate) {
                            Icon(Icons.Default.Refresh, contentDescription = "Regenerate")
                        }
                    }
                }
            }

            // Only when Add/Edit sent us here. Opened from the vault toolbar the generator
            // is a standalone tool and has nowhere to hand a password back to.
            if (forResult) {
                Spacer(modifier = Modifier.height(16.dp))
                Button(
                    onClick = { onUsePassword(uiState.password) },
                    enabled = uiState.password.isNotEmpty(),
                    modifier = Modifier.fillMaxWidth()
                ) { Text("Use this password") }
            }

            Spacer(modifier = Modifier.height(24.dp))

            // Length slider
            Text("Length: ${uiState.config.length}", style = MaterialTheme.typography.titleMedium)
            Slider(
                value = uiState.config.length.toFloat(),
                onValueChange = { viewModel.onLengthChange(it.toInt()) },
                valueRange = 4f..64f,
                steps = 59,
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(modifier = Modifier.height(16.dp))

            // Toggles
            ToggleRow("Uppercase (A-Z)", uiState.config.includeUppercase, viewModel::onUppercaseToggle)
            ToggleRow("Lowercase (a-z)", uiState.config.includeLowercase, viewModel::onLowercaseToggle)
            ToggleRow("Digits (0-9)", uiState.config.includeDigits, viewModel::onDigitsToggle)
            ToggleRow("Symbols (!@#...)", uiState.config.includeSymbols, viewModel::onSymbolsToggle)
            ToggleRow("Exclude ambiguous (0O, 1lI)", uiState.config.excludeAmbiguous, viewModel::onExcludeAmbiguousToggle)

            // History
            if (uiState.history.size > 1) {
                Spacer(modifier = Modifier.height(24.dp))
                Text("Recent", style = MaterialTheme.typography.titleMedium)
                Spacer(modifier = Modifier.height(8.dp))
                uiState.history.reversed().drop(1).forEach { pass ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = pass,
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace,
                            modifier = Modifier.weight(1f)
                        )
                        IconButton(onClick = {
                            clipboard.copyWithAutoExpiry("Password", pass)
                        }) {
                            Icon(Icons.Default.ContentCopy, contentDescription = "Copy")
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ToggleRow(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}
