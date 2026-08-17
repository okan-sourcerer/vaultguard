package com.vaultguard.app.autofill

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon
import android.os.Build
import android.service.autofill.FillRequest
import android.service.autofill.InlinePresentation
import android.view.inputmethod.InlineSuggestionsRequest
import android.widget.inline.InlinePresentationSpec
import androidx.autofill.inline.UiVersions
import androidx.autofill.inline.v1.InlineSuggestionUi
import com.vaultguard.app.MainActivity
import com.vaultguard.app.R
import timber.log.Timber

/**
 * The chips that appear in the keyboard's suggestion strip (finding #50).
 *
 * Without these, a fill response only ever reaches the drop-down menu attached to the
 * field. On a recent device with a keyboard that supports inline suggestions — Gboard
 * does — that menu is the path nobody takes: the strip above the keys is where the user is
 * already looking, and a password manager that never appears there reads as one that does
 * not work.
 *
 * Inline suggestions arrived in Android 11 (API 30). Below that, and on keyboards that do
 * not offer them, everything here returns null and the menu presentation carries the whole
 * response, exactly as before.
 *
 * Shared by [VaultAutofillService] and [AutofillAuthActivity] because both answer fill
 * requests — the second front door of rule 6. A credential unlocked through the auth
 * activity has to come back to the same strip the user tapped in.
 */
object InlineSuggestions {

    /**
     * The presentation specs a keyboard offered for one request.
     *
     * Empty whenever inline suggestions are unavailable, which keeps the call sites free
     * of version checks.
     */
    class Specs private constructor(
        private val specs: List<InlinePresentationSpec>,
        private val maxSuggestions: Int
    ) {
        /**
         * Keyboards commonly advertise fewer specs than the number of suggestions they
         * will display; the last is understood to repeat. Returns null once past what the
         * keyboard said it would show, leaving the remaining credentials menu-only rather
         * than dropping them.
         */
        fun at(index: Int): InlinePresentationSpec? = when {
            specs.isEmpty() || index >= maxSuggestions -> null
            else -> specs.getOrNull(index) ?: specs.last()
        }

        companion object {
            val NONE = Specs(emptyList(), 0)

            fun from(request: FillRequest): Specs {
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return NONE
                return from(request.inlineSuggestionsRequest)
            }

            fun from(request: InlineSuggestionsRequest?): Specs {
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R || request == null) return NONE
                return Specs(request.inlinePresentationSpecs, request.maxSuggestionCount)
            }
        }
    }

    /**
     * @param requestCode must differ per PendingIntent within a session — sharing one is
     *   what made concurrent auth intents cancel each other in #52.
     */
    fun build(
        context: Context,
        spec: InlinePresentationSpec?,
        title: String,
        subtitle: String?,
        requestCode: Int
    ): InlinePresentation? {
        if (spec == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null

        // The IME, not the service, decides how a chip is drawn, and advertises what it
        // can render through the spec's style bundle. A spec that does not carry version 1
        // is one this code cannot draw into, so decline rather than guess.
        if (!UiVersions.getVersions(spec.style).contains(UiVersions.INLINE_UI_VERSION_1)) {
            return null
        }

        return try {
            // Attribution only: shown if the user long-presses the chip to ask where the
            // suggestion came from. A tap fills the field and never launches this.
            val attribution = PendingIntent.getActivity(
                context,
                requestCode,
                Intent(context, MainActivity::class.java),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )

            val content = InlineSuggestionUi.newContentBuilder(attribution)
                .setTitle(title)
                .apply { if (!subtitle.isNullOrBlank()) setSubtitle(subtitle) }
                .setStartIcon(Icon.createWithResource(context, R.drawable.ic_autofill_inline))
                .build()

            InlinePresentation(content.slice, spec, false)
        } catch (e: Exception) {
            // A malformed spec from a third-party keyboard must not cost the user the
            // whole fill response — the menu presentation still works without this.
            Timber.w(e, "Could not build an inline suggestion")
            null
        }
    }
}
