package com.vaultguard.app.ui.screens.feedback

import android.os.Build
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.vaultguard.app.BuildConfig
import com.vaultguard.app.feedback.FeedbackClient
import com.vaultguard.app.feedback.FeedbackReport
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale
import java.util.TimeZone
import javax.inject.Inject

data class FeedbackUiState(
    val type: FeedbackReport.Type = FeedbackReport.Type.BUG,
    val message: String = "",
    val isSending: Boolean = false,
    /** Set once the hub has taken the report; the screen then offers only Done. */
    val sentId: String? = null,
    val error: String? = null
) {
    val canSend: Boolean get() = message.isNotBlank() && !isSending && sentId == null
}

/**
 * Builds the report the user sees, sends it when they say so.
 *
 * The idempotency key is fixed for the life of the view model, so "Send" pressed twice
 * after a timeout — the natural thing to do — files one report, not two.
 */
@HiltViewModel
class FeedbackViewModel @Inject constructor() : ViewModel() {

    private val client = FeedbackClient(BuildConfig.FEEDBACK_URL, BuildConfig.FEEDBACK_KEY)
    private val idempotencyKey = java.util.UUID.randomUUID().toString()

    private val _uiState = MutableStateFlow(FeedbackUiState())
    val uiState: StateFlow<FeedbackUiState> = _uiState

    fun onTypeChange(type: FeedbackReport.Type) {
        _uiState.value = _uiState.value.copy(type = type, error = null)
    }

    fun onMessageChange(message: String) {
        _uiState.value = _uiState.value.copy(
            message = message.take(FeedbackReport.MAX_MESSAGE_LENGTH),
            error = null
        )
    }

    /** Exactly what [send] will post — rendered on the screen, not paraphrased. */
    fun report(): FeedbackReport = FeedbackReport(
        type = _uiState.value.type,
        message = _uiState.value.message.trim(),
        appVersion = BuildConfig.VERSION_NAME,
        environment = if (BuildConfig.DEBUG) FeedbackReport.Environment.DEV else FeedbackReport.Environment.PROD,
        platform = FeedbackReport.Platform.ANDROID,
        os = "Android ${Build.VERSION.RELEASE}",
        device = "${Build.MANUFACTURER} ${Build.MODEL}".trim(),
        locale = Locale.getDefault().toLanguageTag(),
        timezone = TimeZone.getDefault().id,
        idempotencyKey = idempotencyKey
    )

    fun send() {
        val state = _uiState.value
        if (!state.canSend) return
        _uiState.value = state.copy(isSending = true, error = null)

        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) { client.send(report()) }
            _uiState.value = when (result) {
                is FeedbackClient.Result.Sent -> _uiState.value.copy(isSending = false, sentId = result.id)
                is FeedbackClient.Result.Rejected -> _uiState.value.copy(isSending = false, error = result.detail)
                is FeedbackClient.Result.Failed -> _uiState.value.copy(
                    isSending = false,
                    error = "Could not reach the feedback hub: ${result.reason}"
                )
            }
        }
    }
}
