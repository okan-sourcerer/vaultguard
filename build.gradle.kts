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
