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
import androidx.compose.material3.ButtonDefaults
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
import com.vaultguard.app.BuildConfig
import timber.log.Timber
import androidx.core.net.toUri

/**
 * Where the autofill service is chosen. The app used to point at
 * "System → Languages & Input", which has not been correct for several Android versions —
 * so anyone following it went looking in the wrong place.
 */
private const val AUTOFILL_SETTINGS_PATH =
    "Settings → Passwords, passkeys & accounts → Autofill service " +
        "(on Samsung: General management → Passwords)"

@Suppress("AssignedValueIsNeverRead")
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onNavigateBack: () -> Unit,
    onLockVault: () -> Unit,
    onNavigateToFeedback: () -> Unit = {},
    viewModel: SettingsViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    val context = LocalContext.current
    val activity = remember(context) {
        var ctx = context
        while (ctx is android.content.ContextWrapper) {
            if (ctx is androidx.fragment.app.FragmentActivity) return@remember ctx
            ctx = ctx.baseContext
        }
        Timber.tag("SettingsScreen").e("No FragmentActivity in the context chain; biometrics unavailable")
        null
    }
    val lifecycleOwner = LocalLifecycleOwner.current
    val snackbarHostState = remember { SnackbarHostState() }
    val autofillScope = rememberCoroutineScope()

    var showChangePasswordDialog by remember { mutableStateOf(false) }
    var showImportDialog by remember { mutableStateOf(false) }
    var importUri by remember { mutableStateOf<android.net.Uri?>(null) }
    var exportUri by remember { mutableStateOf<android.net.Uri?>(null) }
    var showDeleteCloudDialog by remember { mutableStateOf(false) }

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
        // The backup password is asked for after a destination is chosen, so a cancelled
        // file picker does not waste the user's time typing one.
        uri?.let { exportUri = it }
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
            validate = viewModel::validateNewMasterPassword,
            onDismiss = { showChangePasswordDialog = false },
            onConfirm = { current, new ->
                showChangePasswordDialog = false
                viewModel.onChangeMasterPassword(current, new)
            }
        )
    }

    // Export password dialog
    exportUri?.let { uri ->
        BackupPasswordDialog(
            title = "Encrypt this backup",
            body = "Choose a password for the backup file. It is independent of your master " +
                "password, and it is the only thing that can open the file — if you lose it, " +
                "the backup is unrecoverable.",
            confirmText = "Export",
            requireConfirmation = true,
            onDismiss = { exportUri = null },
            onConfirm = { password ->
                exportUri = null
                viewModel.onExport(uri, password)
            }
        )
    }

    if (showDeleteCloudDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteCloudDialog = false },
            title = { Text("Delete the cloud copy?") },
            text = {
                Text(
                    "This removes every encrypted entry and the vault settings from your " +
                        "Google account, and turns sync off.\n\n" +
                        "The vault on this device is not touched. Any other device still " +
                        "syncing will keep its own copy until it next tries to sync."
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    showDeleteCloudDialog = false
                    viewModel.onDisableSync(deleteRemote = true)
                }) { Text("Delete cloud copy") }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteCloudDialog = false }) { Text("Cancel") }
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
                        minutesLabel(uiState.autoLockTimeout),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                DropdownMenu(expanded = timeoutExpanded, onDismissRequest = { timeoutExpanded = false }) {
                    timeoutOptions.forEach { minutes ->
                        DropdownMenuItem(
                            text = { Text(minutesLabel(minutes)) },
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
            Spacer(modifier = Modifier.height(16.dp))
            Text("Autofill", style = MaterialTheme.typography.titleMedium)
            Spacer(modifier = Modifier.height(8.dp))

            if (!uiState.autofillSupported) {
                Text(
                    "This device does not support autofill.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            if (uiState.autofillEnabled) "VaultGuard is your autofill service"
                            else "VaultGuard is not your autofill service",
                            style = MaterialTheme.typography.bodyLarge
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            if (uiState.autofillEnabled)
                                "Android will offer your saved passwords in other apps."
                            else
                                "Android only sends fill requests to the one service you " +
                                    "choose, so nothing is offered until VaultGuard is selected.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )

                        Spacer(modifier = Modifier.height(12.dp))

                        // A Switch was the wrong control: Android has no intent to *unset*
                        // an autofill service, so the off position could never do anything
                        // (finding #37). It fired the same "enable me" intent both ways.
                        if (!uiState.autofillEnabled) {
                            Button(
                                onClick = {
                                    val intent = Intent(Settings.ACTION_REQUEST_SET_AUTOFILL_SERVICE)
                                        .apply { data = "package:${context.packageName}".toUri() }
                                    val opened = runCatching { context.startActivity(intent) }.isSuccess
                                    if (!opened) {
                                        autofillScope.launch {
                                            snackbarHostState.showSnackbar(AUTOFILL_SETTINGS_PATH)
                                        }
                                    }
                                },
                                modifier = Modifier.fillMaxWidth()
                            ) { Text("Enable VaultGuard") }
                        } else {
                            Text(
                                "To turn it off or switch services, pick a different one in " +
                                    "system settings.\n\n" + AUTOFILL_SETTINGS_PATH,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            OutlinedButton(
                                onClick = {
                                    runCatching { context.startActivity(Intent(Settings.ACTION_SETTINGS)) }
                                },
                                modifier = Modifier.fillMaxWidth()
                            ) { Text("Open system settings") }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant
                    )
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text("Using Chrome?", style = MaterialTheme.typography.titleSmall)
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            "Chrome handles password fields with its own manager and ignores " +
                                "the system service until told otherwise. Open Chrome's " +
                                "settings and look for autofill services, then allow another " +
                                "service. The exact wording moves between Chrome versions.\n\n" +
                                "Most other browsers — Firefox, Samsung Internet, Brave, Edge, " +
                                "DuckDuckGo — need nothing beyond the setting above.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                if (uiState.dismissedSavePrompts > 0) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { viewModel.onClearDismissedSavePrompts() }
                            .padding(vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Dismissed save prompts", style = MaterialTheme.typography.bodyLarge)
                            Text(
                                "${uiState.dismissedSavePrompts} site(s) where you tapped Skip. " +
                                    "Tap to offer saving again.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
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
                    // "Local only" is now a fact rather than a hope. Sync used to upload
                    // the vault to an anonymous account on any sync call while this text
                    // claimed nothing left the device (#15).
                    Text(
                        when {
                            uiState.syncEnabled -> "Syncing to ${uiState.googleEmail ?: "your account"}"
                            uiState.isSignedInWithGoogle -> "Signed in, sync off"
                            else -> "Local only"
                        },
                        style = MaterialTheme.typography.bodyLarge
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        when {
                            uiState.syncEnabled ->
                                "Encrypted entries are copied to your Google account. " +
                                    "Only encrypted blobs leave this device."
                            uiState.isSignedInWithGoogle ->
                                "Nothing has been uploaded. Turn on sync to copy your " +
                                    "encrypted vault to your account."
                            else ->
                                "Nothing leaves this device. Sign in and turn on sync to " +
                                    "copy your encrypted vault across devices."
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    Spacer(modifier = Modifier.height(12.dp))

                    if (!uiState.isSignedInWithGoogle) {
                        Button(
                            onClick = { googleSignInLauncher.launch(viewModel.getGoogleSignInIntent()) },
                            enabled = !uiState.isLoading,
                            modifier = Modifier.fillMaxWidth()
                        ) { Text("Sign in with Google") }
                    } else if (!uiState.syncEnabled) {
                        Button(
                            onClick = { viewModel.onEnableSync() },
                            enabled = !uiState.isLoading,
                            modifier = Modifier.fillMaxWidth()
                        ) { Text("Turn on cloud sync") }
                        Spacer(modifier = Modifier.height(8.dp))
                        OutlinedButton(
                            onClick = { viewModel.onSignOutGoogle() },
                            enabled = !uiState.isLoading,
                            modifier = Modifier.fillMaxWidth()
                        ) { Text("Sign out") }
                        Spacer(modifier = Modifier.height(8.dp))
                        // Reachable while sync is off, on purpose: when the account holds a
                        // vault keyed differently (a fresh install under a new master
                        // password), enabling sync refuses and tells the user to delete the
                        // cloud copy - which used to be offered only while sync was on.
                        OutlinedButton(
                            onClick = { showDeleteCloudDialog = true },
                            enabled = !uiState.isLoading,
                            modifier = Modifier.fillMaxWidth(),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error)
                        ) { Text("Delete cloud copy") }
                    } else {
                        Button(
                            onClick = { viewModel.onSyncNow() },
                            enabled = !uiState.isLoading,
                            modifier = Modifier.fillMaxWidth()
                        ) { Text("Sync now") }
                        Spacer(modifier = Modifier.height(8.dp))
                        OutlinedButton(
                            onClick = { viewModel.onDisableSync(deleteRemote = false) },
                            enabled = !uiState.isLoading,
                            modifier = Modifier.fillMaxWidth()
                        ) { Text("Turn off sync") }
                        Spacer(modifier = Modifier.height(8.dp))
                        OutlinedButton(
                            onClick = { showDeleteCloudDialog = true },
                            enabled = !uiState.isLoading,
                            colors = ButtonDefaults.outlinedButtonColors(
                                contentColor = MaterialTheme.colorScheme.error
                            ),
                            modifier = Modifier.fillMaxWidth()
                        ) { Text("Turn off and delete cloud copy") }
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
                    if (stats.undecryptableEntries > 0) {
                        StatRow(
                            "Entries that could not be decrypted",
                            "${stats.undecryptableEntries}",
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                    if (stats.weakPasswords > 0) {
                        StatRow(
                            "Weak passwords",
                            "${stats.weakPasswords}",
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                    if (stats.reusedPasswords > 0) {
                        StatRow(
                            "Passwords used more than once",
                            "${stats.reusedPasswords}",
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
            Text("About", style = MaterialTheme.typography.titleMedium)
            Spacer(modifier = Modifier.height(8.dp))
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("VaultGuard ${BuildConfig.VERSION_NAME}", style = MaterialTheme.typography.bodyLarge)
                    uiState.updateStatus?.let {
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    Row {
                        OutlinedButton(
                            onClick = { viewModel.onCheckForUpdates() },
                            enabled = !uiState.isCheckingForUpdates
                        ) { Text("Check for updates") }
                        uiState.updateUrl?.let { url ->
                            Spacer(modifier = Modifier.width(8.dp))
                            Button(onClick = {
                                context.startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse(url)))
                            }) { Text("Download") }
                        }
                    }
                }
            }

            // Only when the build knows a hub; a button that always fails is worse than none.
            if (BuildConfig.FEEDBACK_KEY.isNotEmpty()) {
                Spacer(modifier = Modifier.height(8.dp))
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onNavigateToFeedback() }
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text("Send feedback", style = MaterialTheme.typography.bodyLarge)
                        Text(
                            "A bug, a request, a question. You see exactly what is sent.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
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

/** "1 minute", not "1 minutes" (finding #63). */
private fun minutesLabel(minutes: Int) =
    if (minutes == 1) "1 minute" else "$minutes minutes"

@Suppress("AssignedValueIsNeverRead")
@Composable
private fun ChangePasswordDialog(
    validate: (newPassword: String, confirmation: String, currentPassword: String) -> String?,
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
                // The same policy setup uses. These two used to disagree, so the vault
                // could be moved to a password setup would have refused (finding #28).
                error = when {
                    currentPassword.isEmpty() -> "Enter your current password"
                    else -> validate(newPassword, confirmPassword, currentPassword)
                }
                if (error == null) onConfirm(currentPassword, newPassword)
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
    var merge by remember { mutableStateOf(true) }

    BackupPasswordDialog(
        title = "Restore backup",
        body = "Enter the password this backup was encrypted with. For older backups that " +
            "is the master password that was in use at the time.",
        confirmText = "Import",
        requireConfirmation = false,
        onDismiss = onDismiss,
        onConfirm = { password -> onConfirm(password, merge) },
        extraContent = {
            Spacer(modifier = Modifier.height(12.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Merge (keep existing)", modifier = Modifier.weight(1f))
                Switch(checked = merge, onCheckedChange = { merge = it })
            }
            Text(
                if (merge) "Existing entries are kept; only unseen ones are added."
                else "The vault is replaced by the backup. Entries not in the backup are removed.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    )
}

/**
 * Password prompt shared by backup export and import.
 *
 * [requireConfirmation] adds a second field, used when exporting: a typo in a backup
 * password is undiscoverable until the day the backup is needed.
 */
@Composable
private fun BackupPasswordDialog(
    title: String,
    body: String,
    confirmText: String,
    requireConfirmation: Boolean,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
    extraContent: @Composable () -> Unit = {}
) {
    var password by remember { mutableStateOf("") }
    var confirmation by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                Text(body, style = MaterialTheme.typography.bodySmall)
                Spacer(modifier = Modifier.height(12.dp))
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it; error = null },
                    label = { Text("Backup password") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Password,
                        autoCorrectEnabled = false
                    ),
                    modifier = Modifier.fillMaxWidth()
                )
                if (requireConfirmation) {
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(
                        value = confirmation,
                        onValueChange = { confirmation = it; error = null },
                        label = { Text("Confirm backup password") },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(
                            keyboardType = KeyboardType.Password,
                            autoCorrectEnabled = false
                        ),
                        modifier = Modifier.fillMaxWidth()
                    )
                }
                extraContent()
                error?.let {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = password.isNotEmpty(),
                onClick = {
                    when {
                        password.length < 8 && requireConfirmation ->
                            error = "Use at least 8 characters"
                        requireConfirmation && password != confirmation ->
                            error = "Passwords do not match"
                        else -> onConfirm(password)
                    }
                }
            ) { Text(confirmText) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
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
