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

    // A look and feel that is not 2003. Pure Java, MIT, no native code required (it has
    // optional Windows natives for window decorations, which it loads only if present and
    // falls back without). Nothing in it touches the vault; it draws widgets.
    implementation(libs.flatlaf)

    // StatusNotifierItem on Linux (docs/GNOME-TRAY-PLAN.md). The native-unixsocket
    // transport uses the JDK's own Unix-domain sockets: pure Java, no JNR or JNI in the
    // process that holds the vault. Loaded only when a session bus is found.
    implementation(libs.dbus.java.core)
    implementation(libs.dbus.java.unixsocket)

    testImplementation(libs.junit)
    // FakeSecurePrefs, so a test can drive MasterPasswordManager exactly as the phone does
    // when it publishes a vault config, rather than hand-rolling the key hierarchy.
    testImplementation(testFixtures(project(":core")))
}

tasks.withType<Test> {
    // The message and the top of the stack in the console, not only in an XML report that
    // the CI job has to be told to upload.
    testLogging {
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
        events("failed", "skipped")
    }
}

/**
 * The identifiers a downloaded copy needs to reach the vault, baked into the jar.
 *
 * `--cloud` and the tray both talk to one Firebase project through one OAuth desktop
 * client. Before this the values lived only in `~/.vaultguard/desktop.properties`, which
 * the developer has and a person who installed the .msi from a website does not. They are
 * read at build time from, in order: a Gradle property (`-Pvaultguard.firebaseProjectId=`,
 * or `~/.gradle/gradle.properties`), an environment variable (`VAULTGUARD_FIREBASE_PROJECT_ID`),
 * and — for the two Firebase values only — `app/google-services.json`, which is on disk
 * wherever the Android module builds. Anything still missing is written empty and must
 * then come from the user's file; `DesktopConfig` says which key.
 *
 * None of it is secret. The Firebase API key and project id are shipped in every APK by
 * design, and Google's own documentation says an installed app's OAuth client secret is
 * not treated as confidential. The feedback key only permits writing feedback.
 */
// Passed as `-Pvaultguard.version=1.2.3` by the release workflow; MSI wants three numbers.
val appVersion: String = (findProperty("vaultguard.version") as String?) ?: "1.0.0"

val bakedDefaults: Map<String, String> = run {
    fun setting(property: String, env: String): String? =
        (findProperty("vaultguard.$property") as String?)?.takeIf { it.isNotBlank() }
            ?: System.getenv(env)?.takeIf { it.isNotBlank() }

    val googleServices = rootProject.file("app/google-services.json")
    val fromGoogleServices: Map<String, String> = if (googleServices.isFile) {
        val json = groovy.json.JsonSlurper().parse(googleServices) as Map<*, *>
        val projectInfo = json["project_info"] as? Map<*, *>
        val firstClient = (json["client"] as? List<*>)?.firstOrNull() as? Map<*, *>
        val firstKey = (firstClient?.get("api_key") as? List<*>)?.firstOrNull() as? Map<*, *>
        mapOf(
            "projectId" to (projectInfo?.get("project_id") as? String).orEmpty(),
            "apiKey" to (firstKey?.get("current_key") as? String).orEmpty()
        )
    } else {
        emptyMap()
    }

    mapOf(
        "projectId" to (setting("firebaseProjectId", "VAULTGUARD_FIREBASE_PROJECT_ID") ?: fromGoogleServices["projectId"].orEmpty()),
        "apiKey" to (setting("firebaseApiKey", "VAULTGUARD_FIREBASE_API_KEY") ?: fromGoogleServices["apiKey"].orEmpty()),
        "oauthClientId" to setting("oauthClientId", "VAULTGUARD_OAUTH_CLIENT_ID").orEmpty(),
        "oauthClientSecret" to setting("oauthClientSecret", "VAULTGUARD_OAUTH_CLIENT_SECRET").orEmpty(),
        "feedbackUrl" to setting("feedbackUrl", "VAULTGUARD_FEEDBACK_URL").orEmpty(),
        "feedbackKey" to setting("feedbackKey", "VAULTGUARD_FEEDBACK_KEY").orEmpty(),
        "version" to appVersion
    )
}

val generatedResources = layout.buildDirectory.dir("generated/defaults")

val writeDefaults by tasks.registering {
    description = "Writes the baked configuration resource."
    val target = generatedResources.map { it.file("vaultguard-defaults.properties") }
    inputs.properties(bakedDefaults)
    outputs.file(target)
    doLast {
        val file = target.get().asFile
        file.parentFile.mkdirs()
        // Written by hand rather than through java.util.Properties, which prepends a
        // timestamp comment and would make every build's jar differ.
        file.writeText(
            bakedDefaults.entries.joinToString("") { (key, value) -> "$key=${escapeProperty(value)}\n" }
        )
    }
}

fun escapeProperty(value: String): String =
    value.replace("\\", "\\\\").replace("\n", "\\n").replace("\r", "").replace("=", "\\=").replace(":", "\\:")

sourceSets["main"].resources.srcDir(generatedResources)
tasks.named("processResources") { dependsOn(writeDefaults) }

/**
 * A native launcher, so Windows has something to name and draw.
 *
 * Without this the tray service is `javaw.exe` in Task Manager with a coffee cup beside it,
 * which is a poor look for a thing holding your passwords open. `jpackage` ships with the
 * JDK, so this needs no tooling that is not already here.
 *
 * Two tasks share one argument list:
 *
 * - `packageApp` — an `app-image`: a directory that can be run in place or copied, and
 *   nothing has to be installed to try it. What `--install-service --to` copies.
 * - `packageInstaller` — what a person downloads: an `.msi` on Windows, a `.dmg` on macOS,
 *   a `.deb` or `.rpm` on Linux. The MSI needs the WiX 3 toolset on the PATH and the RPM
 *   needs `rpmbuild`; the others need nothing beyond the JDK. Per user, with a Start Menu
 *   entry and an Apps & features entry so it can be removed the ordinary way.
 *
 * The image carries two launchers. `VaultGuard` is a windowed process — no console — and
 * starts the tray service when double-clicked. `vaultguard-cli` is the same program with a
 * console attached, for `--install-service`, `--cloud` and the rest; a windowed launcher
 * would run them silently and show nothing. It is also what the native-messaging wrapper
 * calls: browsers spawn hosts without a console window, so nothing flashes.
 *
 */

// Constant for the life of the product. Windows Installer uses it to recognise a newer
// MSI as an upgrade of the installed one and replace it in place; change it and every
// version installs beside the last.
val windowsUpgradeUuid = "ce432b49-ce4f-46de-a8d9-8f9fd0d2f94d"

val os: org.gradle.internal.os.OperatingSystem = org.gradle.internal.os.OperatingSystem.current()

// jpackage wants the icon in the platform's own format. The `.ico` is drawn by the app
// itself; macOS would need an `.icns`, which nothing here writes, so it takes the default.
val iconFile: File? = when {
    os.isWindows -> layout.buildDirectory.file("vaultguard.ico").get().asFile
    os.isLinux -> layout.buildDirectory.file("vaultguard.png").get().asFile
    else -> null
}

val writeIcon by tasks.registering(JavaExec::class) {
    description = "Generates the launcher icon from the same drawing the tray uses."
    dependsOn(tasks.named("installDist"))
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("com.vaultguard.desktop.MainKt")
    onlyIf { iconFile != null }
    args = listOf("--write-icon", (iconFile ?: File("unused")).absolutePath)
}

val cliLauncherProperties by tasks.registering {
    description = "Writes the jpackage properties for the console launcher."
    val file = layout.buildDirectory.file("vaultguard-cli.properties")
    outputs.file(file)
    doLast {
        file.get().asFile.writeText(
            listOf(
                // Without an explicit value the launcher inherits the main one's
                // `--service`, and a console-attached copy of the tray is not the point.
                "arguments=--help",
                "win-console=true",
                ""
            ).joinToString("\n")
        )
    }
}

fun jpackageArguments(type: String, dest: File): List<String> {
    val installDir = layout.buildDirectory.dir("install/vaultguard").get().asFile
    val common = listOf(
        "${System.getProperty("java.home")}/bin/jpackage",
        "--type", type,
        "--name", "VaultGuard",
        "--app-version", appVersion,
        "--vendor", "VaultGuard",
        "--description", "VaultGuard password vault",
        "--input", "$installDir/lib",
        "--main-jar", "desktop.jar",
        "--main-class", "com.vaultguard.desktop.MainKt",
        // The tray service is the only reason to double-click this.
        "--arguments", "--service",
        "--add-launcher",
        "vaultguard-cli=${layout.buildDirectory.file("vaultguard-cli.properties").get().asFile.absolutePath}",
        "--dest", dest.absolutePath
    )
    val icon = iconFile?.let { listOf("--icon", it.absolutePath) } ?: emptyList()
    val platform = when {
        type == "app-image" -> emptyList()
        os.isWindows -> listOf(
            "--win-per-user-install",
            "--win-dir-chooser",
            "--win-menu",
            "--win-menu-group", "VaultGuard",
            "--win-shortcut",
            "--win-upgrade-uuid", windowsUpgradeUuid
        )
        os.isMacOsX -> listOf(
            "--mac-package-identifier", "com.vaultguard.desktop",
            "--mac-package-name", "VaultGuard"
        )
        else -> listOf(
            "--linux-package-name", "vaultguard",
            "--linux-shortcut",
            "--linux-menu-group", "Utility",
            "--linux-app-category", "utils"
        )
    }
    return common + icon + platform
}

val packageApp by tasks.registering(Exec::class) {
    description = "Builds the VaultGuard app image with jpackage."
    group = "distribution"
    dependsOn(tasks.named("installDist"), writeIcon, cliLauncherProperties)

    val outputDir = layout.buildDirectory.dir("native").get().asFile

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

    commandLine(jpackageArguments("app-image", outputDir))
}

val packageInstaller by tasks.registering(Exec::class) {
    description = "Builds the installer for this platform (msi, dmg, deb or rpm) with jpackage."
    group = "distribution"
    dependsOn(tasks.named("installDist"), writeIcon, cliLauncherProperties)

    val outputDir = layout.buildDirectory.dir("installer").get().asFile
    // Linux has two package formats and one runner builds both, so the type can be named:
    // `-Pvaultguard.installerType=rpm`. Needs `rpm-build` (the `rpm` package on Debian).
    val type = (findProperty("vaultguard.installerType") as String?) ?: when {
        os.isWindows -> "msi"
        os.isMacOsX -> "dmg"
        else -> "deb"
    }

    doFirst {
        outputDir.deleteRecursively()
        outputDir.mkdirs()
        // jpackage's own message when WiX is missing is "Can not find WiX tools", with no
        // hint of what to install. Checked first, and named.
        val path = System.getenv("PATH").orEmpty().split(File.pathSeparator)
        if (type == "msi" && path.none { File(it, "light.exe").exists() && File(it, "candle.exe").exists() }) {
            throw GradleException(
                "Building an .msi needs the WiX 3 toolset (candle.exe and light.exe) on the " +
                    "PATH, and it is not there. Install WiX 3.14 and add its bin directory."
            )
        }
        if (type == "rpm" && path.none { File(it, "rpmbuild").exists() }) {
            throw GradleException(
                "Building an .rpm needs rpmbuild on the PATH. On Debian or Ubuntu: apt-get install rpm."
            )
        }
    }

    commandLine(jpackageArguments(type, outputDir))
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
