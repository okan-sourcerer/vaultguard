package com.vaultguard.app.ui.screens.addEdit

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.InputChip
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.activity.compose.BackHandler
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.TextButton
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.flow.StateFlow
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import com.vaultguard.app.domain.model.CategoryPresets
import com.vaultguard.app.ui.components.PasswordField
import com.vaultguard.app.ui.components.PasswordStrengthIndicator

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddEditScreen(
    onNavigateBack: () -> Unit,
    onGeneratePassword: () -> Unit,
    generatedPassword: StateFlow<String?>,
    onGeneratedPasswordConsumed: () -> Unit,
    viewModel: AddEditViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()

    // The generator screen had no way to return anything, and this callback was never even
    // invoked — the parameter sat unused (finding #33).
    val fromGenerator by generatedPassword.collectAsState()
    LaunchedEffect(fromGenerator) {
        fromGenerator?.let {
            viewModel.onPasswordChange(it)
            onGeneratedPasswordConsumed()
        }
    }

    LaunchedEffect(uiState.isSaved) {
        if (uiState.isSaved) onNavigateBack()
    }

    // Leaving used to discard everything typed without a word, on the one screen where what
    // was typed may be a password that exists nowhere else yet (finding #60).
    var showDiscardConfirm by remember { mutableStateOf(false) }
    fun attemptBack() {
        if (viewModel.hasUnsavedChanges) showDiscardConfirm = true else onNavigateBack()
    }

    BackHandler(enabled = true) { attemptBack() }

    if (showDiscardConfirm) {
        AlertDialog(
            onDismissRequest = { showDiscardConfirm = false },
            title = { Text("Discard changes?") },
            text = {
                Text(
                    if (uiState.isEditing) {
                        "Your edits to this entry have not been saved."
                    } else {
                        "This entry has not been saved. The password will not be kept."
                    }
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    showDiscardConfirm = false
                    onNavigateBack()
                }) { Text("Discard") }
            },
            dismissButton = {
                TextButton(onClick = { showDiscardConfirm = false }) { Text("Keep editing") }
            }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (uiState.isEditing) "Edit Credential" else "Add Credential") },
                navigationIcon = {
                    IconButton(onClick = { attemptBack() }) {
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
            OutlinedTextField(
                value = uiState.siteName,
                onValueChange = viewModel::onSiteNameChange,
                label = { Text("Site Name *") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(modifier = Modifier.height(8.dp))

            OutlinedTextField(
                value = uiState.appName,
                onValueChange = viewModel::onAppNameChange,
                label = { Text("App Name") },
                placeholder = { Text("e.g. Instagram") },
                supportingText = { Text("Display name shown in the list") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(modifier = Modifier.height(8.dp))

            OutlinedTextField(
                value = uiState.url,
                onValueChange = viewModel::onUrlChange,
                label = { Text("URL") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(modifier = Modifier.height(8.dp))

            OutlinedTextField(
                value = uiState.username,
                onValueChange = viewModel::onUsernameChange,
                label = { Text("Username") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(modifier = Modifier.height(8.dp))

            Row(verticalAlignment = Alignment.CenterVertically) {
                PasswordField(
                    value = uiState.password,
                    onValueChange = viewModel::onPasswordChange,
                    label = "Password *",
                    modifier = Modifier.weight(1f)
                )
                Spacer(modifier = Modifier.width(8.dp))
                IconButton(onClick = viewModel::onGeneratePassword) {
                    Icon(Icons.Default.Refresh, contentDescription = "Generate password")
                }
                IconButton(onClick = onGeneratePassword) {
                    Icon(Icons.Default.Tune, contentDescription = "Open password generator")
                }
            }

            if (uiState.password.isNotEmpty()) {
                Spacer(modifier = Modifier.height(4.dp))
                PasswordStrengthIndicator(strength = uiState.strength)
            }

            // Preset selector for password generation
            if (uiState.presets.isNotEmpty()) {
                Spacer(modifier = Modifier.height(8.dp))
                var presetExpanded by remember { mutableStateOf(false) }
                val selectedPreset = uiState.presets.find { it.id == uiState.selectedPresetId }
                ExposedDropdownMenuBox(
                    expanded = presetExpanded,
                    onExpandedChange = { presetExpanded = it }
                ) {
                    OutlinedTextField(
                        value = "Generate with: ${selectedPreset?.name ?: "Default"}",
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
                                text = { Text(preset.name) },
                                onClick = {
                                    viewModel.onSelectPreset(preset.id)
                                    presetExpanded = false
                                }
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            OutlinedTextField(
                value = uiState.notes,
                onValueChange = viewModel::onNotesChange,
                label = { Text("Notes") },
                minLines = 3,
                maxLines = 5,
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(modifier = Modifier.height(8.dp))

            // Category dropdown with presets
            var categoryExpanded by remember { mutableStateOf(false) }
            ExposedDropdownMenuBox(
                expanded = categoryExpanded,
                onExpandedChange = { categoryExpanded = it }
            ) {
                OutlinedTextField(
                    value = uiState.category,
                    onValueChange = viewModel::onCategoryChange,
                    label = { Text("Category") },
                    singleLine = true,
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = categoryExpanded) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryEditable)
                )
                val filtered = CategoryPresets.list.filter {
                    uiState.category.isBlank() || it.lowercase().contains(uiState.category.lowercase())
                }
                if (filtered.isNotEmpty()) {
                    ExposedDropdownMenu(
                        expanded = categoryExpanded,
                        onDismissRequest = { categoryExpanded = false }
                    ) {
                        filtered.forEach { preset ->
                            DropdownMenuItem(
                                text = { Text(preset) },
                                onClick = {
                                    viewModel.onCategoryChange(preset)
                                    categoryExpanded = false
                                }
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            OutlinedTextField(
                value = uiState.tags,
                onValueChange = viewModel::onTagsChange,
                label = { Text("Tags (comma separated)") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(modifier = Modifier.height(16.dp))

            // Where else this entry fills. One account, several front doors: the website,
            // its app, a second domain. Autofill adds to these when it saves; this is
            // where they can be seen and changed.
            Text("Also fills on", style = MaterialTheme.typography.titleSmall)
            Text(
                "Other websites and apps that use this same login. Subdomains of the URL " +
                    "above already match.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(8.dp))
            LinkedList(
                items = uiState.linkedDomains,
                placeholder = "Website, e.g. login.example.com",
                onAdd = viewModel::onAddLinkedDomain,
                onRemove = viewModel::onRemoveLinkedDomain
            )
            Spacer(modifier = Modifier.height(8.dp))
            LinkedList(
                items = uiState.linkedPackages,
                placeholder = "App package, e.g. com.example.app",
                onAdd = viewModel::onAddLinkedPackage,
                onRemove = viewModel::onRemoveLinkedPackage
            )

            Spacer(modifier = Modifier.height(8.dp))

            // Pin toggle
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Pin to top", style = MaterialTheme.typography.bodyLarge)
                    Text(
                        "Pinned entries appear first in the vault",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(
                    checked = uiState.isPinned,
                    onCheckedChange = viewModel::onPinnedChange
                )
            }

            if (uiState.error != null) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = uiState.error!!,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall
                )
            }

            Spacer(modifier = Modifier.height(24.dp))

            Button(
                onClick = viewModel::onSave,
                enabled = !uiState.isLoading,
                modifier = Modifier.fillMaxWidth()
            ) {
                if (uiState.isLoading) {
                    CircularProgressIndicator(modifier = Modifier.size(20.dp))
                } else {
                    Text(if (uiState.isEditing) "Update" else "Save")
                }
            }
        }
    }
}

/** A row of removable chips plus a field that adds one on Enter or the + button. */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun LinkedList(
    items: List<String>,
    placeholder: String,
    onAdd: (String) -> Unit,
    onRemove: (String) -> Unit
) {
    var draft by remember { mutableStateOf("") }
    fun commit() {
        if (draft.isNotBlank()) {
            onAdd(draft)
            draft = ""
        }
    }
    if (items.isNotEmpty()) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items.forEach { item ->
                InputChip(
                    selected = false,
                    onClick = { onRemove(item) },
                    label = { Text(item) },
                    trailingIcon = { Icon(Icons.Default.Close, contentDescription = "Remove $item") }
                )
            }
        }
        Spacer(modifier = Modifier.height(4.dp))
    }
    OutlinedTextField(
        value = draft,
        onValueChange = { draft = it },
        placeholder = { Text(placeholder) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done, keyboardType = KeyboardType.Uri),
        keyboardActions = KeyboardActions(onDone = { commit() }),
        trailingIcon = {
            IconButton(onClick = { commit() }, enabled = draft.isNotBlank()) {
                Icon(Icons.Default.Add, contentDescription = "Add")
            }
        },
        modifier = Modifier.fillMaxWidth()
    )
}
