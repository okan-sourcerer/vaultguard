package com.vaultguard.desktop.cloud

import java.io.File
import java.util.Properties

/**
 * Where the desktop client gets its Firebase and OAuth identifiers.
 *
 * Baked into the jar at build time (see `bakedDefaults` in `desktop/build.gradle.kts`),
 * overridable key by key from `~/.vaultguard/desktop.properties` through [BakedDefaults].
 * `projectId` and `apiKey` are public — they sit in every APK, and a Firebase web API key
 * identifies a project rather than authorising anything on its own. The OAuth client id
 * and secret are not committed to the repository: an installed-app secret is not a real
 * secret either, but committing credentials trains the wrong habit.
 *
 * Nothing here grants access to the vault. Every value is about reaching Firestore as the
 * signed-in user; the vault's contents remain sealed under the master password.
 */
data class DesktopConfig(
    val projectId: String,
    val apiKey: String,
    val oauthClientId: String,
    val oauthClientSecret: String
) {
    companion object {

        val defaultPath: File
            get() = File(System.getProperty("user.home"), ".vaultguard/desktop.properties")

        val template: String = """
            # VaultGuard desktop client configuration.
            #
            # A release build has these baked in and needs nothing here. Any value given
            # below overrides the baked one; an empty line leaves it alone.
            #
            # projectId and apiKey: copy from app/google-services.json in the repository.
            #   projectId -> project_info.project_id
            #   apiKey    -> client[0].api_key[0].current_key
            #
            # oauthClientId and oauthClientSecret: create an OAuth client in the Google
            # Cloud console for this project, of type "Desktop app", and paste its
            # credentials here. A desktop client is required: the Android and Web clients
            # already in google-services.json cannot complete a loopback redirect.
            #
            #   https://console.cloud.google.com/apis/credentials
            #
            # feedbackUrl and feedbackKey: the feedback hub, if the tray's "Send
            # feedback..." should reach one.

            projectId=
            apiKey=
            oauthClientId=
            oauthClientSecret=
            feedbackUrl=
            feedbackKey=
        """.trimIndent()

        class MissingConfigException(message: String) : Exception(message)

        fun load(file: File = defaultPath): DesktopConfig {
            val properties = BakedDefaults.load(file)

            fun require(key: String): String =
                BakedDefaults.value(key, properties)
                    ?: throw MissingConfigException(
                        "No value for `$key`: this build has none baked in and ${file.path} " +
                            (if (file.exists()) "does not set it." else "does not exist.") +
                            "\nRun `vaultguard --cloud-setup` to write a template there."
                    )

            return DesktopConfig(
                projectId = require("projectId"),
                apiKey = require("apiKey"),
                oauthClientId = require("oauthClientId"),
                oauthClientSecret = require("oauthClientSecret")
            )
        }

        /** @return true if a template was written, false if a file was already there. */
        fun writeTemplate(file: File = defaultPath): Boolean {
            if (file.exists()) return false
            file.parentFile?.mkdirs()
            file.writeText(template + "\n")
            return true
        }
    }
}
