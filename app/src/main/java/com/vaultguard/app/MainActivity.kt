package com.vaultguard.app

import android.app.ActivityManager
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.fragment.app.FragmentActivity
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.navigation.compose.rememberNavController
import com.vaultguard.app.data.local.db.VaultDatabaseStatusHolder
import com.vaultguard.app.security.MasterPasswordManager
import com.vaultguard.app.ui.navigation.NavGraph
import com.vaultguard.app.ui.navigation.Screen
import com.vaultguard.app.ui.theme.VaultGuardTheme
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import androidx.core.graphics.toColorInt

@AndroidEntryPoint
class MainActivity : FragmentActivity() {

    @Inject
    lateinit var masterPasswordManager: MasterPasswordManager

    @Inject
    lateinit var vaultDatabaseStatusHolder: VaultDatabaseStatusHolder

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        window.decorView.setBackgroundColor("#1C1B1F".toColorInt())
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            setTaskDescription(
                ActivityManager.TaskDescription.Builder()
                    .setBackgroundColor("#1C1B1F".toColorInt())
                    .build()
            )
        }
        setContent {
            VaultGuardTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    val navController = rememberNavController()
                    val startDestination = when {
                        // Takes precedence over everything: the master password lives in
                        // preferences, so setup can look complete while the vault itself
                        // is unopenable. Reaching the unlock screen in that state would
                        // present an empty vault instead of an explanation (findings #1, #40).
                        vaultDatabaseStatusHolder.isUnreadable -> Screen.Recovery.route
                        !masterPasswordManager.isSetupComplete -> Screen.Setup.route
                        !masterPasswordManager.isVaultUnlocked -> Screen.Unlock.route
                        else -> Screen.Vault.route
                    }
                    // On startup: if saved nav state restored a protected screen but vault is locked, redirect.
                    // This handles process-death restarts where _vaultLocked resets to false but the
                    // session key is gone.
                    LaunchedEffect(Unit) {
                        if (!vaultDatabaseStatusHolder.isUnreadable &&
                            masterPasswordManager.isSetupComplete &&
                            !masterPasswordManager.isVaultUnlocked
                        ) {
                            navController.navigate(Screen.Unlock.route) {
                                popUpTo(0) { inclusive = true }
                            }
                        }
                    }

                    // Navigate to unlock screen when vault is locked at runtime (e.g. auto-lock timeout)
                    val vaultLocked by masterPasswordManager.vaultLocked.collectAsState()
                    LaunchedEffect(vaultLocked) {
                        if (vaultLocked && !vaultDatabaseStatusHolder.isUnreadable) {
                            masterPasswordManager.consumeLockEvent()
                            navController.navigate(Screen.Unlock.route) {
                                popUpTo(0) { inclusive = true }
                            }
                        }
                    }

                    NavGraph(
                        navController = navController,
                        startDestination = startDestination
                    )
                }
            }
        }
    }
}
