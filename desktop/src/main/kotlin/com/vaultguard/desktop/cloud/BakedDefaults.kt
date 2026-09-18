package com.vaultguard.desktop.cloud

import java.io.File
import java.util.Properties

/**
 * Configuration in two layers: what the build baked into the jar, and what the user wrote
 * in `~/.vaultguard/desktop.properties`. The file wins, key by key.
 *
 * The baked layer is what makes a downloaded installer usable: without it every copy
 * needed the developer's Firebase and OAuth identifiers typed into a file. The file
 * remains for pointing a build at a different project, or for a build made without the
 * values (they are then empty in the resource, and [DesktopConfig] names the missing key).
 */
object BakedDefaults {

    private const val RESOURCE = "/vaultguard-defaults.properties"

    val userFile: File
        get() = File(System.getProperty("user.home"), ".vaultguard/desktop.properties")

    fun load(userFile: File = this.userFile): Properties {
        val merged = Properties()
        BakedDefaults::class.java.getResourceAsStream(RESOURCE)?.use { merged.load(it) }
        if (userFile.isFile) {
            val user = Properties()
            userFile.inputStream().use { user.load(it) }
            // An empty value in the file should not blank out a baked one; a template with
            // untouched lines is the common shape of that file.
            for (key in user.stringPropertyNames()) {
                user.getProperty(key)?.trim()?.takeIf { it.isNotEmpty() }?.let { merged.setProperty(key, it) }
            }
        }
        return merged
    }

    fun value(key: String, properties: Properties = load()): String? =
        properties.getProperty(key)?.trim()?.takeIf { it.isNotEmpty() }

    /** The version the build stamped, for the feedback report and `--help`. */
    val version: String get() = value("version") ?: "dev"
}
