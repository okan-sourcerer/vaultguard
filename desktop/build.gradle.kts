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
}
