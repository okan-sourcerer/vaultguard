pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "VaultGuard"

// Pure-JVM half of the app: crypto, the payload contract, the generator, the merge
// rules. Shared so a desktop client cannot drift from the phone about how a key is
// derived or a payload is shaped. See docs/ARCHITECTURE.md.
include(":core")
include(":app")
include(":desktop")
 