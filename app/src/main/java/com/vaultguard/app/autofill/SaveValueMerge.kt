package com.vaultguard.app.autofill

/**
 * Merges the field values observed across every fill context of one autofill session.
 *
 * Android accumulates a session rather than treating each screen separately: every fill
 * request adds a context, and `onSaveRequest` receives all of them. A two-step login —
 * username on one screen, password on the next, which is how Google, Microsoft, Amazon and
 * most banks work — therefore arrives as two contexts. Reading only the last one saw a
 * password and no username at all (finding #54), which then defeated the duplicate check
 * and produced a blank-username copy of a credential already in the vault (finding #55).
 *
 * The rule is **last non-blank wins**, applied per field. A later screen that re-renders
 * the username field empty does not erase what was typed on the earlier one; a user who
 * goes back and corrects a typo does overwrite it.
 *
 * The same rule decides the domain and package, which means they come from whichever
 * screen carried the password. On an SSO hop that is the identity provider — the right
 * answer, because that is the account the password opens.
 *
 * Pure Kotlin so it can be tested on the host JVM; the Android types stay in the service.
 */
internal object SaveValueMerge {

    data class Observation(
        val username: String = "",
        val password: String = "",
        val webDomain: String? = null,
        val packageName: String? = null
    )

    data class Merged(
        val username: String,
        val password: String,
        val webDomain: String?,
        val packageName: String?
    )

    fun merge(observations: List<Observation>): Merged {
        var username = ""
        var password = ""
        var webDomain: String? = null
        var packageName: String? = null

        for (observation in observations) {
            if (observation.username.isNotBlank()) username = observation.username
            if (observation.password.isNotBlank()) password = observation.password
            if (!observation.webDomain.isNullOrBlank()) webDomain = observation.webDomain
            if (!observation.packageName.isNullOrBlank()) packageName = observation.packageName
        }

        return Merged(username, password, webDomain, packageName)
    }
}
