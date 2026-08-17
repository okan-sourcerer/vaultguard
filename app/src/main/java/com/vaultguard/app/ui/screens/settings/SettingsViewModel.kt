package com.vaultguard.app.ui.screens.settings

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import android.view.autofill.AutofillManager
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.vaultguard.app.data.remote.FirebaseSyncService
import com.vaultguard.app.domain.repository.CredentialRepository
import com.vaultguard.app.domain.usecase.ChangeMasterPasswordUseCase
import com.vaultguard.app.domain.usecase.ExportVaultUseCase
import com.vaultguard.app.domain.usecase.ImportVaultUseCase
import com.vaultguard.app.domain.usecase.migration.ExportCleartextVaultUseCase
import com.vaultguard.app.security.BiometricAuthManager
import com.vaultguard.app.security.GoogleAuthManager
import com.vaultguard.app.security.GoogleSignInResult
import com.vaultguard.app.security.MasterPasswordManager
import com.vaultguard.app.security.VaultAutoLock
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

data class VaultStats(
    val totalEntries: Int = 0,
    val weakPasswords: Int = 0,
    val duplicatePasswords: Int = 0,
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
    val autofillSupported: Boolean = false,
    val isSignedInWithGoogle: Boolean = false,
    val googleEmail: String? = null,
    val googleDisplayName: String? = null,
    val vaultStats: VaultStats = VaultStats(),
    val isLoading: Boolean = false,
    val message: String? = null,
    val error: String? = null
)

@HiltViewModel
class SettingsViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val masterPasswordManager: MasterPasswordManager,
    private val biometricAuthManager: BiometricAuthManager,
    private val vaultAutoLock: VaultAutoLock,
    private val credentialRepository: CredentialRepository,
    private val exportVaultUseCase: ExportVaultUseCase,
    private val importVaultUseCase: ImportVaultUseCase,
    // TEMPORARY — cleartext migration aid, remove with the `migration` package.
    private val exportCleartextVaultUseCase: ExportCleartextVaultUseCase,
    private val changeMasterPasswordUseCase: ChangeMasterPasswordUseCase,
    private val googleAuthManager: GoogleAuthManager,
    private val syncService: FirebaseSyncService
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

                val weakPasswords = credentials.count { cred ->
                    cred.password.length < 8 ||
                        cred.password.all { it.isLetterOrDigit() } ||
                        !cred.password.any { it.isUpperCase() } ||
                        !cred.password.any { it.isDigit() }
                }

                val passwordGroups = credentials.groupBy { it.password }
                val duplicatePasswords = passwordGroups.values
                    .filter { it.size > 1 }
                    .sumOf { it.size }

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
                        duplicatePasswords = duplicatePasswords,
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
            biometricAvailable = bioAvailable,
            biometricEnabled = bioEnabled,
            autofillSupported = autofillManager?.isAutofillSupported == true,
            autofillEnabled = autofillManager?.hasEnabledAutofillServices() == true,
            isSignedInWithGoogle = googleAuthManager.isSignedInWithGoogle,
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

    /**
     * TEMPORARY — CLEARTEXT MIGRATION AID (finding #3). Remove with the `migration` package.
     */
    fun onExportCleartext(uri: Uri) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true)
            try {
                val count = exportCleartextVaultUseCase(uri)
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    message = "Exported $count credentials in cleartext. Import them elsewhere, " +
                        "then delete the file."
                )
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    error = "Cleartext export failed: ${e.message}"
                )
            }
        }
    }

    fun onExport(uri: Uri) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true)
            try {
                exportVaultUseCase(uri)
                _uiState.value = _uiState.value.copy(isLoading = false, message = "Vault exported successfully")
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(isLoading = false, error = "Export failed: ${e.message}")
            }
        }
    }

    fun onImport(uri: Uri, masterPassword: String, merge: Boolean) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true)
            try {
                importVaultUseCase(uri, masterPassword.toCharArray(), merge)
                _uiState.value = _uiState.value.copy(isLoading = false, message = "Vault imported successfully")
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(isLoading = false, error = "Import failed: ${e.message}")
            }
        }
    }

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
            val (result, oldAnonymousUid) = googleAuthManager.handleSignInResult(data)
            when (result) {
                is GoogleSignInResult.Success -> {
                    // If we had to sign in separately (link failed), migrate any anonymous data
                    if (oldAnonymousUid != null) {
                        try {
                            syncService.migrateFromAnonymousUser(oldAnonymousUid)
                        } catch (e: Exception) {
                            Log.e("SettingsVM", "Migration failed", e)
                        }
                    }

                    var adoptedRemoteVault = false
                    try {
                        val remoteConfig = syncService.pullVaultConfig()
                        if (remoteConfig != null &&
                            !remoteConfig.salt.contentEquals(masterPasswordManager.getSalt())
                        ) {
                            // A vault from another device exists — adopt its salt and
                            // verification data so we derive the same key on unlock.
                            masterPasswordManager.adoptRemoteSetup(
                                remoteConfig.salt,
                                remoteConfig.verificationCiphertext,
                                remoteConfig.verificationIv
                            )
                            adoptedRemoteVault = true
                        } else if (remoteConfig == null) {
                            // First device — publish our vault config
                            val (ciphertext, iv) = masterPasswordManager.getVerificationData()
                            syncService.pushVaultConfig(masterPasswordManager.getSalt(), ciphertext, iv)
                        }
                        syncService.fullSync()
                    } catch (_: Exception) { }

                    if (adoptedRemoteVault) {
                        // Adopting a different salt makes the biometric wrapper stale for
                        // the same reason a password change does (finding #6) — it holds a
                        // key derived from the old salt, which no longer opens this vault.
                        biometricAuthManager.disableBiometric()
                    }

                    refreshState()
                    if (adoptedRemoteVault) {
                        // Lock so the user re-unlocks with the correct key (Device 1's salt)
                        masterPasswordManager.lockVault(
                            "Existing vault found — please re-unlock to sync your passwords."
                        )
                        _uiState.value = _uiState.value.copy(isLoading = false)
                    } else {
                        _uiState.value = _uiState.value.copy(
                            isLoading = false,
                            message = "Signed in as ${result.user.email}. Cloud sync enabled!"
                        )
                    }
                }
                is GoogleSignInResult.Error -> {
                    _uiState.value = _uiState.value.copy(
                        isLoading = false,
                        error = "Sign-in failed: ${result.message}"
                    )
                }
            }
        }
    }

    fun onSignOutGoogle() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true)
            try {
                googleAuthManager.signOutAndGoAnonymous()
                refreshState()
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    message = "Signed out. Cloud sync disabled."
                )
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    error = "Sign-out failed: ${e.message}"
                )
            }
        }
    }

    fun clearMessage() {
        _uiState.value = _uiState.value.copy(message = null, error = null)
    }

    fun onLockVault() {
        masterPasswordManager.lockVault()
    }
}
