package com.vaultguard.app.security

import com.vaultguard.app.data.local.AppPreferences
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class VaultAutoLock @Inject constructor(
    private val masterPasswordManager: MasterPasswordManager,
    private val appPreferences: AppPreferences
) : DefaultLifecycleObserver {

    private val scope = CoroutineScope(Dispatchers.Main)
    private var lockJob: Job? = null

    /**
     * Read from and written to storage, so the choice outlives the process. It used to be
     * a plain field that reset to five minutes on every launch (finding #25).
     */
    var timeoutMinutes: Int
        get() = appPreferences.autoLockMinutes
        set(value) { appPreferences.autoLockMinutes = value }

    fun register() {
        ProcessLifecycleOwner.get().lifecycle.addObserver(this)
    }

    override fun onStop(owner: LifecycleOwner) {
        if (masterPasswordManager.isVaultUnlocked) {
            lockJob = scope.launch {
                delay(timeoutMinutes * 60_000L)
                masterPasswordManager.lockVault()
            }
        }
    }

    override fun onStart(owner: LifecycleOwner) {
        lockJob?.cancel()
        lockJob = null
    }
}
