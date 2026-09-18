plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
    alias(libs.plugins.google.gms.google.services)
}

android {
    namespace = "com.vaultguard.app"
    compileSdk {
        version = release(36)
    }

    defaultConfig {
        applicationId = "com.vaultguard.app"
        minSdk = 28
        targetSdk = 36
        // The release workflow passes both: the tag for the name, the run number for the
        // code, so every CI build is newer than the last whatever the tag says.
        versionCode = (findProperty("vaultguard.versionCode") as String?)?.toInt() ?: 1
        versionName = (findProperty("vaultguard.version") as String?) ?: "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // The feedback hub. Empty in a build without them, and Settings then shows no
        // "Send feedback" entry. The key permits writing feedback and nothing else, which
        // is why it can sit in a distributed APK. Same names as the desktop build takes.
        fun setting(property: String, env: String): String =
            (findProperty("vaultguard.$property") as String?)?.takeIf { it.isNotBlank() }
                ?: System.getenv(env).orEmpty()
        buildConfigField("String", "FEEDBACK_URL", "\"${setting("feedbackUrl", "VAULTGUARD_FEEDBACK_URL")}\"")
        buildConfigField("String", "FEEDBACK_KEY", "\"${setting("feedbackKey", "VAULTGUARD_FEEDBACK_KEY")}\"")
    }

    // Release signing material comes from the environment and is never on disk in the
    // repository: the workflow writes the keystore from a secret and points these at it.
    // Absent, release builds fall back to the debug key below so a minified build can
    // still be put on the owner's phone - which was signed with that key and would
    // refuse an upgrade signed with any other (see the note on the release block).
    val releaseKeystore = System.getenv("VAULTGUARD_KEYSTORE")?.let(::file)?.takeIf { it.isFile }
    if (releaseKeystore != null) {
        signingConfigs.create("release") {
            storeFile = releaseKeystore
            storePassword = System.getenv("VAULTGUARD_KEYSTORE_PASSWORD")
            keyAlias = System.getenv("VAULTGUARD_KEY_ALIAS")
            keyPassword = System.getenv("VAULTGUARD_KEY_PASSWORD")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )

            // Locally, signed with the debug key so a minified build can actually be
            // installed and exercised. R8 breakage — a missing keep rule for a
            // reflectively-loaded class, say — cannot be found any other way: it never
            // reproduces in a debug build, and an unsigned APK cannot be installed to try.
            //
            // In CI, signed with the release keystore. Android identifies an app by its
            // signing certificate, so a CI-built APK will not install over a debug-signed
            // one, and vice versa. The phone that already holds the vault stays on the key
            // it was installed with; never uninstall to switch (CLAUDE.md, "Commands").
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    packaging {
        jniLibs {
            useLegacyPackaging = false
        }
    }
    testOptions {
        unitTests {
            isIncludeAndroidResources = true
            all { it.testLogging { events("passed", "skipped", "failed") } }
        }
    }
    // Lets MigrationTestHelper open the committed v1 schema on-device.
    sourceSets.getByName("androidTest") {
        assets.srcDir("$projectDir/schemas")
    }
}

ksp {
    arg("dagger.fastInit", "enabled")
    // Commit the generated schema JSON so migrations can be tested against the real
    // historical shape rather than one reconstructed by hand.
    arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {
    // Crypto, the payload contract, the backup format, the generator, the merge rules.
    // Shared with any desktop client so the two cannot disagree — see core/build.gradle.kts.
    implementation(project(":core"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)

    // Navigation
    implementation(libs.androidx.navigation.compose)

    // Hilt
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.androidx.hilt.navigation.compose)

    // Room + SQLCipher
    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)
    implementation(libs.sqlcipher)
    implementation(libs.androidx.sqlite.ktx)

    // Firebase
    implementation(platform(libs.firebase.bom))
    implementation(libs.firebase.firestore.ktx)
    implementation(libs.firebase.auth.ktx)
    implementation(libs.play.services.auth)

    // Security
    implementation(libs.security.crypto)
    implementation(libs.bouncycastle)

    // Biometric
    implementation(libs.biometric)

    // Autofill (inline suggestions)
    implementation(libs.androidx.autofill)

    // Coroutines
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.coroutines.play.services)

    // Lifecycle
    implementation(libs.lifecycle.process)

    // WorkManager
    implementation(libs.work.runtime.ktx)

    // Logging
    implementation(libs.timber)

    // Test
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.mockk)
    testImplementation(libs.json)
    testImplementation(libs.bouncycastle)
    testImplementation(testFixtures(project(":core")))
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.room.testing)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}
