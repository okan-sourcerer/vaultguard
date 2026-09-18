package com.vaultguard.app.ui.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import com.vaultguard.app.ui.screens.addEdit.AddEditScreen
import com.vaultguard.app.ui.screens.detail.CredentialDetailScreen
import com.vaultguard.app.ui.screens.feedback.FeedbackScreen
import com.vaultguard.app.ui.screens.generator.PasswordGeneratorScreen
import com.vaultguard.app.ui.screens.recovery.VaultRecoveryScreen
import com.vaultguard.app.ui.screens.settings.SettingsScreen
import com.vaultguard.app.ui.screens.setup.SetupScreen
import com.vaultguard.app.ui.screens.unlock.UnlockScreen
import com.vaultguard.app.ui.screens.vault.VaultScreen

sealed class Screen(val route: String) {
    data object Setup : Screen("setup")
    data object Unlock : Screen("unlock")
    data object Vault : Screen("vault") {
        /**
         * Id of an entry just deleted from the detail screen, handed back so the list can
         * offer to undo it (#59). Same mechanism as [Generator.RESULT_KEY].
         */
        const val DELETED_KEY = "deleted_credential_id"
    }
    data object AddEdit : Screen("add_edit?id={id}") {
        fun createRoute(id: String? = null) = if (id != null) "add_edit?id=$id" else "add_edit"
    }
    data object Detail : Screen("detail/{id}") {
        fun createRoute(id: String) = "detail/$id"
    }
    data object Generator : Screen("generator?forResult={forResult}") {
        /**
         * [forResult] makes the generator offer a "Use this password" button that hands
         * the result back to Add/Edit. Without it the screen is just a standalone tool.
         */
        fun createRoute(forResult: Boolean = false) = "generator?forResult=$forResult"

        /** Key under which the chosen password is handed to the previous screen. */
        const val RESULT_KEY = "generated_password"
    }
    data object Settings : Screen("settings")
    data object Feedback : Screen("feedback")

    /** Shown when vault.db exists but cannot be decrypted (finding #1). */
    data object Recovery : Screen("recovery")
}

/**
 * @param openSettingsOnVaultEntry pushes Settings the first time the vault list is reached,
 *   for the autofill settings entry point (#53). Hooking it to the vault rather than to a
 *   start destination means it works whether the app opened unlocked or had to go through
 *   the unlock screen first, and it leaves the vault beneath Settings so Back behaves.
 */
@Composable
fun NavGraph(
    navController: NavHostController,
    startDestination: String,
    openSettingsOnVaultEntry: Boolean = false,
    onOpenSettingsHandled: () -> Unit = {}
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

        composable(Screen.Vault.route) { entry ->
            LaunchedEffect(Unit) {
                if (openSettingsOnVaultEntry) {
                    onOpenSettingsHandled()
                    navController.navigate(Screen.Settings.route)
                }
            }

            VaultScreen(
                deletedCredentialId = entry.savedStateHandle
                    .getStateFlow<String?>(Screen.Vault.DELETED_KEY, null),
                onDeletionHandled = {
                    entry.savedStateHandle[Screen.Vault.DELETED_KEY] = null
                },
                onCredentialClick = { id ->
                    navController.navigate(Screen.Detail.createRoute(id))
                },
                onAddClick = {
                    navController.navigate(Screen.AddEdit.createRoute())
                },
                onGeneratorClick = {
                    navController.navigate(Screen.Generator.createRoute())
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
                onGeneratePassword = {
                    navController.navigate(Screen.Generator.createRoute(forResult = true))
                },
                // Compose Navigation hands results back through the *previous* entry's
                // SavedStateHandle. Reading it here keeps that plumbing out of the screen.
                generatedPassword = it.savedStateHandle
                    .getStateFlow<String?>(Screen.Generator.RESULT_KEY, null),
                onGeneratedPasswordConsumed = {
                    it.savedStateHandle[Screen.Generator.RESULT_KEY] = null
                }
            )
        }

        composable(
            route = Screen.Detail.route,
            arguments = listOf(navArgument("id") { type = NavType.StringType })
        ) {
            CredentialDetailScreen(
                onNavigateBack = { navController.popBackStack() },
                onDeleted = { id ->
                    // Hand the id to the list before leaving, so it can offer the undo (#59).
                    navController.previousBackStackEntry
                        ?.savedStateHandle?.set(Screen.Vault.DELETED_KEY, id)
                    navController.popBackStack()
                },
                onEditClick = { id ->
                    navController.navigate(Screen.AddEdit.createRoute(id))
                }
            )
        }

        composable(
            route = Screen.Generator.route,
            arguments = listOf(
                navArgument("forResult") { type = NavType.BoolType; defaultValue = false }
            )
        ) { entry ->
            PasswordGeneratorScreen(
                forResult = entry.arguments?.getBoolean("forResult") == true,
                onNavigateBack = { navController.popBackStack() },
                onUsePassword = { password ->
                    navController.previousBackStackEntry
                        ?.savedStateHandle?.set(Screen.Generator.RESULT_KEY, password)
                    navController.popBackStack()
                }
            )
        }

        composable(Screen.Settings.route) {
            SettingsScreen(
                onNavigateBack = { navController.popBackStack() },
                onNavigateToFeedback = { navController.navigate(Screen.Feedback.route) },
                onLockVault = {
                    navController.navigate(Screen.Unlock.route) {
                        popUpTo(0) { inclusive = true }
                    }
                }
            )
        }

        composable(Screen.Feedback.route) {
            FeedbackScreen(onNavigateBack = { navController.popBackStack() })
        }
    }
}
