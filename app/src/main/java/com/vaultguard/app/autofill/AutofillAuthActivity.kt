package com.vaultguard.app.autofill

import android.content.Intent
import android.os.Bundle
import android.service.autofill.Dataset
import android.service.autofill.FillResponse
import android.view.WindowManager
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.vaultguard.app.R
import com.vaultguard.app.data.local.db.dao.CredentialDao
import com.vaultguard.app.data.repository.CredentialPayloadCodec
import com.vaultguard.app.domain.model.Credential
import com.vaultguard.app.domain.usecase.UnlockVaultUseCase
import com.vaultguard.app.security.CryptoManager
import com.vaultguard.app.security.EncryptedData
import com.vaultguard.app.security.MasterPasswordManager
import com.vaultguard.app.ui.theme.VaultGuardTheme
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber
import javax.inject.Inject

/**
 * Unlocks the vault in response to an autofill request, then hands back **every** matching
 * credential for the user to choose from.
 *
 * It previously returned `credentials.first()` as a single dataset with no choice offered,
 * using matching rules looser than the service's own (finding #11). The service now
 * authenticates the whole `FillResponse`, so this can answer with a response rather than
 * one dataset, and matching goes through the shared [CredentialMatcher].
 */
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
    @Inject lateinit var unlockVaultUseCase: UnlockVaultUseCase

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // This screen takes the master password. Only MainActivity used to set this, so
        // the two autofill activities were screenshotable and appeared in the recents
        // thumbnail (finding #13).
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)

        val webDomain = intent.getStringExtra(EXTRA_WEB_DOMAIN)
        val appPackage = intent.getStringExtra(EXTRA_PACKAGE_NAME)
        val usernameIds = intent.getParcelableArrayListExtra<AutofillId>(EXTRA_USERNAME_IDS) ?: emptyList()
        val passwordIds = intent.getParcelableArrayListExtra<AutofillId>(EXTRA_PASSWORD_IDS) ?: emptyList()

        setContent {
            VaultGuardTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    var password by remember { mutableStateOf("") }
                    var error by remember { mutableStateOf<String?>(null) }
                    var isBusy by remember { mutableStateOf(false) }
                    val scope = rememberCoroutineScope()

                    // Argon2id blocks for hundreds of milliseconds; running it in the
                    // click handler froze the autofill window (finding #17).
                    fun submit() {
                        if (isBusy || password.isEmpty()) return
                        isBusy = true
                        scope.launch {
                            error = unlockAndRespond(password, webDomain, appPackage, usernameIds, passwordIds)
                            isBusy = false
                        }
                    }

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
                            enabled = !isBusy,
                            visualTransformation = PasswordVisualTransformation(),
                            keyboardOptions = KeyboardOptions(
                                keyboardType = KeyboardType.Password,
                                imeAction = ImeAction.Done,
                                autoCorrectEnabled = false
                            ),
                            keyboardActions = KeyboardActions(onDone = { submit() }),
                            modifier = Modifier.fillMaxWidth()
                        )

                        error?.let {
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(it, color = MaterialTheme.colorScheme.error)
                        }

                        Spacer(modifier = Modifier.height(16.dp))

                        Button(
                            onClick = { submit() },
                            enabled = !isBusy && password.isNotEmpty(),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(if (isBusy) "Unlocking…" else "Unlock & Fill")
                        }
                    }
                }
            }
        }
    }

    /**
     * @return an error message to display, or null once the response has been returned.
     *
     * The screen already rendered an `error` slot but nothing ever assigned to it, so a
     * wrong master password made the button appear inert (finding #31).
     */
    private suspend fun unlockAndRespond(
        password: String,
        webDomain: String?,
        appPackage: String?,
        usernameIds: List<AutofillId>,
        passwordIds: List<AutofillId>
    ): String? {
        when (val result = unlockVaultUseCase(password.toCharArray())) {
            UnlockVaultUseCase.Result.Success -> Unit
            is UnlockVaultUseCase.Result.VaultUnreadable -> return result.detail
            UnlockVaultUseCase.Result.WrongPassword -> return "Incorrect master password"
        }

        val credentials = withContext(Dispatchers.IO) {
            findMatchingCredentials(webDomain, appPackage)
        }
        if (credentials.isEmpty()) {
            // Unlocked, but nothing matches. Say so rather than dismissing silently, which
            // looked identical to a failed unlock.
            return "Vault unlocked, but no saved credential matches this app or site."
        }

        val responseBuilder = FillResponse.Builder()
        var added = 0
        for (credential in credentials) {
            val presentation = RemoteViews(packageName, R.layout.autofill_item).apply {
                setTextViewText(
                    R.id.autofill_text,
                    "${credential.displayName} — ${credential.username}"
                )
            }
            val dataset = Dataset.Builder(presentation)
            var hasValue = false
            for (id in usernameIds) {
                dataset.setValue(id, AutofillValue.forText(credential.username))
                hasValue = true
            }
            for (id in passwordIds) {
                dataset.setValue(id, AutofillValue.forText(credential.password))
                hasValue = true
            }
            if (hasValue) {
                responseBuilder.addDataset(dataset.build())
                added++
            }
        }

        if (added == 0) return "Nothing to fill in this form."

        setResult(
            RESULT_OK,
            Intent().putExtra(
                AutofillManager.EXTRA_AUTHENTICATION_RESULT,
                responseBuilder.build()
            )
        )
        finish()
        return null
    }

    /** Goes through the shared matcher — the same rules the unlocked path uses. */
    private fun findMatchingCredentials(webDomain: String?, packageName: String?): List<Credential> {
        val key = masterPasswordManager.getSessionKey()
        val decrypted = credentialDao.getAllBlocking()
            .filter { !it.isDeleted }
            .mapNotNull { entity ->
                try {
                    val plaintext = cryptoManager.decrypt(
                        EncryptedData(entity.encryptedPayload, entity.iv), key
                    )
                    val json = String(plaintext, Charsets.UTF_8)
                    plaintext.fill(0)
                    CredentialPayloadCodec.decode(
                        json, entity.id, entity.createdAt, entity.updatedAt, entity.passwordChangedAt
                    )
                } catch (e: Exception) {
                    Timber.e(e, "Autofill could not decrypt credential %s", entity.id)
                    null
                }
            }
        return CredentialMatcher.match(decrypted, webDomain, packageName)
    }
}
