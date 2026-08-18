package com.vaultguard.desktop.cloud

import java.io.File
import java.util.Properties

/**
 * Where the desktop client gets its Firebase and OAuth identifiers.
 *
 * None of this is checked in. `projectId` and `apiKey` are already public — they sit in
 * `app/google-services.json` in this repository, and a Firebase web API key identifies a
 * project rather than authorising anything on its own. The OAuth client id and secret are
 * kept out of the repository regardless: an installed-app secret is not a real secret
 * either, but committing credentials trains the wrong habit.
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

            projectId=
            apiKey=
            oauthClientId=
            oauthClientSecret=
        """.trimIndent()

        class MissingConfigException(message: String) : Exception(message)

        fun load(file: File = defaultPath): DesktopConfig {
            if (!file.exists()) {
                throw MissingConfigException(
                    "No desktop configuration at ${file.path}.\n" +
                        "Run `vaultguard --cloud-setup` to write a template there."
                )
            }

            val properties = Properties()
            file.inputStream().use { properties.load(it) }

            fun require(key: String): String {
                val value = properties.getProperty(key)?.trim()
                if (value.isNullOrEmpty()) {
                    throw MissingConfigException("${file.path} is missing a value for `$key`.")
                }
                return value
            }

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
