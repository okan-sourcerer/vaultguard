package com.vaultguard.app.autofill

import com.vaultguard.app.domain.model.Credential

/**
 * Decides what to do with a credential captured from a form (finding #56).
 *
 * The rule used to be "does any matching entry share this username?", and if so the capture
 * was dropped. That reads as duplicate suppression, but it also silently discards the case
 * that matters most: a password *rotated* on the website. The vault kept the old value
 * indefinitely, with nothing to indicate it had gone stale, and the next autofill typed a
 * password the site would reject.
 *
 * Pure Kotlin — [Credential] carries no Android types — so the decision is testable on the
 * host JVM, which is where the previous version's flaw would have shown up. It lives in
 * `:core` because the desktop makes the same decision for a login captured by the browser
 * extension; a second copy of this is how matchers drift (#11).
 */
object SaveDecision {

    sealed interface Outcome {
        /** The vault already holds this, or cannot tell which entry is meant. Stay quiet. */
        data object Ignore : Outcome

        /** Not seen before. Offer to save it. */
        data object CreateNew : Outcome

        /** Same account, different password. Offer to replace the stored one. */
        data class UpdateExisting(val id: String) : Outcome

        /**
         * Nothing is saved for this app or site, but an entry elsewhere holds exactly
         * this username and password - the same account seen through its app after being
         * saved from its website, or the reverse. Offer to add this app or site to that
         * entry rather than creating a twin of it.
         */
        data class LinkExisting(val id: String) : Outcome
    }

    /**
     * @param known the entries [CredentialMatcher] considers valid for this app or site.
     *   Empty when the vault is locked, which correctly yields [Outcome.CreateNew] — the
     *   save activity gates on the master password before writing anything.
     * @param everything the whole vault, for [Outcome.LinkExisting]. Defaults to [known],
     *   which makes linking impossible and keeps older callers' behaviour.
     */
    fun decide(
        username: String,
        password: String,
        known: List<Credential>,
        everything: List<Credential> = known
    ): Outcome {
        val existing = if (username.isEmpty()) {
            // A password-only screen — a re-authentication prompt. It can only be
            // attributed when there is exactly one candidate; guessing between several
            // would update the wrong account.
            known.singleOrNull()
        } else {
            known.firstOrNull { it.username == username }
        }

        return when {
            existing == null && username.isEmpty() && known.isNotEmpty() ->
                // Several candidates and no username to choose between them. Creating an
                // entry here is what produced the blank-username duplicates of #55.
                Outcome.Ignore

            existing == null && username.isNotEmpty() -> {
                // Exact username and password, saved for some other site or app. One
                // match only: two entries with the same pair are already twins, and
                // choosing between them here would be a guess.
                val twin = everything.filter { it.username == username && it.password == password }
                if (twin.size == 1) Outcome.LinkExisting(twin.single().id) else Outcome.CreateNew
            }

            existing == null -> Outcome.CreateNew

            existing.password == password -> Outcome.Ignore

            else -> Outcome.UpdateExisting(existing.id)
        }
    }
}
