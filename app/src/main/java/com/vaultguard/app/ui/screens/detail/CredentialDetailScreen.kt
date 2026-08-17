package com.vaultguard.app.ui.screens.detail

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
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.vaultguard.app.security.BreachCheckResult
import com.vaultguard.app.ui.components.ConfirmDialog
import com.vaultguard.app.ui.components.PasswordField
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Suppress("AssignedValueIsNeverRead")
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CredentialDetailScreen(
    onNavigateBack: () -> Unit,
    onEditClick: (String) -> Unit,
    onDeleted: (String) -> Unit = { onNavigateBack() },
    viewModel: CredentialDetailViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    var showDeleteDialog by remember { mutableStateOf(false) }

    val lifecycleOwner = LocalLifecycleOwner.current
    LaunchedEffect(lifecycleOwner) {
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            viewModel.refresh()
        }
    }

    LaunchedEffect(uiState.isDeleted) {
        if (uiState.isDeleted) onDeleted(viewModel.credentialId)
    }

    if (showDeleteDialog) {
        ConfirmDialog(
            title = "Delete Credential",
            message = "Are you sure you want to delete this credential?",
            confirmText = "Delete",
            onConfirm = {
                showDeleteDialog = false
                viewModel.onDelete()
            },
            onDismiss = { showDeleteDialog = false }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(uiState.credential?.displayName ?: "Details") },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    uiState.credential?.let { cred ->
                        IconButton(onClick = { onEditClick(cred.id) }) {
                            Icon(Icons.Default.Edit, contentDescription = "Edit")
                        }
                        IconButton(onClick = { showDeleteDialog = true }) {
                            Icon(Icons.Default.Delete, contentDescription = "Delete")
                        }
                    }
                }
            )
        }
    ) { innerPadding ->
        val credential = uiState.credential

        // Previously this composed nothing at all when the credential was null, so a
        // missing or unreadable row rendered as a blank page (finding #38).
        uiState.unavailable?.let { reason ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .padding(24.dp)
            ) {
                Text(
                    text = when (reason) {
                        DetailUiState.Unavailable.NOT_FOUND -> "This credential no longer exists."
                        DetailUiState.Unavailable.LOCKED -> "The vault is locked."
                        DetailUiState.Unavailable.UNDECRYPTABLE ->
                            "This credential could not be decrypted."
                    },
                    style = MaterialTheme.typography.titleMedium
                )
                if (reason == DetailUiState.Unavailable.UNDECRYPTABLE) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "The entry is still stored on this device but cannot be read " +
                            "with the current key. Do not delete it — export a backup and " +
                            "check whether your master password recently changed.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                    uiState.error?.let {
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = it,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                Spacer(modifier = Modifier.height(16.dp))
                OutlinedButton(onClick = onNavigateBack) { Text("Back") }
            }
        }

        if (credential != null) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .padding(16.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                if (credential.appName.isNotEmpty()) {
                    DetailField("App Name", credential.appName)
                }

                if (credential.appName.isNotEmpty() && credential.siteName.isNotEmpty() && credential.appName != credential.siteName) {
                    DetailField("Package / Site", credential.siteName)
                }

                if (credential.url.isNotEmpty()) {
                    DetailField("URL", credential.url) {
                        viewModel.onCopy("URL", credential.url)
                    }
                }

                DetailField("Username", credential.username) {
                    viewModel.onCopy("Username", credential.username)
                }

                Spacer(modifier = Modifier.height(8.dp))

                PasswordField(
                    value = credential.password,
                    onValueChange = {},
                    label = "Password",
                    readOnly = true,
                    onCopy = { viewModel.onCopy("Password", credential.password) }
                )

                Spacer(modifier = Modifier.height(8.dp))

                // Breach check
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedButton(
                        onClick = viewModel::onCheckBreach,
                        enabled = !uiState.isCheckingBreach
                    ) {
                        if (uiState.isCheckingBreach) {
                            CircularProgressIndicator(modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Checking...")
                        } else {
                            Text("Check for breaches")
                        }
                    }
                    Spacer(modifier = Modifier.width(12.dp))
                    uiState.breachResult?.let { result ->
                        when (result) {
                            is BreachCheckResult.Breached -> Text(
                                text = "Found in ${result.occurrences} breaches — change it",
                                color = MaterialTheme.colorScheme.error,
                                style = MaterialTheme.typography.bodySmall
                            )
                            BreachCheckResult.Safe -> Text(
                                text = "Not found in known breaches",
                                color = MaterialTheme.colorScheme.tertiary,
                                style = MaterialTheme.typography.bodySmall
                            )
                            // Neither green nor red: the check did not happen, and saying
                            // "not breached" here was the whole of finding #14.
                            is BreachCheckResult.Unavailable -> Text(
                                text = "Could not check — ${result.reason}",
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "Sends the first five characters of this password's SHA-1 hash to " +
                        "Have I Been Pwned. The password itself never leaves the device.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                if (credential.notes.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(16.dp))
                    Text("Notes", style = MaterialTheme.typography.labelMedium)
                    Spacer(modifier = Modifier.height(4.dp))
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Text(
                            text = credential.notes,
                            modifier = Modifier.padding(12.dp),
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                }

                if (credential.category.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(16.dp))
                    DetailField("Category", credential.category)
                }

                if (credential.tags.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(8.dp))
                    DetailField("Tags", credential.tags.joinToString(", "))
                }

                // Password age indicator
                Spacer(modifier = Modifier.height(16.dp))
                // Reads passwordChangedAt, not updatedAt: pinning the entry or editing
                // its notes must not reset the reported age (finding #29).
                val passwordAgeDays = remember(credential.passwordChangedAt) {
                    ((System.currentTimeMillis() - credential.passwordChangedAt) /
                        (1000L * 60 * 60 * 24)).toInt().coerceAtLeast(0)
                }
                val passwordAgeText = when {
                    passwordAgeDays < 1 -> "Today"
                    passwordAgeDays < 30 -> "$passwordAgeDays days"
                    passwordAgeDays < 365 -> "${passwordAgeDays / 30} months"
                    else -> "${passwordAgeDays / 365} years, ${(passwordAgeDays % 365) / 30} months"
                }
                val passwordAgeColor = when {
                    passwordAgeDays > 180 -> MaterialTheme.colorScheme.error
                    passwordAgeDays > 90 -> MaterialTheme.colorScheme.tertiary
                    else -> MaterialTheme.colorScheme.onSurfaceVariant
                }
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = passwordAgeColor.copy(alpha = 0.1f)
                    )
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Password age: $passwordAgeText",
                                style = MaterialTheme.typography.bodyMedium,
                                color = passwordAgeColor
                            )
                            if (passwordAgeDays > 90) {
                                Text(
                                    text = if (passwordAgeDays > 180) "Consider changing this password"
                                           else "Password is getting old",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = passwordAgeColor
                                )
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))
                val dateFormat = remember { SimpleDateFormat("MMM dd, yyyy HH:mm", Locale.getDefault()) }
                Text(
                    text = "Created: ${dateFormat.format(Date(credential.createdAt))}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = "Updated: ${dateFormat.format(Date(credential.updatedAt))}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun DetailField(
    label: String,
    value: String,
    onCopy: (() -> Unit)? = null
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.labelMedium)
            Text(value, style = MaterialTheme.typography.bodyLarge)
        }
        if (onCopy != null) {
            Spacer(modifier = Modifier.width(8.dp))
            IconButton(onClick = onCopy) {
                Icon(Icons.Default.ContentCopy, contentDescription = "Copy $label")
            }
        }
    }
}
