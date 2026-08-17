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
import com.vaultguard.app.domain.model.Credential
import com.vaultguard.app.security.CryptoManager
import com.vaultguard.app.security.EncryptedData
import com.vaultguard.app.security.MasterPasswordManager
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.json.JSONObject
import timber.log.Timber
import javax.inject.Inject

@AndroidEntryPoint
class VaultAutofillService : AutofillService() {

    @Inject lateinit var masterPasswordManager: MasterPasswordManager
    @Inject lateinit var cryptoManager: CryptoManager
    @Inject lateinit var credentialDao: CredentialDao
    @Inject lateinit var dismissedPrefs: AutofillDismissedPrefs

    private val scope = CoroutineScope(Dispatchers.IO)

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

        // If vault is locked, present an auth intent
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
                this, 0, authIntent,
                PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_CANCEL_CURRENT
            )

            val presentation = RemoteViews(packageName, R.layout.autofill_item).apply {
                setTextViewText(R.id.autofill_text, "Unlock VaultGuard")
            }

            val responseBuilder = FillResponse.Builder()
            val datasetBuilder = Dataset.Builder(presentation)

            // Set a placeholder value so the dataset is valid
            val targetId = parsed.usernameFields.firstOrNull() ?: parsed.passwordFields.first()
            datasetBuilder.setValue(targetId, null)
            datasetBuilder.setAuthentication(pendingIntent.intentSender)

            responseBuilder.addDataset(datasetBuilder.build())
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

                if (!isDuplicate) {
                    val saveIntent = Intent(this@VaultAutofillService, AutofillSaveActivity::class.java).apply {
                        putExtra(AutofillSaveActivity.EXTRA_USERNAME, username)
                        putExtra(AutofillSaveActivity.EXTRA_PASSWORD, password)
                        putExtra(AutofillSaveActivity.EXTRA_WEB_DOMAIN, parsed.webDomain)
                        putExtra(AutofillSaveActivity.EXTRA_PACKAGE_NAME, parsed.packageName)
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    startActivity(saveIntent)
                }
            } catch (e: Exception) {
                Timber.e(e, "Autofill save handling failed")
            } finally {
                // Always answer — an unanswered SaveCallback leaves the system waiting.
                callback.onSuccess()
            }
        }
    }

    private fun findMatchingCredentials(webDomain: String?, packageName: String?): List<Credential> {
        if (!masterPasswordManager.isVaultUnlocked) return emptyList()

        val key = masterPasswordManager.getSessionKey()
        val allEntities = credentialDao.getAllBlocking()

        return allEntities
            .filter { !it.isDeleted }
            .mapNotNull { entity ->
                try {
                    val decrypted = cryptoManager.decrypt(
                        EncryptedData(entity.encryptedPayload, entity.iv), key
                    )
                    val json = String(decrypted, Charsets.UTF_8)
                    decrypted.fill(0)
                    val obj = JSONObject(json)
                    val linkedPackages = mutableListOf<String>()
                    val linkedDomains = mutableListOf<String>()
                    obj.optJSONArray("linkedPackages")?.let { arr ->
                        for (i in 0 until arr.length()) linkedPackages.add(arr.getString(i))
                    }
                    obj.optJSONArray("linkedDomains")?.let { arr ->
                        for (i in 0 until arr.length()) linkedDomains.add(arr.getString(i))
                    }
                    val credential = Credential(
                        id = entity.id,
                        siteName = obj.optString("siteName", ""),
                        url = obj.optString("url", ""),
                        username = obj.optString("username", ""),
                        password = obj.optString("password", ""),
                        linkedPackages = linkedPackages,
                        linkedDomains = linkedDomains
                    )
                    credential
                } catch (_: Exception) {
                    null
                }
            }
            .let { allDecrypted ->
                // Tier 1: exact linked domain/package matches
                val exact = allDecrypted.filter { cred ->
                    exactMatchesDomain(cred, webDomain) || exactMatchesPackage(cred, packageName)
                }
                if (exact.isNotEmpty()) return@let exact

                // Tier 2: URL-based matches
                val urlMatches = allDecrypted.filter { cred ->
                    fuzzyMatchesDomain(cred, webDomain) || fuzzyMatchesPackage(cred, packageName)
                }
                urlMatches
            }
    }

    private fun exactMatchesDomain(credential: Credential, webDomain: String?): Boolean {
        if (webDomain.isNullOrBlank()) return false
        val domain = webDomain.lowercase()
        // Linked domains
        if (credential.linkedDomains.any { it.lowercase() == domain }) return true
        // URL host exact match
        val credHost = credential.url.lowercase()
            .removePrefix("https://")
            .removePrefix("http://")
            .split("/").firstOrNull() ?: ""
        return credHost.isNotEmpty() && credHost == domain
    }

    private fun exactMatchesPackage(credential: Credential, packageName: String?): Boolean {
        if (packageName.isNullOrBlank()) return false
        return credential.linkedPackages.any { it.lowercase() == packageName.lowercase() }
    }

    private fun fuzzyMatchesDomain(credential: Credential, webDomain: String?): Boolean {
        if (webDomain.isNullOrBlank()) return false
        val domain = webDomain.lowercase()
        val credUrl = credential.url.lowercase()
            .removePrefix("https://")
            .removePrefix("http://")
            .removeSuffix("/")
        if (credUrl.isEmpty()) return false
        val credHost = credUrl.split("/").firstOrNull() ?: ""
        // Check if domains share the same base (e.g. accounts.google.com vs google.com)
        return credHost.isNotEmpty() && (domain.endsWith(credHost) || credHost.endsWith(domain))
    }

    private fun fuzzyMatchesPackage(credential: Credential, packageName: String?): Boolean {
        if (packageName.isNullOrBlank()) return false
        val pkg = packageName.lowercase()
        val siteName = credential.siteName.lowercase().trim()
        if (siteName.isEmpty() || siteName.length < 3) return false
        // Only match if the full site name appears as a segment in the package name
        return pkg.split(".").any { it == siteName }
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
