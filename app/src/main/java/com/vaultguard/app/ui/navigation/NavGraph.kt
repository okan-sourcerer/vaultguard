package com.vaultguard.app.ui.navigation

import androidx.compose.runtime.Composable
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import com.vaultguard.app.ui.screens.addEdit.AddEditScreen
import com.vaultguard.app.ui.screens.detail.CredentialDetailScreen
import com.vaultguard.app.ui.screens.generator.PasswordGeneratorScreen
import com.vaultguard.app.ui.screens.recovery.VaultRecoveryScreen
import com.vaultguard.app.ui.screens.settings.SettingsScreen
import com.vaultguard.app.ui.screens.setup.SetupScreen
import com.vaultguard.app.ui.screens.unlock.UnlockScreen
import com.vaultguard.app.ui.screens.vault.VaultScreen

sealed class Screen(val route: String) {
    data object Setup : Screen("setup")
    data object Unlock : Screen("unlock")
    data object Vault : Screen("vault")
    data object AddEdit : Screen("add_edit?id={id}") {
        fun createRoute(id: String? = null) = if (id != null) "add_edit?id=$id" else "add_edit"
    }
    data object Detail : Screen("detail/{id}") {
        fun createRoute(id: String) = "detail/$id"
    }
    data object Generator : Screen("generator")
    data object Settings : Screen("settings")

    /** Shown when vault.db exists but cannot be decrypted (finding #1). */
    data object Recovery : Screen("recovery")
}

@Composable
fun NavGraph(
    navController: NavHostController,
    startDestination: String
) {
    NavHost(navController = navController, startDestination = startDestination) {
        composable(Screen.Recovery.route) {
            VaultRecoveryScreen()
        }

        composable(Screen.Setup.route) {
            SetupScreen(
                onSetupComplete = {
                    navController.navigate(Screen.Vault.route) {
                        popUpTo(Screen.Setup.route) { inclusive = true }
                    }
                }
            )
        }

        composable(Screen.Unlock.route) {
            UnlockScreen(
                onUnlockSuccess = {
                    navController.navigate(Screen.Vault.route) {
                        popUpTo(Screen.Unlock.route) { inclusive = true }
                    }
                },
            )
        }

        composable(Screen.Vault.route) {
            VaultScreen(
                onCredentialClick = { id ->
                    navController.navigate(Screen.Detail.createRoute(id))
                },
                onAddClick = {
                    navController.navigate(Screen.AddEdit.createRoute())
                },
                onGeneratorClick = {
                    navController.navigate(Screen.Generator.route)
                },
                onSettingsClick = {
                    navController.navigate(Screen.Settings.route)
                }
            )
        }

        composable(
            route = Screen.AddEdit.route,
            arguments = listOf(navArgument("id") { type = NavType.StringType; nullable = true; defaultValue = null })
        ) {
            AddEditScreen(
                onNavigateBack = { navController.popBackStack() },
                onGeneratePassword = { navController.navigate(Screen.Generator.route) }
            )
        }

        composable(
            route = Screen.Detail.route,
            arguments = listOf(navArgument("id") { type = NavType.StringType })
        ) {
            CredentialDetailScreen(
                onNavigateBack = { navController.popBackStack() },
                onEditClick = { id ->
                    navController.navigate(Screen.AddEdit.createRoute(id))
                }
            )
        }

        composable(Screen.Generator.route) {
            PasswordGeneratorScreen(
                onNavigateBack = { navController.popBackStack() }
            )
        }

        composable(Screen.Settings.route) {
            SettingsScreen(
                onNavigateBack = { navController.popBackStack() },
                onLockVault = {
                    navController.navigate(Screen.Unlock.route) {
                        popUpTo(0) { inclusive = true }
                    }
                }
            )
        }
    }
}
