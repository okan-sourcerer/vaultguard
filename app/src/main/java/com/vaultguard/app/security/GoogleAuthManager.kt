package com.vaultguard.app.security

import android.content.Context
import android.content.Intent
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInClient
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseUser
import com.google.firebase.auth.GoogleAuthProvider
import com.vaultguard.app.R
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.tasks.await
import javax.inject.Inject
import javax.inject.Singleton

sealed class GoogleSignInResult {
    data class Success(val user: FirebaseUser) : GoogleSignInResult()
    data class Error(val message: String) : GoogleSignInResult()
}

@Singleton
class GoogleAuthManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val auth: FirebaseAuth
) {

    private val googleSignInClient: GoogleSignInClient by lazy {
        val gso = GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
            .requestIdToken(context.getString(R.string.default_web_client_id))
            .requestEmail()
            .build()
        GoogleSignIn.getClient(context, gso)
    }

    val isSignedInWithGoogle: Boolean
        get() = auth.currentUser?.providerData?.any { it.providerId == "google.com" } == true

    val isAnonymous: Boolean
        get() = auth.currentUser?.isAnonymous == true

    val currentUserEmail: String?
        get() = auth.currentUser?.email

    val currentUserDisplayName: String?
        get() = auth.currentUser?.displayName

    /**
     * Returns the Intent to launch the Google Sign-In flow.
     * The caller should use ActivityResultLauncher to start this intent.
     */
    fun getSignInIntent(): Intent = googleSignInClient.signInIntent

    /**
     * Handles the result from Google Sign-In activity.
     * If the current user is anonymous, links the Google credential to migrate data.
     * Otherwise, signs in directly with Google.
     *
     * @return the old anonymous UID if migration happened (caller should move Firestore data),
     *         or null if no migration was needed.
     */
    suspend fun handleSignInResult(data: Intent?): Pair<GoogleSignInResult, String?> {
        return try {
            val task = GoogleSignIn.getSignedInAccountFromIntent(data)
            val account = task.await()
            val idToken = account.idToken
                ?: return Pair(GoogleSignInResult.Error("No ID token received"), null)

            val credential = GoogleAuthProvider.getCredential(idToken, null)
            val currentUser = auth.currentUser

            if (currentUser != null && currentUser.isAnonymous) {
                // Link anonymous account with Google — preserves the same UID
                try {
                    val result = currentUser.linkWithCredential(credential).await()
                    Pair(GoogleSignInResult.Success(result.user!!), null)
                } catch (linkError: Exception) {
                    // Link failed — likely because Google account already exists in Firebase.
                    // Sign in with Google directly and return old UID for data migration.
                    val oldUid = currentUser.uid
                    val result = auth.signInWithCredential(credential).await()
                    Pair(GoogleSignInResult.Success(result.user!!), oldUid)
                }
            } else {
                // Not anonymous — just sign in with Google
                val result = auth.signInWithCredential(credential).await()
                Pair(GoogleSignInResult.Success(result.user!!), null)
            }
        } catch (e: Exception) {
            Pair(GoogleSignInResult.Error(e.message ?: "Google Sign-In failed"), null)
        }
    }

    /**
     * Signs out from Google and Firebase, then signs in anonymously.
     */
    suspend fun signOutAndGoAnonymous() {
        googleSignInClient.signOut().await()
        auth.signOut()
        auth.signInAnonymously().await()
    }
}
