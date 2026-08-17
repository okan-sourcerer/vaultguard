package com.vaultguard.app.autofill

import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.vaultguard.app.domain.model.Credential
import com.vaultguard.app.domain.repository.CredentialRepository
import com.vaultguard.app.security.MasterPasswordManager
import com.vaultguard.app.ui.theme.VaultGuardTheme
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import java.util.UUID
import javax.inject.Inject

@AndroidEntryPoint
class AutofillSaveActivity : ComponentActivity() {

    companion object {
        const val EXTRA_USERNAME = "username"
        const val EXTRA_PASSWORD = "password"
        const val EXTRA_WEB_DOMAIN = "web_domain"
        const val EXTRA_PACKAGE_NAME = "app_package"
    }

    @Inject lateinit var credentialRepository: CredentialRepository
    @Inject lateinit var masterPasswordManager: MasterPasswordManager
    @Inject lateinit var dismissedPrefs: AutofillDismissedPrefs

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val username = intent.getStringExtra(EXTRA_USERNAME) ?: ""
        val password = intent.getStringExtra(EXTRA_PASSWORD) ?: ""
        val webDomain = intent.getStringExtra(EXTRA_WEB_DOMAIN) ?: ""
        val appPackage = intent.getStringExtra(EXTRA_PACKAGE_NAME) ?: ""

        val suggestedName = webDomain.ifEmpty {
            appPackage.split(".").lastOrNull() ?: "Unknown"
        }

        val resolvedAppName = if (appPackage.isNotEmpty()) {
            try {
                val appInfo = packageManager.getApplicationInfo(appPackage, 0)
                packageManager.getApplicationLabel(appInfo).toString()
            } catch (_: PackageManager.NameNotFoundException) {
                ""
            }
        } else ""

        if (!masterPasswordManager.isVaultUnlocked) {
            finish()
            return
        }

        setContent {
            VaultGuardTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    var siteName by remember { mutableStateOf(suggestedName) }
                    var appName by remember { mutableStateOf(resolvedAppName) }
                    var url by remember { mutableStateOf(if (webDomain.isNotEmpty()) "https://$webDomain" else "") }
                    val scope = rememberCoroutineScope()

                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(24.dp),
                        verticalArrangement = Arrangement.Center,
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text("Save to VaultGuard?", style = MaterialTheme.typography.headlineSmall)
                        Spacer(modifier = Modifier.height(16.dp))

                        OutlinedTextField(
                            value = siteName,
                            onValueChange = { siteName = it },
                            label = { Text("Site Name") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                        Spacer(modifier = Modifier.height(8.dp))

                        OutlinedTextField(
                            value = appName,
                            onValueChange = { appName = it },
                            label = { Text("App Name") },
                            placeholder = { Text("Display name shown in the list") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                        Spacer(modifier = Modifier.height(8.dp))

                        OutlinedTextField(
                            value = url,
                            onValueChange = { url = it },
                            label = { Text("URL") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                        Spacer(modifier = Modifier.height(8.dp))

                        var editableUsername by remember { mutableStateOf(username) }
                        OutlinedTextField(
                            value = editableUsername,
                            onValueChange = { editableUsername = it },
                            label = { Text("Username / Email") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                        Spacer(modifier = Modifier.height(8.dp))

                        OutlinedTextField(
                            value = "\u2022".repeat(password.length),
                            onValueChange = {},
                            label = { Text("Password") },
                            readOnly = true,
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )

                        Spacer(modifier = Modifier.height(24.dp))

                        Row(modifier = Modifier.fillMaxWidth()) {
                            OutlinedButton(
                                onClick = {
                                    dismissedPrefs.markDismissed(webDomain.ifEmpty { null }, appPackage.ifEmpty { null })
                                    finish()
                                },
                                modifier = Modifier.weight(1f)
                            ) {
                                Text("Skip")
                            }
                            Spacer(modifier = Modifier.width(16.dp))
                            Button(
                                onClick = {
                                    scope.launch {
                                        credentialRepository.save(
                                            Credential(
                                                id = UUID.randomUUID().toString(),
                                                siteName = siteName.trim(),
                                                appName = appName.trim(),
                                                url = url.trim(),
                                                username = editableUsername.trim(),
                                                password = password,
                                                linkedPackages = if (appPackage.isNotEmpty()) listOf(appPackage) else emptyList(),
                                                linkedDomains = if (webDomain.isNotEmpty()) listOf(webDomain) else emptyList()
                                            )
                                        )
                                        finish()
                                    }
                                },
                                modifier = Modifier.weight(1f)
                            ) {
                                Text("Save")
                            }
                        }
                    }
                }
            }
        }
    }
}
