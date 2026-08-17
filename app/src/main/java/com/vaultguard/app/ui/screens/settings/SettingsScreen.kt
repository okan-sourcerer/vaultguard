package com.vaultguard.app.ui.screens.settings

import android.app.Activity
import android.content.Intent
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import kotlinx.coroutines.launch
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import timber.log.Timber
import androidx.core.net.toUri

@Suppress("AssignedValueIsNeverRead")
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onNavigateBack: () -> Unit,
    onLockVault: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    val context = LocalContext.current
    val activity = remember(context) {
        var ctx = context
        while (ctx is android.content.ContextWrapper) {
            Timber.tag("SettingsScreen").d("Context unwrap: ${ctx::class.java.name}")
            if (ctx is androidx.fragment.app.FragmentActivity) return@remember ctx
            ctx = ctx.baseContext
        }
        Timber.tag("SettingsScreen").e("Could NOT find FragmentActivity from context chain!")
        null
    }
    Timber.tag("SettingsScreen")
        .d("activity=$activity, biometricAvailable=${uiState.biometricAvailable}, biometricEnabled=${uiState.biometricEnabled}")
    val lifecycleOwner = LocalLifecycleOwner.current
    val snackbarHostState = remember { SnackbarHostState() }

    var showChangePasswordDialog by remember { mutableStateOf(false) }
    var showImportDialog by remember { mutableStateOf(false) }
    var importUri by remember { mutableStateOf<android.net.Uri?>(null) }

    // Google Sign-In launcher
    val googleSignInLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            viewModel.handleGoogleSignInResult(result.data)
        }
    }

    // File pickers
    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        uri?.let { viewModel.onExport(it) }
    }

    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        uri?.let {
            importUri = it
            showImportDialog = true
        }
    }

    // Refresh autofill state when returning from system settings
    LaunchedEffect(lifecycleOwner) {
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            viewModel.refreshState()
        }
    }

    // Show messages
    LaunchedEffect(uiState.message, uiState.error) {
        val msg = uiState.message ?: uiState.error
        if (msg != null) {
            snackbarHostState.showSnackbar(msg)
            viewModel.clearMessage()
        }
    }

    // Change password dialog
    if (showChangePasswordDialog) {
        ChangePasswordDialog(
            onDismiss = { showChangePasswordDialog = false },
            onConfirm = { current, new ->
                showChangePasswordDialog = false
                viewModel.onChangeMasterPassword(current, new)
            }
        )
    }

    // Import dialog
    if (showImportDialog && importUri != null) {
        ImportDialog(
            onDismiss = { showImportDialog = false },
            onConfirm = { password, merge ->
                showImportDialog = false
                viewModel.onImport(importUri!!, password, merge)
            }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(16.dp)
                .verticalScroll(rememberScrollState())
        ) {
            // Security section
            Text("Security", style = MaterialTheme.typography.titleMedium)
            Spacer(modifier = Modifier.height(8.dp))

            // Auto-lock timeout
            var timeoutExpanded by remember { mutableStateOf(false) }
            val timeoutOptions = listOf(1, 5, 15, 30)

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { timeoutExpanded = true }
                    .padding(vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Auto-lock timeout", style = MaterialTheme.typography.bodyLarge)
                    Text(
                        "${uiState.autoLockTimeout} minutes",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                DropdownMenu(expanded = timeoutExpanded, onDismissRequest = { timeoutExpanded = false }) {
                    timeoutOptions.forEach { minutes ->
                        DropdownMenuItem(
                            text = { Text("$minutes minutes") },
                            onClick = {
                                viewModel.onAutoLockTimeoutChange(minutes)
                                timeoutExpanded = false
                            }
                        )
                    }
                }
            }

            // Biometric toggle
            if (uiState.biometricAvailable) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Biometric unlock", style = MaterialTheme.typography.bodyLarge)
                        Text(
                            "Use fingerprint to unlock vault",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(
                        checked = uiState.biometricEnabled,
                        onCheckedChange = { enabled ->
                            Timber.tag("SettingsScreen")
                                .d("Biometric switch toggled: enabled=$enabled, activity=$activity")
                            if (enabled) {
                                if (activity != null) {
                                    viewModel.onEnableBiometric(activity)
                                } else {
                                    Timber.tag("SettingsScreen")
                                        .e("Cannot enable biometric: activity is null!")
                                }
                            } else {
                                viewModel.onDisableBiometric()
                            }
                        }
                    )
                }
            }

            // Change master password
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { showChangePasswordDialog = true }
                    .padding(vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text("Change master password", style = MaterialTheme.typography.bodyLarge)
                    Text(
                        "Re-encrypts entire vault with new password",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            // Autofill section
            if (uiState.autofillSupported) {
                Spacer(modifier = Modifier.height(16.dp))
                Text("Autofill", style = MaterialTheme.typography.titleMedium)
                Spacer(modifier = Modifier.height(8.dp))

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Autofill service", style = MaterialTheme.typography.bodyLarge)
                        Text(
                            if (uiState.autofillEnabled) "VaultGuard is your autofill provider"
                            else "Tap to enable VaultGuard as autofill provider",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    val autofillScope = rememberCoroutineScope()
                    Switch(
                        checked = uiState.autofillEnabled,
                        onCheckedChange = {
                            val intent = Intent(Settings.ACTION_REQUEST_SET_AUTOFILL_SERVICE).apply {
                                data = "package:${context.packageName}".toUri()
                            }
                            val canResolve = context.packageManager
                                .resolveActivity(intent, 0) != null
                            if (canResolve) {
                                try { context.startActivity(intent) } catch (_: Exception) { }
                            }
                            // Always show the path — some devices resolve the intent silently
                            autofillScope.launch {
                                snackbarHostState.showSnackbar(
                                    "Go to Settings → System → Languages & Input → Autofill service"
                                )
                            }
                        }
                    )
                }
            }

            // Backup section
            Spacer(modifier = Modifier.height(16.dp))
            Text("Backup", style = MaterialTheme.typography.titleMedium)
            Spacer(modifier = Modifier.height(8.dp))

            Row(modifier = Modifier.fillMaxWidth()) {
                OutlinedButton(
                    onClick = { exportLauncher.launch("vaultguard_backup.json") },
                    enabled = !uiState.isLoading,
                    modifier = Modifier.weight(1f)
                ) {
                    Text("Export Vault")
                }
                Spacer(modifier = Modifier.width(8.dp))
                OutlinedButton(
                    onClick = { importLauncher.launch(arrayOf("application/json")) },
                    enabled = !uiState.isLoading,
                    modifier = Modifier.weight(1f)
                ) {
                    Text("Import Vault")
                }
            }

            // Cloud Sync section
            Spacer(modifier = Modifier.height(16.dp))
            Text("Cloud Sync", style = MaterialTheme.typography.titleMedium)
            Spacer(modifier = Modifier.height(8.dp))

            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    if (uiState.isSignedInWithGoogle) {
                        Text("Signed in with Google", style = MaterialTheme.typography.bodyLarge)
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            uiState.googleEmail ?: "",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        val displayName = uiState.googleDisplayName
                        if (!displayName.isNullOrEmpty()) {
                            Text(
                                displayName,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            "Your vault is synced across devices signed in with this Google account.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        OutlinedButton(
                            onClick = { viewModel.onSignOutGoogle() },
                            enabled = !uiState.isLoading,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("Sign Out")
                        }
                    } else {
                        Text("Local only", style = MaterialTheme.typography.bodyLarge)
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            "Sign in with Google to sync your vault across devices. " +
                                "Your passwords remain encrypted — Google only stores encrypted blobs.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        Button(
                            onClick = {
                                val intent = viewModel.getGoogleSignInIntent()
                                googleSignInLauncher.launch(intent)
                            },
                            enabled = !uiState.isLoading,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("Sign in with Google")
                        }
                    }
                }
            }

            // Vault Stats section
            Spacer(modifier = Modifier.height(16.dp))
            Text("Vault Stats", style = MaterialTheme.typography.titleMedium)
            Spacer(modifier = Modifier.height(8.dp))

            val stats = uiState.vaultStats
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    StatRow("Total entries", "${stats.totalEntries}")
                    if (stats.weakPasswords > 0) {
                        StatRow(
                            "Weak passwords",
                            "${stats.weakPasswords}",
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                    if (stats.duplicatePasswords > 0) {
                        StatRow(
                            "Duplicate passwords",
                            "${stats.duplicatePasswords}",
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                    if (stats.oldPasswords > 0) {
                        StatRow(
                            "Passwords older than 90 days",
                            "${stats.oldPasswords}",
                            color = MaterialTheme.colorScheme.tertiary
                        )
                    }
                    if (stats.categoryCounts.isNotEmpty()) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            "By category",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        stats.categoryCounts.toSortedMap().forEach { (category, count) ->
                            StatRow(category, "$count")
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            // Danger zone
            Text("Danger Zone", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.error)
            Spacer(modifier = Modifier.height(8.dp))

            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable {
                        viewModel.onLockVault()
                        onLockVault()
                    },
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)
            ) {
                Text(
                    text = "Lock Vault Now",
                    modifier = Modifier.padding(16.dp),
                    color = MaterialTheme.colorScheme.onErrorContainer
                )
            }
        }
    }
}

@Suppress("AssignedValueIsNeverRead")
@Composable
private fun ChangePasswordDialog(
    onDismiss: () -> Unit,
    onConfirm: (currentPassword: String, newPassword: String) -> Unit
) {
    var currentPassword by remember { mutableStateOf("") }
    var newPassword by remember { mutableStateOf("") }
    var confirmPassword by remember { mutableStateOf("") }

    var error by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Change Master Password") },
        text = {
            Column {
                OutlinedTextField(
                    value = currentPassword,
                    onValueChange = { currentPassword = it; error = null },
                    label = { Text("Current password") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false),
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(
                    value = newPassword,
                    onValueChange = { newPassword = it; error = null },
                    label = { Text("New password") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false),
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(
                    value = confirmPassword,
                    onValueChange = { confirmPassword = it; error = null },
                    label = { Text("Confirm new password") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false),
                    modifier = Modifier.fillMaxWidth()
                )
                if (error != null) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(error!!, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                when {
                    currentPassword.isEmpty() -> error = "Enter current password"
                    newPassword.length < 8 -> error = "New password must be at least 8 characters"
                    newPassword != confirmPassword -> error = "Passwords do not match"
                    else -> onConfirm(currentPassword, newPassword)
                }
            }) { Text("Change") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}

@Composable
private fun ImportDialog(
    onDismiss: () -> Unit,
    onConfirm: (password: String, merge: Boolean) -> Unit
) {
    var password by remember { mutableStateOf("") }
    var merge by remember { mutableStateOf(true) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Import Vault") },
        text = {
            Column {
                Text("Enter the master password that was used when this backup was created.")
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    label = { Text("Backup password") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false),
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(12.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Merge (skip existing)", modifier = Modifier.weight(1f))
                    Switch(checked = merge, onCheckedChange = { merge = it })
                }
                Text(
                    if (merge) "Existing credentials will be kept, only new ones imported"
                    else "All existing credentials will be replaced",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { if (password.isNotEmpty()) onConfirm(password, merge) },
                enabled = password.isNotEmpty()
            ) { Text("Import") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}

@Composable
private fun StatRow(
    label: String,
    value: String,
    color: androidx.compose.ui.graphics.Color = MaterialTheme.colorScheme.onSurface
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium)
        Text(value, style = MaterialTheme.typography.bodyMedium, color = color)
    }
}
