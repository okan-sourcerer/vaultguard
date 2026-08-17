package com.vaultguard.app.ui.screens.vault

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import com.vaultguard.app.domain.model.CredentialSummary

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun VaultScreen(
    onCredentialClick: (String) -> Unit,
    onAddClick: () -> Unit,
    onGeneratorClick: () -> Unit,
    onSettingsClick: () -> Unit,
    deletedCredentialId: StateFlow<String?> = MutableStateFlow(null),
    onDeletionHandled: () -> Unit = {},
    viewModel: VaultViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    val focusManager = LocalFocusManager.current
    val snackbarHostState = remember { SnackbarHostState() }

    // Deleting used to be final the moment it was confirmed, with the row kept as a
    // tombstone nothing could reach — the safety of a soft delete without the benefit
    // (finding #59). The undo offer is that benefit; letting it go is what makes the
    // deletion real.
    val justDeleted by deletedCredentialId.collectAsState()
    LaunchedEffect(justDeleted) {
        val id = justDeleted ?: return@LaunchedEffect
        val result = snackbarHostState.showSnackbar(
            message = "Credential deleted",
            actionLabel = "Undo",
            duration = SnackbarDuration.Long
        )
        when (result) {
            SnackbarResult.ActionPerformed -> viewModel.onUndoDelete(id)
            // Dismissed, or timed out. If the process dies first the row simply stays a
            // tombstone, which is where it was before this existed.
            SnackbarResult.Dismissed -> viewModel.onFinaliseDelete(id)
        }
        onDeletionHandled()
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { Text("VaultGuard") },
                actions = {
                    IconButton(onClick = onGeneratorClick) {
                        Icon(Icons.Default.Refresh, contentDescription = "Password Generator")
                    }
                    IconButton(onClick = onSettingsClick) {
                        Icon(Icons.Default.Settings, contentDescription = "Settings")
                    }
                }
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = onAddClick) {
                Icon(Icons.Default.Add, contentDescription = "Add credential")
            }
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            // Search bar
            OutlinedTextField(
                value = uiState.searchQuery,
                onValueChange = viewModel::onSearchQueryChange,
                placeholder = { Text("Search credentials...") },
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                trailingIcon = {
                    if (uiState.searchQuery.isNotEmpty()) {
                        IconButton(onClick = { viewModel.onSearchQueryChange("") }) {
                            Icon(Icons.Default.Clear, contentDescription = "Clear search")
                        }
                    }
                },
                singleLine = true,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp)
            )

            // Sort + Filter row
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                // Sort dropdown
                var sortExpanded by remember { mutableStateOf(false) }
                Box {
                    TextButton(onClick = { sortExpanded = true }) {
                        Text(uiState.sortMode.label, style = MaterialTheme.typography.labelLarge)
                    }
                    DropdownMenu(expanded = sortExpanded, onDismissRequest = { sortExpanded = false }) {
                        SortMode.entries.forEach { mode ->
                            DropdownMenuItem(
                                text = { Text(mode.label) },
                                onClick = {
                                    viewModel.onSortModeChange(mode)
                                    sortExpanded = false
                                }
                            )
                        }
                    }
                }

                // Credential count
                Text(
                    "${uiState.credentials.size} items",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            // Category filter chips
            if (uiState.availableCategories.isNotEmpty()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState())
                        .padding(horizontal = 16.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    FilterChip(
                        selected = uiState.filterCategory == null,
                        onClick = { viewModel.onFilterCategoryChange(null) },
                        label = { Text("All") }
                    )
                    uiState.availableCategories.forEach { category ->
                        FilterChip(
                            selected = uiState.filterCategory == category,
                            onClick = {
                                viewModel.onFilterCategoryChange(
                                    if (uiState.filterCategory == category) null else category
                                )
                            },
                            label = { Text(category) }
                        )
                    }
                }
            }

            // Undecryptable rows are damage, not absence. Surfacing this is the whole
            // point of finding #40 — the previous code dropped such rows silently, so a
            // vault nothing could read looked exactly like a vault with nothing in it.
            if (uiState.undecryptableCount > 0) {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer
                    )
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            text = "${uiState.undecryptableCount} " +
                                if (uiState.undecryptableCount == 1) "entry could not be decrypted"
                                else "entries could not be decrypted",
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.onErrorContainer
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "They are still stored on this device but cannot be read " +
                                "with the current key. Do not delete anything or change your " +
                                "master password until this is resolved.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onErrorContainer
                        )
                    }
                }
            }

            uiState.error?.let { message ->
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 4.dp)
                        .clickable { viewModel.onDismissError() },
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer
                    )
                ) {
                    Text(
                        text = message,
                        modifier = Modifier.padding(12.dp),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onErrorContainer
                    )
                }
            }

            // Content
            PullToRefreshBox(
                isRefreshing = uiState.isSyncing,
                onRefresh = viewModel::onSync,
                modifier = Modifier.fillMaxSize()
            ) {
                if (uiState.isLoading) {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                } else if (uiState.credentials.isEmpty()) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .verticalScroll(rememberScrollState()),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = when {
                                uiState.searchQuery.isNotEmpty() || uiState.filterCategory != null ->
                                    "No matching credentials."
                                // Never claim the vault is empty when rows exist but
                                // could not be read (finding #40).
                                uiState.undecryptableCount > 0 ->
                                    "Nothing readable to show.\nSee the warning above."
                                else -> "No credentials yet.\nTap + to add one."
                            },
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                } else {
                    // Group by first letter for section headers (only for name sorts)
                    val showHeaders = uiState.sortMode == SortMode.NAME_ASC || uiState.sortMode == SortMode.NAME_DESC
                    val grouped = if (showHeaders) {
                        uiState.credentials.groupBy { cred ->
                            if (cred.isPinned) "\u2606" // star for pinned
                            else cred.displayName.firstOrNull()?.uppercase() ?: "#"
                        }
                    } else null

                    LazyColumn(modifier = Modifier.fillMaxSize()) {
                        if (grouped != null) {
                            grouped.forEach { (letter, credentials) ->
                                stickyHeader(key = "header_$letter") {
                                    Text(
                                        text = if (letter == "\u2606") "Pinned" else letter,
                                        style = MaterialTheme.typography.labelLarge,
                                        color = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(horizontal = 20.dp, vertical = 4.dp)
                                    )
                                }
                                items(credentials, key = { it.id }) { credential ->
                                    CredentialCard(
                                        credential = credential,
                                        onClick = {
                                            focusManager.clearFocus()
                                            onCredentialClick(credential.id)
                                        },
                                        onTogglePin = { viewModel.onTogglePin(credential) }
                                    )
                                }
                            }
                        } else {
                            items(uiState.credentials, key = { it.id }) { credential ->
                                CredentialCard(
                                    credential = credential,
                                    onClick = {
                                        focusManager.clearFocus()
                                        onCredentialClick(credential.id)
                                    },
                                    onTogglePin = { viewModel.onTogglePin(credential) }
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CredentialCard(
    credential: CredentialSummary,
    onClick: () -> Unit,
    onTogglePin: () -> Unit
) {
    val dismissState = rememberSwipeToDismissBoxState()
    LaunchedEffect(dismissState.currentValue) {
        if (dismissState.currentValue == SwipeToDismissBoxValue.StartToEnd) {
            onTogglePin()
            dismissState.snapTo(SwipeToDismissBoxValue.Settled)
        }
    }

    SwipeToDismissBox(
        state = dismissState,
        enableDismissFromStartToEnd = true,
        enableDismissFromEndToStart = false,
        backgroundContent = {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 16.dp, vertical = 4.dp)
                    .background(
                        MaterialTheme.colorScheme.primaryContainer,
                        shape = MaterialTheme.shapes.medium
                    ),
                contentAlignment = Alignment.CenterStart
            ) {
                Icon(
                    imageVector = Icons.Filled.PushPin,
                    contentDescription = if (credential.isPinned) "Unpin" else "Pin",
                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.padding(start = 20.dp)
                )
            }
        }
    ) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 4.dp)
                .clickable(onClick = onClick)
        ) {
            Row(
                modifier = Modifier.padding(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = credential.displayName,
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    if (credential.username.isNotEmpty()) {
                        Text(
                            text = credential.username,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    } else {
                        Text(
                            text = "No username",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.error.copy(alpha = 0.7f),
                            maxLines = 1
                        )
                    }
                }
                if (credential.category.isNotEmpty()) {
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = credential.category,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
                // Shown in both states. It used to render only when already pinned, so the
                // list could unpin but never pin, and the swipe gesture that could — with
                // no affordance until the swipe is under way — was the only route short of
                // opening the entry and editing it (finding #57).
                IconButton(onClick = onTogglePin) {
                    Icon(
                        imageVector = if (credential.isPinned) {
                            Icons.Filled.PushPin
                        } else {
                            Icons.Outlined.PushPin
                        },
                        contentDescription = if (credential.isPinned) "Unpin" else "Pin",
                        tint = if (credential.isPinned) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        }
                    )
                }
            }
        }
    }
}
