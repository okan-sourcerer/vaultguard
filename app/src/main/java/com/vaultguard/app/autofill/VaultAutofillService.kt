package com.vaultguard.app.autofill

import android.app.PendingIntent
import android.app.assist.AssistStructure
import android.content.Intent
import android.os.CancellationSignal
import android.service.autofill.AutofillService
import android.service.autofill.Dataset
import android.service.autofill.FillCallback
import android.service.autofill.FillRequest
import android.service.autofill.FillResponse
import android.service.autofill.SaveCallback
import android.service.autofill.SaveInfo
import android.service.autofill.SaveRequest
import android.view.autofill.AutofillValue
import android.widget.RemoteViews
import com.vaultguard.app.R
import com.vaultguard.app.data.local.db.dao.CredentialDao
import com.vaultguard.app.data.repository.CredentialPayloadCodec
import com.vaultguard.app.domain.model.Credential
import com.vaultguard.app.security.CryptoManager
import com.vaultguard.app.security.EncryptedData
import com.vaultguard.app.security.MasterPasswordManager
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject

@AndroidEntryPoint
class VaultAutofillService : AutofillService() {

    @Inject lateinit var masterPasswordManager: MasterPasswordManager
    @Inject lateinit var cryptoManager: CryptoManager
    @Inject lateinit var credentialDao: CredentialDao
    @Inject lateinit var dismissedPrefs: AutofillDismissedPrefs

    private val scope = CoroutineScope(Dispatchers.IO)

    /**
     * Every PendingIntent used to be built with request code 0 and FLAG_CANCEL_CURRENT, so
     * a second fill request cancelled the first one's authentication intent — routine on a
     * page with more than one form.
     */
    private val requestCodes = java.util.concurrent.atomic.AtomicInteger(0)

    private fun nextRequestCode() = requestCodes.incrementAndGet()

    override fun onFillRequest(
        request: FillRequest,
        cancellationSignal: CancellationSignal,
        callback: FillCallback
    ) {
        val structure = request.fillContexts.lastOrNull()?.structure ?: run {
            callback.onSuccess(null)
            return
        }

        val parsed = StructureParser(structure).parse()

        // Only respond if we found username or password fields
        if (parsed.usernameFields.isEmpty() && parsed.passwordFields.isEmpty()) {
            callback.onSuccess(null)
            return
        }

        // If the vault is locked, authenticate the whole response rather than a single
        // placeholder dataset. Dataset-level auth forced the unlock activity to answer
        // with exactly one credential, which is why it picked the first match with no
        // user choice (finding #11). Response-level auth lets it return every match.
        if (!masterPasswordManager.isVaultUnlocked) {
            val authIntent = Intent(this, AutofillAuthActivity::class.java).apply {
                putExtra(AutofillAuthActivity.EXTRA_WEB_DOMAIN, parsed.webDomain)
                putExtra(AutofillAuthActivity.EXTRA_PACKAGE_NAME, parsed.packageName)
                putParcelableArrayListExtra(
                    AutofillAuthActivity.EXTRA_USERNAME_IDS,
                    ArrayList(parsed.usernameFields)
                )
                putParcelableArrayListExtra(
                    AutofillAuthActivity.EXTRA_PASSWORD_IDS,
                    ArrayList(parsed.passwordFields)
                )
            }

            val pendingIntent = PendingIntent.getActivity(
                this, nextRequestCode(), authIntent,
                PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )

            val presentation = RemoteViews(packageName, R.layout.autofill_item).apply {
                setTextViewText(R.id.autofill_text, "Unlock VaultGuard")
            }

            val responseBuilder = FillResponse.Builder()
            responseBuilder.setAuthentication(
                (parsed.usernameFields + parsed.passwordFields).toTypedArray(),
                pendingIntent.intentSender,
                presentation
            )
            addSaveInfo(responseBuilder, parsed)
            callback.onSuccess(responseBuilder.build())
            return
        }

        // Vault is unlocked — find matching credentials
        scope.launch {
            try {
                val credentials = findMatchingCredentials(parsed.webDomain, parsed.packageName)
                if (credentials.isEmpty()) {
                    callback.onSuccess(buildEmptyResponse(parsed))
                    return@launch
                }

                val responseBuilder = FillResponse.Builder()
                for (credential in credentials) {
                    val dataset = buildDataset(credential, parsed)
                    if (dataset != null) {
                        responseBuilder.addDataset(dataset)
                    }
                }
                addSaveInfo(responseBuilder, parsed)
                callback.onSuccess(responseBuilder.build())
            } catch (_: Exception) {
                callback.onSuccess(null)
            }
        }
    }

    override fun onSaveRequest(request: SaveRequest, callback: SaveCallback) {
        val structure = request.fillContexts.lastOrNull()?.structure ?: run {
            callback.onSuccess()
            return
        }

        val parsed = StructureParser(structure).parse()

        // Extract filled values
        var username = ""
        var password = ""

        for (i in 0 until structure.windowNodeCount) {
            val windowNode = structure.getWindowNodeAt(i)
            extractValues(windowNode.rootViewNode, parsed, { username = it }, { password = it })
        }

        if (password.isEmpty()) {
            callback.onSuccess()
            return
        }

        // If the user has already dismissed the save prompt for this app/site, do not show it again
        if (dismissedPrefs.isDismissed(parsed.webDomain, parsed.packageName)) {
            callback.onSuccess()
            return
        }

        // The duplicate check decrypts the whole vault, which used to happen inside
        // runBlocking on the main thread — an ANR that scaled with vault size (finding #18).
        // SaveCallback may be answered asynchronously, so do the work off-thread and
        // respond when it is done.
        scope.launch {
            try {
                val isDuplicate = masterPasswordManager.isVaultUnlocked &&
                    findMatchingCredentials(parsed.webDomain, parsed.packageName)
                        .any { it.username == username }

                if (isDuplicate) {
                    callback.onSuccess()
                    return@launch
                }

                val saveIntent = Intent(this@VaultAutofillService, AutofillSaveActivity::class.java).apply {
                    putExtra(AutofillSaveActivity.EXTRA_USERNAME, username)
                    putExtra(AutofillSaveActivity.EXTRA_PASSWORD, password)
                    putExtra(AutofillSaveActivity.EXTRA_WEB_DOMAIN, parsed.webDomain)
                    putExtra(AutofillSaveActivity.EXTRA_PACKAGE_NAME, parsed.packageName)
                }

                // Hand the system an IntentSender and let *it* launch the dialog.
                //
                // This used to call startActivity() directly. A service in the background
                // cannot start an activity on Android 10 and above, so the save prompt was
                // silently blocked — which is why saving from autofill appeared to do
                // nothing at all (finding #47). SaveCallback.onSuccess(IntentSender) exists
                // for exactly this and has been available since API 28; minSdk is 28.
                val pendingIntent = PendingIntent.getActivity(
                    this@VaultAutofillService,
                    nextRequestCode(),
                    saveIntent,
                    PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                )
                callback.onSuccess(pendingIntent.intentSender)
            } catch (e: Exception) {
                Timber.e(e, "Autofill save handling failed")
                // Still answer, or the system waits on a callback that never comes.
                callback.onSuccess()
            }
        }
    }

    /**
     * Decrypts the vault and asks [CredentialMatcher] which entries may be offered.
     *
     * Matching used to live here *and* in AutofillAuthActivity, in two implementations
     * that had drifted apart — with the looser one guarding the locked-vault path
     * (finding #11). There is now one.
     */
    private fun findMatchingCredentials(webDomain: String?, packageName: String?): List<Credential> {
        if (!masterPasswordManager.isVaultUnlocked) return emptyList()

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

    private fun buildDataset(credential: Credential, parsed: ParsedStructure): Dataset? {
        val presentation = RemoteViews(packageName, R.layout.autofill_item).apply {
            setTextViewText(R.id.autofill_text, "${credential.displayName} — ${credential.username}")
        }

        val builder = Dataset.Builder(presentation)
        var hasValue = false

        for (id in parsed.usernameFields) {
            builder.setValue(id, AutofillValue.forText(credential.username))
            hasValue = true
        }
        for (id in parsed.passwordFields) {
            builder.setValue(id, AutofillValue.forText(credential.password))
            hasValue = true
        }

        return if (hasValue) builder.build() else null
    }

    private fun buildEmptyResponse(parsed: ParsedStructure): FillResponse? {
        val builder = FillResponse.Builder()
        addSaveInfo(builder, parsed)
        return try {
            builder.build()
        } catch (_: Exception) {
            null
        }
    }

    private fun addSaveInfo(builder: FillResponse.Builder, parsed: ParsedStructure) {
        val allIds = parsed.usernameFields + parsed.passwordFields
        if (allIds.isEmpty()) return

        val saveInfoBuilder = SaveInfo.Builder(
            SaveInfo.SAVE_DATA_TYPE_USERNAME or SaveInfo.SAVE_DATA_TYPE_PASSWORD,
            allIds.toTypedArray()
        )
        builder.setSaveInfo(saveInfoBuilder.build())
    }

    private fun extractValues(
        node: AssistStructure.ViewNode,
        parsed: ParsedStructure,
        onUsername: (String) -> Unit,
        onPassword: (String) -> Unit
    ) {
        val id = node.autofillId
        val value = node.autofillValue?.textValue?.toString()
        if (id != null && value != null) {
            if (id in parsed.usernameFields) onUsername(value)
            if (id in parsed.passwordFields) onPassword(value)
        }
        for (i in 0 until node.childCount) {
            extractValues(node.getChildAt(i), parsed, onUsername, onPassword)
        }
    }
}
