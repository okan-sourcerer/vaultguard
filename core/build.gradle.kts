plugins {
    alias(libs.plugins.kotlin.jvm)
    // FakeSecurePrefs is shared: :core's own tests use it, and :app's tests still need it
    // for UnlockVaultUseCase, ChangeMasterPasswordUseCase and CredentialRepositoryImpl.
    // A fixture rather than a second copy — two fakes drifting apart is how the looser one
    // ends up guarding the more sensitive path.
    `java-test-fixtures`
}

// The pure-JVM half of VaultGuard: key derivation, AES-GCM, the credential payload
// contract, the backup format, the generator, and the sync merge rules.
//
// Nothing here may reference Android. That is the point — a desktop client links the
// same classes, so the two cannot disagree about how a key is derived or a payload is
// shaped. The golden-vector test lives here and guards one implementation, not two.
//
// Package names are unchanged from when this code lived in :app, so the move was a
// file move rather than a refactor.

java {
    // Matches :app's compileOptions. A wider target here would compile fine and then
    // fail to dex.
    sourceCompatibility = JavaVersion.VERSION_11
    targetCompatibility = JavaVersion.VERSION_11
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_11)
    }
}

dependencies {
    // Flow appears in CredentialRepository's signature, so consumers need it: api.
    api(libs.kotlinx.coroutines.core)
    // @Inject/@Singleton are on public constructors.
    api(libs.javax.inject)

    implementation(libs.bouncycastle)

    // Android ships org.json in the framework, so :app must NOT get an implementation
    // copy in its APK — that would put a second org.json on the runtime classpath.
    // compileOnly here, real jar for tests, and a desktop consumer declares its own.
    compileOnly(libs.json)

    testImplementation(libs.junit)
    testImplementation(libs.json)
    testImplementation(libs.kotlinx.coroutines.test)
}
