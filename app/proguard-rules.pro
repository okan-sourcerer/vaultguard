# R8 rules for the minified release build.
#
# The previous version of this file kept two packages the app has never contained and
# missed one it genuinely needs, so a release build was not known to work (finding #41).
# Everything below is either justified by reflection or by JNI; anything reached by
# ordinary calls needs no rule, because R8 can see it.

# ---------------------------------------------------------------------------------------
# SQLCipher
#
# The artifact is net.zetetic:sqlcipher-android, whose classes live in
# net.zetetic.database. The old rule kept net.sqlcipher.** — the package name of the
# *older* SQLCipher artifact, which is not on this classpath, so it kept nothing at all.
#
# These are reached from native code, which resolves classes, methods and fields by name,
# so R8 cannot see the references and must be told.
-keep class net.zetetic.database.** { *; }
-dontwarn net.zetetic.database.**

# ---------------------------------------------------------------------------------------
# Room
#
# Room finds its generated implementation with Class.forName(name + "_Impl").
-keep class * extends androidx.room.RoomDatabase { *; }
-keep @androidx.room.Entity class * { *; }
-dontwarn androidx.room.paging.**

# ---------------------------------------------------------------------------------------
# WorkManager
#
# Workers are stored as class-name strings and instantiated reflectively through this
# exact constructor. Nothing in the code refers to ClearClipboardWorker by type, so
# without this rule R8 removes it and the clipboard is never cleared in a release build —
# a failure that cannot happen in debug, which is precisely why it was worth finding.
-keep class * extends androidx.work.ListenableWorker {
    <init>(android.content.Context, androidx.work.WorkerParameters);
}

# ---------------------------------------------------------------------------------------
# BouncyCastle
#
# Argon2BytesGenerator and Argon2Parameters are constructed directly, so R8 keeps them
# without help — the old rule pointed at org.signal.argon2, a library this app has never
# used. bcprov references optional JCE and naming classes that are absent on Android;
# silence those rather than keeping the whole provider, which is large.
-dontwarn org.bouncycastle.**
-dontwarn javax.naming.**

# ---------------------------------------------------------------------------------------
# Firebase
#
# Firestore's reflective POJO mapping is not used here: documents are read and written as
# maps and typed getters. The Firebase artifacts ship their own consumer rules, so a
# blanket keep only inflates the APK.
-dontwarn com.google.firebase.**

# ---------------------------------------------------------------------------------------
# Hilt and Kotlin
-dontwarn dagger.hilt.**
-dontwarn kotlinx.coroutines.**

# ---------------------------------------------------------------------------------------
# Crash reports stay readable. Retrace against the mapping file in build/outputs/mapping.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# ---------------------------------------------------------------------------------------
# Strip verbose and debug logging. Timber plants no tree in release, so its calls are
# already inert; these cover the android.util.Log calls that remain.
-assumenosideeffects class android.util.Log {
    public static int v(...);
    public static int d(...);
}
