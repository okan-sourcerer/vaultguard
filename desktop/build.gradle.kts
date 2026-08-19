plugins {
    alias(libs.plugins.kotlin.jvm)
    application
}

// A desktop client for the same vault. It exists first of all as proof that :core really
// is portable — if the Argon2id configuration or the UTF-16BE password encoding differed
// by one byte here, a backup written by the phone would not open.
//
// It deliberately reads and writes the v2 backup file rather than talking to Firestore.
// That keeps it offline and reversible while the foundation is being proven; the sync
// client is the next step, not this one.

// Newer than :core's Java 11, which exists to match what Android can dex. Nothing here
// is dexed, and java.net.http (the only HTTP client used) wants 11 or later anyway.
kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

application {
    mainClass.set("com.vaultguard.desktop.MainKt")
    applicationName = "vaultguard"
}

dependencies {
    implementation(project(":core"))

    // :core declares org.json compileOnly, because Android ships it in the framework and
    // a second copy on the APK classpath would be a problem. Off Android there is no
    // framework copy, so a consumer has to bring its own.
    implementation(libs.json)

    testImplementation(libs.junit)
    // FakeSecurePrefs, so a test can drive MasterPasswordManager exactly as the phone does
    // when it publishes a vault config, rather than hand-rolling the key hierarchy.
    testImplementation(testFixtures(project(":core")))
}

/**
 * A native launcher, so Windows has something to name and draw.
 *
 * Without this the tray service is `javaw.exe` in Task Manager with a coffee cup beside it,
 * which is a poor look for a thing holding your passwords open. `jpackage` ships with the
 * JDK, so this needs no tooling that is not already here.
 *
 * `app-image` rather than an installer: it produces a directory that can be run in place or
 * copied, and nothing has to be installed to try it.
 */
val writeIcon by tasks.registering(JavaExec::class) {
    description = "Generates the launcher icon from the same drawing the tray uses."
    dependsOn(tasks.named("installDist"))
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("com.vaultguard.desktop.MainKt")
    args = listOf("--write-icon", layout.buildDirectory.file("vaultguard.ico").get().asFile.absolutePath)
}

val packageApp by tasks.registering(Exec::class) {
    description = "Builds VaultGuard.exe with jpackage."
    group = "distribution"
    dependsOn(tasks.named("installDist"), writeIcon)

    val installDir = layout.buildDirectory.dir("install/vaultguard").get().asFile
    val outputDir = layout.buildDirectory.dir("native").get().asFile
    val icon = layout.buildDirectory.file("vaultguard.ico").get().asFile

    doFirst {
        val image = outputDir.resolve("VaultGuard")
        val exe = image.resolve("VaultGuard.exe")

        // Windows locks a running executable and the runtime beside it, so the delete below
        // half-succeeds and jpackage then refuses with "directory already exists" - which
        // says nothing about the actual cause. Checked first, and named.
        if (exe.exists() && isRunning(exe)) {
            throw GradleException(
                "VaultGuard is running from ${exe.path}, so its files cannot be replaced. " +
                    "Quit it from the tray icon (right-click -> Quit) and run this again."
            )
        }

        // A stale image is worse than none: it would keep launching the old build while
        // looking like the new one.
        if (image.exists() && !image.deleteRecursively()) {
            throw GradleException(
                "Could not remove ${image.path}. " +
                    "Something is holding a file in it open - a running VaultGuard, an " +
                    "antivirus scan, or an Explorer window inside the folder."
            )
        }
        outputDir.mkdirs()
    }

    commandLine(
        "${System.getProperty("java.home")}/bin/jpackage",
        "--type", "app-image",
        "--name", "VaultGuard",
        "--app-version", "1.0.0",
        "--vendor", "VaultGuard",
        "--description", "VaultGuard password vault",
        "--input", "$installDir/lib",
        "--main-jar", "desktop.jar",
        "--main-class", "com.vaultguard.desktop.MainKt",
        "--icon", icon.absolutePath,
        // The tray service is the only reason to double-click this.
        "--arguments", "--service",
        "--dest", outputDir.absolutePath
    )
}

/**
 * Whether a process is running from [executable].
 *
 * `ProcessHandle` rather than shelling out to `tasklist`: it is in the JDK, it reports the
 * full command path so a same-named process elsewhere is not mistaken for this one, and it
 * works the same on every platform. Processes the build cannot inspect report no command and
 * are treated as not matching, which is the right way to be wrong here — the worst case is
 * the clearer message below being replaced by jpackage's.
 */
fun isRunning(executable: File): Boolean =
    ProcessHandle.allProcesses().anyMatch { handle ->
        handle.info().command().map { it.equals(executable.absolutePath, ignoreCase = true) }.orElse(false)
    }
