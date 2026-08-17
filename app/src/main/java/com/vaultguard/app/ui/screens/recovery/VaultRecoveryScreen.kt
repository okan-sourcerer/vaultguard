package com.vaultguard.app.ui.screens.recovery

import android.app.Activity
import android.content.Context
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import com.vaultguard.app.data.local.db.VaultDatabaseStatus
import kotlin.system.exitProcess

/**
 * Shown when `vault.db` exists but cannot be opened.
 *
 * The job of this screen is to make it unmistakable that **the data is still on the
 * device**, and to keep every destructive-looking action behind an explicit confirmation.
 * The bug it replaces (finding #1) deleted the vault silently at this exact moment.
 */
@Composable
fun VaultRecoveryScreen(
    viewModel: VaultRecoveryViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    val context = LocalContext.current
    var showQuarantineConfirm by remember { mutableStateOf(false) }

    val unreadable = uiState.status as? VaultDatabaseStatus.Unreadable

    if (showQuarantineConfirm) {
        AlertDialog(
            onDismissRequest = { showQuarantineConfirm = false },
            title = { Text("Set the vault aside?") },
            text = {
                Text(
                    "The existing database will be renamed, not deleted — every byte stays " +
                        "on this device and you can copy it off later.\n\n" +
                        "VaultGuard will then start with an empty vault, and you can import " +
                        "a backup.\n\n" +
                        "Only do this if you have a backup, or if you accept losing access " +
                        "to whatever is in the unreadable database."
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    showQuarantineConfirm = false
                    viewModel.onQuarantine()
                }) { Text("Rename and start fresh") }
            },
            dismissButton = {
                TextButton(onClick = { showQuarantineConfirm = false }) { Text("Cancel") }
            }
        )
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp)
            .verticalScroll(rememberScrollState())
    ) {
        Text("Vault could not be opened", style = MaterialTheme.typography.headlineSmall)
        Spacer(modifier = Modifier.height(12.dp))

        Text(
            "Your encrypted vault file is still on this device and has not been changed. " +
                "VaultGuard could not decrypt it.",
            style = MaterialTheme.typography.bodyMedium
        )

        Spacer(modifier = Modifier.height(16.dp))

        if (unreadable != null) {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    DetailLine("File", unreadable.path)
                    DetailLine("Size", "${unreadable.sizeBytes} bytes")
                    DetailLine(
                        "Likely cause",
                        when (unreadable.reason) {
                            VaultDatabaseStatus.Reason.WRONG_PASSPHRASE_OR_CORRUPT ->
                                "The stored database passphrase does not match this file. " +
                                    "This usually means the app's encrypted preferences were " +
                                    "lost — most often after restoring or migrating a device."
                            VaultDatabaseStatus.Reason.TRANSIENT ->
                                "A temporary problem reading the file, such as an I/O error " +
                                    "or a full disk. Retrying may work."
                        }
                    )
                    unreadable.detail?.let { DetailLine("Reported error", it) }
                }
            }
            Spacer(modifier = Modifier.height(16.dp))
        }

        if (uiState.existingQuarantines.isNotEmpty()) {
            Text(
                "Previously set aside: " + uiState.existingQuarantines.joinToString(", "),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(16.dp))
        }

        uiState.quarantinedTo?.let { name ->
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.tertiaryContainer
                )
            ) {
                Text(
                    "Saved as $name. Restart VaultGuard to begin with an empty vault, " +
                        "then import a backup.",
                    modifier = Modifier.padding(16.dp),
                    style = MaterialTheme.typography.bodyMedium
                )
            }
            Spacer(modifier = Modifier.height(16.dp))
        }

        uiState.error?.let { message ->
            Text(
                message,
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodyMedium
            )
            Spacer(modifier = Modifier.height(16.dp))
        }

        Text(
            "What you can do",
            style = MaterialTheme.typography.titleSmall
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            "1. Copy the file above off the device before changing anything — it is your " +
                "only copy of this vault.\n" +
                "2. If the problem looks temporary, restart and try again.\n" +
                "3. Otherwise set the vault aside and import a backup.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Spacer(modifier = Modifier.height(24.dp))

        Button(
            onClick = { restart(context) },
            enabled = !uiState.isWorking,
            modifier = Modifier.fillMaxWidth()
        ) { Text("Restart and try again") }

        Spacer(modifier = Modifier.height(8.dp))

        OutlinedButton(
            onClick = { showQuarantineConfirm = true },
            enabled = !uiState.isWorking && uiState.quarantinedTo == null,
            modifier = Modifier.fillMaxWidth()
        ) { Text("Set aside and start fresh") }
    }
}

@Composable
private fun DetailLine(label: String, value: String) {
    Column(modifier = Modifier.padding(vertical = 4.dp)) {
        Text(label, style = MaterialTheme.typography.labelMedium)
        Text(value, style = MaterialTheme.typography.bodySmall)
    }
}

/**
 * Relaunches the process. Room caches an open helper against the old file, so continuing
 * in-process after a quarantine would keep using stale state.
 */
private fun restart(context: Context) {
    val intent = context.packageManager.getLaunchIntentForPackage(context.packageName)
    if (intent != null) {
        intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        intent.addFlags(android.content.Intent.FLAG_ACTIVITY_CLEAR_TASK)
        context.startActivity(intent)
    }
    (context as? Activity)?.finish()
    exitProcess(0)
}
