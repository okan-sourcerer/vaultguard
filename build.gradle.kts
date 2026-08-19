// Top-level build file where you can add configuration options common to all sub-projects/modules.
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.ksp) apply false
    alias(libs.plugins.hilt) apply false
    alias(libs.plugins.google.gms.google.services) apply false
}

/**
 * The browser extension is one source tree packaged twice: browsers load a directory, and a
 * symlink is not portable across the tools people use to zip one. `extension/shared` is the
 * source; the per-browser directories hold only their manifest plus a copy.
 *
 * Run this after editing anything in `extension/shared`.
 */
tasks.register<Copy>("syncExtension") {
    description = "Copies extension/shared into the chrome and firefox packages."
    group = "build"

    from("extension/shared") {
        include("*.js", "*.html", "*.css")
    }
    into(layout.projectDirectory.dir("extension"))

    eachFile {
        // One source file lands in both packages.
        relativePath = RelativePath(true, "chrome", relativePath.lastName)
    }
    doLast {
        copy {
            from("extension/shared") { include("*.js", "*.html", "*.css") }
            into("extension/firefox")
        }
    }
    includeEmptyDirs = false
}

/**
 * Zips each browser package.
 *
 * Chrome loads an unpacked directory and keeps it across restarts, so the zip is only for
 * distribution. Firefox is the one that needs it: an unsigned add-on cannot be installed
 * permanently on release Firefox at all, so the zip is what gets submitted to AMO for
 * unlisted signing.
 */
listOf("chrome", "firefox").forEach { browser ->
    tasks.register<Zip>("package${browser.replaceFirstChar { it.uppercase() }}Extension") {
        dependsOn("syncExtension")
        description = "Zips the $browser extension for distribution or signing."
        group = "build"

        from("extension/$browser")
        archiveFileName.set("vaultguard-$browser.zip")
        destinationDirectory.set(layout.buildDirectory.dir("extension"))
    }
}

/**
 * The two manifests are separate files and their versions have to move together: AMO
 * refuses a repeat upload of a version it has already signed, so a Firefox manifest left
 * behind is found only after a round trip through submission.
 */
val checkExtensionVersions by tasks.registering {
    description = "Fails if the browser manifests disagree about the version."

    doLast {
        val versions = listOf("chrome", "firefox").associateWith { browser ->
            val manifest = file("extension/$browser/manifest.json").readText()
            Regex(""""version"\s*:\s*"([^"]+)"""").find(manifest)?.groupValues?.get(1)
                ?: throw GradleException("No version in the $browser manifest.")
        }

        if (versions.values.distinct().size != 1) {
            throw GradleException(
                "The extension manifests disagree about the version: $versions. " +
                    "Bump both, or AMO will reject the upload as a duplicate."
            )
        }
        logger.lifecycle("Extension version ${versions.values.first()}")
    }
}

tasks.register("packageExtensions") {
    dependsOn(checkExtensionVersions, "packageChromeExtension", "packageFirefoxExtension")
    description = "Zips both browser extensions."
    group = "build"
}
