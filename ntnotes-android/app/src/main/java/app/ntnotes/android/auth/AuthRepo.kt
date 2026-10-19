package app.ntnotes.android.auth

import android.content.Context
import androidx.credentials.ClearCredentialStateRequest
import androidx.credentials.CredentialManager
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.credentials.exceptions.GetCredentialException
import androidx.credentials.exceptions.NoCredentialException
import com.google.android.gms.tasks.Task
import com.google.android.libraries.identity.googleid.GetSignInWithGoogleOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import com.google.firebase.FirebaseNetworkException
import com.google.firebase.FirebaseTooManyRequestsException
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseAuthInvalidCredentialsException
import com.google.firebase.auth.FirebaseAuthInvalidUserException
import com.google.firebase.auth.FirebaseAuthUserCollisionException
import com.google.firebase.auth.FirebaseAuthWeakPasswordException
import com.google.firebase.auth.GoogleAuthProvider
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Thrown with a message that is safe to show to the user. */
class AuthFailure(message: String) : Exception(message)

/** Same Firebase Auth project as the desktop app, so one account works on both. */
object AuthRepo {
    private val auth: FirebaseAuth get() = FirebaseAuth.getInstance()

    val email: String? get() = auth.currentUser?.email

    suspend fun signIn(email: String, password: String) {
        requireFields(email, password)
        guarded { auth.signInWithEmailAndPassword(email.trim(), password).await() }
    }

    suspend fun signUp(email: String, password: String) {
        requireFields(email, password)
        guarded { auth.createUserWithEmailAndPassword(email.trim(), password).await() }
    }

    suspend fun sendPasswordReset(email: String) {
        if (email.isBlank()) throw AuthFailure("Please enter your email.")
        guarded { auth.sendPasswordResetEmail(email.trim()).await() }
    }

    /** Google account chooser (Credential Manager) -> Firebase. [activity] must be an Activity context. */
    suspend fun signInWithGoogle(activity: Context) {
        val res = activity.resources
        val id = res.getIdentifier("default_web_client_id", "string", activity.packageName)
        if (id == 0) {
            throw AuthFailure(
                "Google sign-in is not set up yet: enable Google in Firebase > Authentication, add this app's " +
                    "SHA-1 in Project settings, then download google-services.json again and rebuild."
            )
        }
        val serverClientId = res.getString(id)
        try {
            val option = GetSignInWithGoogleOption.Builder(serverClientId).build()
            val request = GetCredentialRequest.Builder().addCredentialOption(option).build()
            val result = CredentialManager.create(activity).getCredential(activity, request)
            val cred = result.credential
            if (cred is CustomCredential && cred.type == GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL) {
                val token = GoogleIdTokenCredential.createFrom(cred.data).idToken
                auth.signInWithCredential(GoogleAuthProvider.getCredential(token, null)).await()
            } else {
                throw AuthFailure("Google sign-in returned an unexpected result.")
            }
        } catch (e: GetCredentialCancellationException) {
            // The user closed the chooser: not an error.
        } catch (e: NoCredentialException) {
            throw AuthFailure("No Google account found on this phone. Add one in Settings > Accounts, then try again.")
        } catch (e: GetCredentialException) {
            throw AuthFailure("Google sign-in failed: " + (e.message ?: e.javaClass.simpleName))
        } catch (e: AuthFailure) {
            throw e
        } catch (e: Exception) {
            throw AuthFailure(friendly(e))
        }
    }

    suspend fun signOut(context: Context) {
        auth.signOut()
        try {
            CredentialManager.create(context).clearCredentialState(ClearCredentialStateRequest())
        } catch (_: Throwable) {
        }
    }

    // ------------------------------------------------------------------ helpers

    private fun requireFields(email: String, password: String) {
        if (email.isBlank()) throw AuthFailure("Please enter your email.")
        if (password.isEmpty()) throw AuthFailure("Please enter your password.")
    }

    private suspend fun guarded(block: suspend () -> Unit) {
        try {
            block()
        } catch (e: AuthFailure) {
            throw e
        } catch (e: Exception) {
            throw AuthFailure(friendly(e))
        }
    }

    private fun friendly(e: Exception): String = when (e) {
        is FirebaseAuthWeakPasswordException -> "Password is too weak. Use at least 6 characters."
        is FirebaseAuthUserCollisionException -> "This email is already registered. Try signing in instead."
        is FirebaseAuthInvalidUserException ->
            if (e.errorCode == "ERROR_USER_DISABLED") "This account has been disabled."
            else "No account exists for this email."
        is FirebaseAuthInvalidCredentialsException ->
            if (e.errorCode == "ERROR_INVALID_EMAIL") "That email address is not valid."
            else "Incorrect email or password."
        is FirebaseTooManyRequestsException -> "Too many attempts. Please wait a while and try again."
        is FirebaseNetworkException -> "No internet connection."
        else -> e.message ?: "Something went wrong."
    }
}

private suspend fun <T> Task<T>.await(): T = suspendCancellableCoroutine { cont ->
    addOnCompleteListener { t ->
        if (t.isSuccessful) {
            @Suppress("UNCHECKED_CAST")
            cont.resume(t.result as T)
        } else {
            cont.resumeWithException(t.exception ?: Exception("Unknown error"))
        }
    }
}
