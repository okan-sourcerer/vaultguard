package com.vaultguard.app.ui.screens.settings

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import android.view.autofill.AutofillManager
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.vaultguard.app.autofill.AutofillDismissedPrefs
import com.vaultguard.app.data.remote.FirebaseSyncService
import com.vaultguard.app.data.remote.SyncResult
import com.vaultguard.app.domain.repository.CredentialRepository
import com.vaultguard.app.domain.usecase.ChangeMasterPasswordUseCase
import com.vaultguard.app.domain.usecase.backup.ExportVaultUseCase
import com.vaultguard.app.domain.usecase.backup.ImportVaultUseCase
import com.vaultguard.app.domain.usecase.backup.VaultBackupFormat
import com.vaultguard.app.domain.usecase.backup.WrongBackupPasswordException
import com.vaultguard.app.security.BiometricAuthManager
import com.vaultguard.app.security.GoogleAuthManager
import com.vaultguard.app.security.GoogleSignInResult
import com.vaultguard.app.security.MasterPasswordManager
import com.vaultguard.app.security.VaultAutoLock
import com.vaultguard.app.util.MasterPasswordPolicy
import com.vaultguard.app.util.PasswordStrengthEvaluator
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import com.vaultguard.app.BuildConfig
import com.vaultguard.app.update.UpdateCheck
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

data class VaultStats(
    val totalEntries: Int = 0,
    val weakPasswords: Int = 0,
    val reusedPasswords: Int = 0,
    val oldPasswords: Int = 0,
    val categoryCounts: Map<String, Int> = emptyMap(),
    /** Rows that exist but could not be decrypted (finding #40). */
    val undecryptableEntries: Int = 0
)

data class SettingsUiState(
    val autoLockTimeout: Int = 5,
    val biometricAvailable: Boolean = false,
    val biometricEnabled: Boolean = false,
    val autofillEnabled: Boolean = false,
    val dismissedSavePrompts: Int = 0,
    val autofillSupported: Boolean = false,
    val isSignedInWithGoogle: Boolean = false,
    val syncEnabled: Boolean = false,
    val googleEmail: String? = null,
    val googleDisplayName: String? = null,
    val vaultStats: VaultStats = VaultStats(),
    val isLoading: Boolean = false,
    val message: String? = null,
    val error: String? = null,
    /** Result of the last "Check for updates"; null until asked. */
    val updateStatus: String? = null,
    /** The release page to open when a newer version exists. */
    val updateUrl: String? = null,
    val isCheckingForUpdates: Boolean = false
)

@HiltViewModel
class SettingsViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val masterPasswordManager: MasterPasswordManager,
    private val biometricAuthManager: BiometricAuthManager,
    private val vaultAutoLock: VaultAutoLock,
    private val credentialRepository: CredentialRepository,
    private val strengthEvaluator: PasswordStrengthEvaluator,
    private val masterPasswordPolicy: MasterPasswordPolicy,
    private val exportVaultUseCase: ExportVaultUseCase,
    private val importVaultUseCase: ImportVaultUseCase,
    private val changeMasterPasswordUseCase: ChangeMasterPasswordUseCase,
    private val googleAuthManager: GoogleAuthManager,
    private val syncService: FirebaseSyncService,
    private val dismissedPrefs: AutofillDismissedPrefs
) : ViewModel() {

    private val _uiState = MutableStateFlow(SettingsUiState())
    val uiState: StateFlow<SettingsUiState> = _uiState

    init {
        refreshState()
        loadVaultStats()
    }

    private fun loadVaultStats() {
        viewModelScope.launch {
            credentialRepository.getAllCredentials().collect { snapshot ->
                val credentials = snapshot.items
                val now = System.currentTimeMillis()
                val ninetyDaysMs = 90L * 24 * 60 * 60 * 1000

                // The shared evaluator, not a second opinion. This used to run its own
                // composition check that called a 40-character generated passphrase weak
                // for lacking a capital letter, while the evaluator called the same string
                // VERY_STRONG (finding #26).
                val weakPasswords = credentials.count { strengthEvaluator(it.password).isWeak }

                // Blank passwords are not "duplicates of each other", and counting the
                // members of every reuse group reported 2 for one reused password (#30).
                // This counts the passwords that are reused, not the entries affected.
                val reusedPasswords = credentials
                    .filter { it.password.isNotBlank() }
                    .groupBy { it.password }
                    .count { (_, group) -> group.size > 1 }

                // passwordChangedAt, not updatedAt — a pin toggle is not a rotation (#29).
                val oldPasswords = credentials.count { (now - it.passwordChangedAt) > ninetyDaysMs }

                val categoryCounts = credentials
                    .filter { it.category.isNotEmpty() }
                    .groupBy { it.category }
                    .mapValues { it.value.size }

                _uiState.value = _uiState.value.copy(
                    vaultStats = VaultStats(
                        totalEntries = credentials.size,
                        weakPasswords = weakPasswords,
                        reusedPasswords = reusedPasswords,
                        oldPasswords = oldPasswords,
                        categoryCounts = categoryCounts,
                        undecryptableEntries = snapshot.undecryptableCount
                    )
                )
            }
        }
    }

    fun refreshState() {
        val autofillManager = context.getSystemService(AutofillManager::class.java)
        val bioAvailable = biometricAuthManager.isBiometricAvailable
        val bioEnabled = biometricAuthManager.isBiometricEnabled
        Log.d("SettingsVM", "refreshState: biometricAvailable=$bioAvailable, biometricEnabled=$bioEnabled")
        _uiState.value = _uiState.value.copy(
            autoLockTimeout = vaultAutoLock.timeoutMinutes,
            biometricAvailable = bioAvailable,
            biometricEnabled = bioEnabled,
            autofillSupported = autofillManager?.isAutofillSupported == true,
            autofillEnabled = autofillManager?.hasEnabledAutofillServices() == true,
            dismissedSavePrompts = dismissedPrefs.dismissedCount,
            isSignedInWithGoogle = googleAuthManager.isSignedInWithGoogle,
            syncEnabled = syncService.isSyncEnabled,
            googleEmail = googleAuthManager.currentUserEmail,
            googleDisplayName = googleAuthManager.currentUserDisplayName
        )
    }

    fun onAutoLockTimeoutChange(minutes: Int) {
        _uiState.value = _uiState.value.copy(autoLockTimeout = minutes)
        vaultAutoLock.timeoutMinutes = minutes
    }

    fun onEnableBiometric(activity: FragmentActivity) {
        Log.d("SettingsVM", "onEnableBiometric called, activity=$activity, vaultUnlocked=${masterPasswordManager.isVaultUnlocked}")
        if (!masterPasswordManager.isVaultUnlocked) {
            _uiState.value = _uiState.value.copy(error = "Vault must be unlocked first")
            return
        }
        biometricAuthManager.enableBiometric(
            vaultKey = masterPasswordManager.getSessionKey(),
            activity = activity,
            onResult = { success ->
                if (success) {
                    _uiState.value = _uiState.value.copy(
                        biometricEnabled = true,
                        message = "Biometric unlock enabled"
                    )
                } else {
                    _uiState.value = _uiState.value.copy(error = "Biometric setup failed")
                }
            }
        )
    }

    fun onDisableBiometric() {
        biometricAuthManager.disableBiometric()
        _uiState.value = _uiState.value.copy(biometricEnabled = false, message = "Biometric unlock disabled")
    }

    fun onExport(uri: Uri, backupPassword: String) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true)
            try {
                val count = exportVaultUseCase(uri, backupPassword.toCharArray())
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    message = "Exported $count credentials. Keep the backup password safe — " +
                        "without it the file cannot be restored."
                )
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    error = "Export failed: ${e.message}"
                )
            }
        }
    }

    fun onImport(uri: Uri, backupPassword: String, merge: Boolean) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true)
            try {
                val result = importVaultUseCase(uri, backupPassword.toCharArray(), merge)
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    message = buildString {
                        append("Imported ${result.imported} credentials")
                        if (result.skipped > 0) append(", ${result.skipped} already present")
                        if (result.replaced > 0) append(", ${result.replaced} replaced")
                        append(".")
                        if (result.formatVersion == VaultBackupFormat.VERSION_1) {
                            append(" This was an old-format backup — export a fresh one.")
                        }
                    }
                )
            } catch (e: WrongBackupPasswordException) {
                _uiState.value = _uiState.value.copy(isLoading = false, error = e.message)
            } catch (e: VaultBackupFormat.UnsupportedBackupException) {
                _uiState.value = _uiState.value.copy(isLoading = false, error = e.message)
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    error = "Import failed: ${e.message}"
                )
            }
        }
    }

    fun validateNewMasterPassword(
        newPassword: String,
        confirmation: String,
        currentPassword: String
    ): String? =
        (masterPasswordPolicy.validate(newPassword, confirmation, currentPassword)
            as? MasterPasswordPolicy.Result.Rejected)?.reason

    fun onChangeMasterPassword(currentPassword: String, newPassword: String) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true)
            try {
                val result = changeMasterPasswordUseCase(
                    currentPassword.toCharArray(),
                    newPassword.toCharArray()
                )
                if (result.succeeded) {
                    _uiState.value = _uiState.value.copy(
                        isLoading = false,
                        message = "Master password changed. Your entries were not re-encrypted " +
                            "and biometric unlock still works."
                    )
                } else {
                    _uiState.value = _uiState.value.copy(
                        isLoading = false,
                        error = result.failureReason ?: "Could not change the master password"
                    )
                }
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(isLoading = false, error = "Failed: ${e.message}")
            }
        }
    }

    fun getGoogleSignInIntent(): Intent = googleAuthManager.getSignInIntent()

    fun handleGoogleSignInResult(data: Intent?) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true)
            val (result, _) = googleAuthManager.handleSignInResult(data)
            when (result) {
                is GoogleSignInResult.Success -> {
                    // Signing in no longer switches sync on by itself. Enabling it is a
                    // separate, explicit choice — uploading a vault is not something to
                    // infer from a sign-in (#15).
                    refreshState()
                    _uiState.value = _uiState.value.copy(
                        isLoading = false,
                        message = "Signed in as ${result.user.email}. " +
                            "Turn on cloud sync below when you want to upload."
                    )
                }
                is GoogleSignInResult.Error -> _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    error = "Sign-in failed: ${result.message}"
                )
            }
        }
    }

    fun onEnableSync() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true)
            try {
                val summary = syncService.enable()
                refreshState()
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    message = "Cloud sync on. ${summary.describe()}"
                )
            } catch (e: Exception) {
                refreshState()
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    error = e.message ?: "Could not enable cloud sync"
                )
            }
        }
    }

    /**
     * @param deleteRemote also removes the account's vault from Firestore. There was no way
     *   to do that at all before (#16) — sign-out claimed sync was disabled and then went
     *   on syncing anonymously.
     */
    fun onDisableSync(deleteRemote: Boolean) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true)
            try {
                val deleted = syncService.disable(deleteRemote)
                refreshState()
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    message = if (deleteRemote) "Cloud sync off. Deleted $deleted entries from the cloud."
                    else "Cloud sync off. Your cloud copy was left in place."
                )
            } catch (e: Exception) {
                refreshState()
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    error = "Could not turn off cloud sync: ${e.message}"
                )
            }
        }
    }

    fun onSyncNow() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true)
            try {
                val summary = syncService.fullSync()
                _uiState.value = _uiState.value.copy(isLoading = false, message = summary.describe())
            } catch (e: Exception) {
                refreshState()
                _uiState.value = _uiState.value.copy(isLoading = false, error = e.message ?: "Sync failed")
            }
        }
    }

    fun onSignOutGoogle() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true)
            try {
                // Turn sync off first, while the account is still available to talk to.
                syncService.disable(deleteRemote = false)
                googleAuthManager.signOut()
                refreshState()
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    message = "Signed out. Cloud sync is off and this device is local only."
                )
            } catch (e: Exception) {
                refreshState()
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    error = "Sign-out failed: ${e.message}"
                )
            }
        }
    }

    /** Finding #34 — "Skip" used to be permanent with no way back. */
    fun onClearDismissedSavePrompts() {
        val cleared = dismissedPrefs.clearAll()
        _uiState.value = _uiState.value.copy(
            dismissedSavePrompts = 0,
            message = if (cleared == 0) "No dismissed prompts to clear"
            else "VaultGuard will offer to save on $cleared site(s) again"
        )
    }

    fun clearMessage() {
        _uiState.value = _uiState.value.copy(message = null, error = null)
    }

    /**
     * One GET to GitHub's public releases API, on demand only; nothing about the user or
     * the vault goes with it. Installing is the browser and the package installer: the
     * APK is signed with the same key, so Android upgrades in place.
     */
    fun onCheckForUpdates() {
        _uiState.value = _uiState.value.copy(isCheckingForUpdates = true, updateStatus = "Checking...", updateUrl = null)
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) { UpdateCheck(BuildConfig.VERSION_NAME).check() }
            _uiState.value = when (result) {
                is UpdateCheck.Result.UpToDate -> _uiState.value.copy(
                    isCheckingForUpdates = false, updateStatus = "Up to date (${result.current})."
                )
                is UpdateCheck.Result.Available -> _uiState.value.copy(
                    isCheckingForUpdates = false,
                    updateStatus = "VaultGuard ${result.release.version} is available.",
                    updateUrl = result.release.assets["VaultGuard-android.apk"] ?: result.release.pageUrl
                )
                is UpdateCheck.Result.Failed -> _uiState.value.copy(
                    isCheckingForUpdates = false, updateStatus = "Could not check: ${result.reason}"
                )
            }
        }
    }

    fun onLockVault() {
        masterPasswordManager.lockVault()
    }

}

private fun SyncResult.describe(): String = buildString {
    append("Pushed $pushed, pulled $pulled.")
    if (conflicts > 0) {
        append(" $conflicts entry(s) were edited in two places — both copies were kept.")
    }
    if (purged > 0) append(" Cleaned up $purged deleted entries.")
}
