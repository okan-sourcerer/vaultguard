package com.vaultguard.app.autofill

import android.app.PendingIntent
import android.app.assist.AssistStructure
import android.content.Intent
import android.os.Build
import android.os.CancellationSignal
import android.service.autofill.AutofillService
import android.service.autofill.Dataset
import android.service.autofill.FillCallback
import android.service.autofill.FillRequest
import android.service.autofill.FillResponse
import android.service.autofill.InlinePresentation
import android.service.autofill.SaveCallback
import android.service.autofill.SaveInfo
import android.service.autofill.SaveRequest
import android.view.autofill.AutofillId
import android.view.autofill.AutofillValue
import android.widget.RemoteViews
import android.widget.inline.InlinePresentationSpec
import com.vaultguard.app.MainActivity
import com.vaultguard.app.R
import com.vaultguard.app.data.local.db.dao.CredentialDao
import com.vaultguard.app.data.repository.CredentialPayloadCodec
import com.vaultguard.app.domain.model.Credential
import com.vaultguard.app.security.CryptoManager
import com.vaultguard.app.security.EncryptedData
import com.vaultguard.app.security.MasterPasswordManager
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import timber.log.Timber
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
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
        // The platform cancels a request when the user has moved on before it was answered
        // — a new screen, a dismissed keyboard, a field that lost focus. The signal was
        // accepted and ignored (#51), so the work carried on, decrypted the whole vault,
        // and answered a request nobody was waiting for.
        //
        // The listener is registered before any work starts, and fires immediately if the
        // request was already cancelled. Cancelling the job as well is what actually stops
        // the decryption; the flag is what keeps the callback quiet either way.
        val cancelled = AtomicBoolean(false)
        val job = AtomicReference<Job?>(null)
        cancellationSignal.setOnCancelListener {
            cancelled.set(true)
            job.get()?.cancel()
        }

        fun respond(response: FillResponse?) {
            if (cancelled.get()) return
            callback.onSuccess(response)
        }

        // Fill answers the screen in front of the user, so unlike the save path (#54) the
        // last context is the right one.
        val structure = request.fillContexts.lastOrNull()?.structure ?: run {
            respond(null)
            return
        }

        val parsed = StructureParser(structure).parse()

        // Only respond if we found username or password fields
        if (parsed.usernameFields.isEmpty() && parsed.passwordFields.isEmpty()) {
            respond(null)
            return
        }

        // What the keyboard is willing to draw in its suggestion strip, if anything (#50).
        val inlineSpecs = InlineSuggestions.Specs.from(request)

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
                // The activity answers with its own FillResponse, so it needs the
                // keyboard's specs to put its results back in the strip the user tapped
                // in rather than in the drop-down menu (#50).
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    request.inlineSuggestionsRequest?.let {
                        putExtra(AutofillAuthActivity.EXTRA_INLINE_REQUEST, it)
                    }
                }
            }

            val pendingIntent = PendingIntent.getActivity(
                this, nextRequestCode(), authIntent,
                PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )

            val presentation = RemoteViews(packageName, R.layout.autofill_item).apply {
                setTextViewText(R.id.autofill_text, "Unlock VaultGuard")
            }

            val responseBuilder = FillResponse.Builder()
            val authIds = (parsed.usernameFields + parsed.passwordFields).toTypedArray()

            // A locked vault has to reach the strip too. Without this the user sees the
            // keyboard offer nothing at all and concludes VaultGuard has no password for
            // the site, when it is only locked.
            val unlockInline = InlineSuggestions.build(
                this, inlineSpecs.at(0), "Unlock VaultGuard", null, nextRequestCode()
            )

            if (unlockInline != null) {
                responseBuilder.setAuthentication(
                    authIds, pendingIntent.intentSender, presentation, unlockInline
                )
            } else {
                responseBuilder.setAuthentication(authIds, pendingIntent.intentSender, presentation)
            }

            addSaveInfo(responseBuilder, parsed)
            respond(responseBuilder.build())
            return
        }

        // Vault is unlocked — find matching credentials
        job.set(scope.launch {
            try {
                val credentials = findMatchingCredentials(parsed.webDomain, parsed.packageName)
                if (credentials.isEmpty()) {
                    respond(buildEmptyResponse(parsed))
                    return@launch
                }

                val responseBuilder = FillResponse.Builder()
                for ((index, credential) in credentials.withIndex()) {
                    val inlinePresentation = InlineSuggestions.build(
                        this@VaultAutofillService,
                        inlineSpecs.at(index),
                        credential.displayName,
                        credential.username,
                        nextRequestCode()
                    )
                    val dataset = buildDataset(credential, parsed, inlinePresentation)
                    if (dataset != null) {
                        responseBuilder.addDataset(dataset)
                    }
                }
                addSaveInfo(responseBuilder, parsed)
                respond(responseBuilder.build())
            } catch (_: CancellationException) {
                // Cancelled mid-decryption; the caller is gone and wants no answer.
            } catch (_: Exception) {
                respond(null)
            }
        })

        // Covers a cancellation that arrived between registering the listener and having
        // a job to cancel.
        if (cancelled.get()) job.get()?.cancel()
    }

    override fun onSaveRequest(request: SaveRequest, callback: SaveCallback) {
        // Every context of the session, not just the last. A two-step login puts the
        // username in an earlier one, and reading only the last discarded it (#54).
        val merged = SaveValueMerge.merge(
            request.fillContexts.map { observe(it.structure) }
        )

        val username = merged.username
        val password = merged.password

        if (password.isEmpty()) {
            callback.onSuccess()
            return
        }

        // If the user has already dismissed the save prompt for this app/site, do not show it again
        if (dismissedPrefs.isDismissed(merged.webDomain, merged.packageName)) {
            callback.onSuccess()
            return
        }

        // The duplicate check decrypts the whole vault, which used to happen inside
        // runBlocking on the main thread — an ANR that scaled with vault size (finding #18).
        // SaveCallback may be answered asynchronously, so do the work off-thread and
        // respond when it is done.
        scope.launch {
            try {
                val known = if (masterPasswordManager.isVaultUnlocked) {
                    findMatchingCredentials(merged.webDomain, merged.packageName)
                } else {
                    emptyList()
                }

                // With #54 fixed the username is populated for a two-step login, so the
                // usual comparison works again. It can still be empty on a genuine
                // password-only screen — a re-authentication prompt — and there the safe
                // reading is that this belongs to an account already held, rather than a
                // new blank-username entry beside it (#55).
                val isDuplicate = if (username.isEmpty()) {
                    known.isNotEmpty()
                } else {
                    known.any { it.username == username }
                }

                if (isDuplicate) {
                    callback.onSuccess()
                    return@launch
                }

                val saveIntent = Intent(this@VaultAutofillService, AutofillSaveActivity::class.java).apply {
                    putExtra(AutofillSaveActivity.EXTRA_USERNAME, username)
                    putExtra(AutofillSaveActivity.EXTRA_PASSWORD, password)
                    putExtra(AutofillSaveActivity.EXTRA_WEB_DOMAIN, merged.webDomain)
                    putExtra(AutofillSaveActivity.EXTRA_PACKAGE_NAME, merged.packageName)
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

    private fun buildDataset(
        credential: Credential,
        parsed: ParsedStructure,
        inlinePresentation: InlinePresentation?
    ): Dataset? {
        val presentation = RemoteViews(packageName, R.layout.autofill_item).apply {
            setTextViewText(R.id.autofill_text, "${credential.displayName} — ${credential.username}")
        }

        val builder = Dataset.Builder(presentation)
        var hasValue = false

        for (id in parsed.usernameFields) {
            setDatasetValue(builder, id, credential.username, presentation, inlinePresentation)
            hasValue = true
        }
        for (id in parsed.passwordFields) {
            setDatasetValue(builder, id, credential.password, presentation, inlinePresentation)
            hasValue = true
        }

        return if (hasValue) builder.build() else null
    }

    /**
     * The inline-carrying overloads are marked deprecated in favour of the `Presentations`
     * API, which is API 33. minSdk here is 28, and these still work on every version from
     * 30 up, so one code path covers the range instead of three. Revisit when minSdk
     * reaches 33.
     */
    @Suppress("DEPRECATION")
    private fun setDatasetValue(
        builder: Dataset.Builder,
        id: AutofillId,
        text: String,
        presentation: RemoteViews,
        inlinePresentation: InlinePresentation?
    ) {
        val value = AutofillValue.forText(text)
        if (inlinePresentation != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            builder.setValue(id, value, presentation, inlinePresentation)
        } else {
            builder.setValue(id, value)
        }
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

        // Declare what this screen actually holds. The pair was announced unconditionally
        // before, which told the platform a password was coming on screens that had none.
        var dataTypes = 0
        if (parsed.usernameFields.isNotEmpty()) dataTypes = dataTypes or SaveInfo.SAVE_DATA_TYPE_USERNAME
        if (parsed.passwordFields.isNotEmpty()) dataTypes = dataTypes or SaveInfo.SAVE_DATA_TYPE_PASSWORD

        val saveInfoBuilder = SaveInfo.Builder(dataTypes, allIds.toTypedArray())

        // A username with no password is the first half of a two-step login. Without this
        // flag the platform can commit that context as soon as the screen is left, firing
        // a save for a credential whose password has not been typed yet — and the context
        // that does carry the password arrives after (#54).
        if (parsed.passwordFields.isEmpty()) {
            saveInfoBuilder.setFlags(SaveInfo.FLAG_DELAY_SAVE)
        }

        builder.setSaveInfo(saveInfoBuilder.build())
    }

    /**
     * Reduces one fill context to the values it carries. [SaveValueMerge] then combines
     * the contexts of the whole session.
     *
     * The same last-non-blank rule applies inside a context, because a page can hold more
     * than one form — a sign-in beside a sign-up — and an empty field further down the
     * tree must not wipe out one that was actually filled.
     */
    private fun observe(structure: AssistStructure): SaveValueMerge.Observation {
        val parsed = StructureParser(structure).parse()
        var username = ""
        var password = ""

        for (i in 0 until structure.windowNodeCount) {
            extractValues(
                structure.getWindowNodeAt(i).rootViewNode,
                parsed,
                { if (it.isNotBlank()) username = it },
                { if (it.isNotBlank()) password = it }
            )
        }

        return SaveValueMerge.Observation(username, password, parsed.webDomain, parsed.packageName)
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
