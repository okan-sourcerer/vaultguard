package com.vaultguard.app.autofill

import android.content.Intent
import android.os.Bundle
import android.service.autofill.Dataset
import android.view.autofill.AutofillId
import android.view.autofill.AutofillManager
import android.view.autofill.AutofillValue
import android.widget.RemoteViews
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.vaultguard.app.R
import com.vaultguard.app.domain.model.Credential
import com.vaultguard.app.security.CryptoManager
import com.vaultguard.app.security.EncryptedData
import com.vaultguard.app.security.MasterPasswordManager
import com.vaultguard.app.data.local.db.dao.CredentialDao
import com.vaultguard.app.ui.theme.VaultGuardTheme
import dagger.hilt.android.AndroidEntryPoint
import org.json.JSONObject
import javax.inject.Inject

@AndroidEntryPoint
class AutofillAuthActivity : ComponentActivity() {

    companion object {
        const val EXTRA_WEB_DOMAIN = "web_domain"
        const val EXTRA_PACKAGE_NAME = "app_package"
        const val EXTRA_USERNAME_IDS = "username_ids"
        const val EXTRA_PASSWORD_IDS = "password_ids"
    }

    @Inject lateinit var masterPasswordManager: MasterPasswordManager
    @Inject lateinit var cryptoManager: CryptoManager
    @Inject lateinit var credentialDao: CredentialDao

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val webDomain = intent.getStringExtra(EXTRA_WEB_DOMAIN)
        val appPackage = intent.getStringExtra(EXTRA_PACKAGE_NAME)
        val usernameIds = intent.getParcelableArrayListExtra<AutofillId>(EXTRA_USERNAME_IDS) ?: emptyList()
        val passwordIds = intent.getParcelableArrayListExtra<AutofillId>(EXTRA_PASSWORD_IDS) ?: emptyList()

        setContent {
            VaultGuardTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    var password by remember { mutableStateOf("") }
                    var error by remember { mutableStateOf<String?>(null) }

                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(24.dp),
                        verticalArrangement = Arrangement.Center,
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text("Unlock VaultGuard", style = MaterialTheme.typography.headlineSmall)
                        Spacer(modifier = Modifier.height(16.dp))

                        OutlinedTextField(
                            value = password,
                            onValueChange = { password = it; error = null },
                            label = { Text("Master Password") },
                            singleLine = true,
                            visualTransformation = PasswordVisualTransformation(),
                            keyboardOptions = KeyboardOptions(
                                keyboardType = KeyboardType.Password,
                                imeAction = ImeAction.Done,
                                autoCorrectEnabled = false
                            ),
                            keyboardActions = KeyboardActions(onDone = {
                                error = tryUnlock(password, webDomain, appPackage, usernameIds, passwordIds)
                            }),
                            modifier = Modifier.fillMaxWidth()
                        )

                        if (error != null) {
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(error!!, color = MaterialTheme.colorScheme.error)
                        }

                        Spacer(modifier = Modifier.height(16.dp))

                        Button(
                            onClick = {
                                error = tryUnlock(password, webDomain, appPackage, usernameIds, passwordIds)
                            },
                            enabled = password.isNotEmpty(),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("Unlock & Fill")
                        }
                    }
                }
            }
        }
    }

    /**
     * @return an error message to display, or null on success.
     *
     * The screen already rendered an `error` slot but nothing ever assigned to it, so a
     * wrong master password made the button appear inert (finding #31).
     */
    private fun tryUnlock(
        password: String,
        webDomain: String?,
        appPackage: String?,
        usernameIds: List<AutofillId>,
        passwordIds: List<AutofillId>
    ): String? {
        if (password.isEmpty()) return "Enter your master password"

        val success = masterPasswordManager.unlock(password.toCharArray())
        if (!success) {
            return "Incorrect master password"
        }

        val credentials = findMatchingCredentials(webDomain, appPackage)
        if (credentials.isEmpty()) {
            // Unlocked, but nothing matches this app or site. Say so rather than
            // dismissing silently, which looked identical to a failed unlock.
            return "Vault unlocked, but no saved credential matches this app or site."
        }

        // Return the first match as the autofill response
        val credential = credentials.first()
        val replyIntent = Intent()

        val presentation = RemoteViews(packageName, R.layout.autofill_item).apply {
            setTextViewText(R.id.autofill_text, "${credential.siteName} — ${credential.username}")
        }

        val datasetBuilder = Dataset.Builder(presentation)
        for (id in usernameIds) {
            datasetBuilder.setValue(id, AutofillValue.forText(credential.username))
        }
        for (id in passwordIds) {
            datasetBuilder.setValue(id, AutofillValue.forText(credential.password))
        }

        replyIntent.putExtra(AutofillManager.EXTRA_AUTHENTICATION_RESULT, datasetBuilder.build())
        setResult(RESULT_OK, replyIntent)
        finish()
        return null
    }

    private fun findMatchingCredentials(webDomain: String?, packageName: String?): List<Credential> {
        val key = masterPasswordManager.getSessionKey()
        return credentialDao.getAllBlocking()
            .filter { !it.isDeleted }
            .mapNotNull { entity ->
                try {
                    val decrypted = cryptoManager.decrypt(
                        EncryptedData(entity.encryptedPayload, entity.iv), key
                    )
                    val json = String(decrypted, Charsets.UTF_8)
                    decrypted.fill(0)
                    val obj = JSONObject(json)
                    Credential(
                        id = entity.id,
                        siteName = obj.optString("siteName", ""),
                        url = obj.optString("url", ""),
                        username = obj.optString("username", ""),
                        password = obj.optString("password", "")
                    )
                } catch (_: Exception) {
                    null
                }
            }
            .filter { cred ->
                val domain = webDomain?.lowercase() ?: ""
                val pkg = packageName?.lowercase() ?: ""
                val credUrl = cred.url.lowercase()
                    .removePrefix("https://").removePrefix("http://").removeSuffix("/")
                val credSite = cred.siteName.lowercase()

                (domain.isNotEmpty() && (credUrl.contains(domain) || credSite.contains(domain.split(".").first()))) ||
                (pkg.isNotEmpty() && credSite.split(" ").any { it.length > 2 && pkg.contains(it) })
            }
    }
}
