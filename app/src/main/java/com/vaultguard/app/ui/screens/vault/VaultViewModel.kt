package com.vaultguard.app.ui.screens.vault

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.vaultguard.app.domain.model.CredentialSummary
import com.vaultguard.app.domain.repository.CredentialLookup
import com.vaultguard.app.domain.repository.CredentialRepository
import com.vaultguard.app.data.remote.FirebaseSyncService
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

enum class SortMode(val label: String) {
    NAME_ASC("Name A-Z"),
    NAME_DESC("Name Z-A"),
    NEWEST("Newest first"),
    OLDEST("Oldest first")
}

data class VaultUiState(
    val credentials: List<CredentialSummary> = emptyList(),
    val searchQuery: String = "",
    val sortMode: SortMode = SortMode.NAME_ASC,
    val filterCategory: String? = null,
    val availableCategories: List<String> = emptyList(),
    val isLoading: Boolean = false,
    val isSyncing: Boolean = false,
    val error: String? = null,
    /**
     * Rows present in the database that could not be decrypted (finding #40).
     * Non-zero means real trouble and must never be rendered as an empty vault.
     */
    val undecryptableCount: Int = 0
)

@HiltViewModel
class VaultViewModel @Inject constructor(
    private val credentialRepository: CredentialRepository,
    private val syncService: FirebaseSyncService
) : ViewModel() {

    private val allCredentials = MutableStateFlow<List<CredentialSummary>>(emptyList())
    private val undecryptableCount = MutableStateFlow(0)
    private val searchQuery = MutableStateFlow("")
    private val sortMode = MutableStateFlow(SortMode.NAME_ASC)
    private val filterCategory = MutableStateFlow<String?>(null)
    private val isLoading = MutableStateFlow(false)
    private val isSyncing = MutableStateFlow(false)
    private val error = MutableStateFlow<String?>(null)

    val uiState: StateFlow<VaultUiState> = combine(
        allCredentials, searchQuery, sortMode, filterCategory,
        combine(isLoading, isSyncing, error, undecryptableCount) { l, s, e, u -> Quad(l, s, e, u) }
    ) { creds, query, sort, category, (loading, syncing, err, undecryptable) ->
        val filtered = filterAndSort(creds, query, sort, category)
        val categories = creds.map { it.category }.filter { it.isNotEmpty() }.distinct().sorted()
        VaultUiState(
            credentials = filtered,
            searchQuery = query,
            sortMode = sort,
            filterCategory = category,
            availableCategories = categories,
            isLoading = loading,
            isSyncing = syncing,
            error = err,
            undecryptableCount = undecryptable
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), VaultUiState(isLoading = true))

    private data class Quad<A, B, C, D>(val a: A, val b: B, val c: C, val d: D)

    init {
        loadCredentials()
    }

    private fun loadCredentials() {
        viewModelScope.launch {
            isLoading.value = true
            credentialRepository.getAllSummaries()
                .catch { e ->
                    isLoading.value = false
                    error.value = e.message
                }
                .collect { snapshot ->
                    allCredentials.value = snapshot.items
                    undecryptableCount.value = snapshot.undecryptableCount
                    isLoading.value = false
                }
        }
    }

    private fun filterAndSort(
        credentials: List<CredentialSummary>,
        query: String,
        sort: SortMode,
        category: String?
    ): List<CredentialSummary> {
        val lowerQuery = query.lowercase()

        val filtered = credentials.filter { cred ->
            val matchesQuery = query.isBlank() ||
                cred.siteName.lowercase().contains(lowerQuery) ||
                cred.appName.lowercase().contains(lowerQuery) ||
                cred.username.lowercase().contains(lowerQuery) ||
                cred.category.lowercase().contains(lowerQuery) ||
                cred.tags.any { it.lowercase().contains(lowerQuery) }

            val matchesCategory = category == null || cred.category.equals(category, ignoreCase = true)

            matchesQuery && matchesCategory
        }

        val sorted = when (sort) {
            SortMode.NAME_ASC -> filtered.sortedBy { it.displayName.lowercase() }
            SortMode.NAME_DESC -> filtered.sortedByDescending { it.displayName.lowercase() }
            SortMode.NEWEST -> filtered.sortedByDescending { it.createdAt }
            SortMode.OLDEST -> filtered.sortedBy { it.createdAt }
        }

        // Pinned entries always on top, preserving sort order within each group
        return sorted.sortedByDescending { it.isPinned }
    }

    fun onSearchQueryChange(query: String) {
        searchQuery.value = query
    }

    fun onSortModeChange(mode: SortMode) {
        sortMode.value = mode
    }

    fun onFilterCategoryChange(category: String?) {
        filterCategory.value = category
    }

    fun onTogglePin(summary: CredentialSummary) {
        viewModelScope.launch {
            when (val lookup = credentialRepository.getById(summary.id)) {
                is CredentialLookup.Found ->
                    credentialRepository.save(
                        lookup.credential.copy(isPinned = !lookup.credential.isPinned)
                    )
                is CredentialLookup.Undecryptable ->
                    error.value = "Could not read “${summary.displayName}” to pin it."
                CredentialLookup.NotFound ->
                    error.value = "“${summary.displayName}” no longer exists."
                CredentialLookup.Locked -> Unit // the lock event navigates away on its own
            }
        }
    }

    fun onDismissError() {
        error.value = null
    }

    fun onSync() {
        // Pulling to refresh used to sign in anonymously and upload the entire vault to an
        // account the owner never created (#15). It now does nothing unless sync has been
        // switched on deliberately.
        if (!syncService.isSyncEnabled) {
            error.value = "Cloud sync is off. Turn it on in Settings to sync."
            return
        }

        viewModelScope.launch {
            isSyncing.value = true
            try {
                val result = syncService.fullSync()
                if (result.conflicts > 0) {
                    error.value = "${result.conflicts} entry(s) were edited in two places — " +
                        "both copies were kept, review them in the list."
                }
            } catch (e: Exception) {
                error.value = "Sync failed: ${e.message}"
            } finally {
                isSyncing.value = false
            }
        }
    }
}
