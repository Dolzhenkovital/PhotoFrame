package com.smartphonekey.photoframe.gphotos

import android.app.Activity
import android.content.Context
import androidx.activity.ComponentActivity
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailability
import com.google.android.gms.common.api.ApiException
import com.google.android.gms.common.api.Scope

/**
 * OAuth access token for the Picker scope via Google Identity Services.
 * No client secrets in the app: the OAuth client is matched by package name
 * + signing SHA-1 configured in the Google Cloud console
 * (google-photos-picker skill, "Google Cloud setup").
 *
 * Construct in Activity.onCreate (registers an ActivityResult launcher).
 */
class GoogleAuth(
    private val activity: ComponentActivity,
    private val onToken: (String) -> Unit,
    private val onError: (String?) -> Unit,
) {

    private val consentLauncher = activity.registerForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult()
    ) { result ->
        val data = result.data
        if (result.resultCode == Activity.RESULT_OK && data != null) {
            try {
                val auth = Identity.getAuthorizationClient(activity)
                    .getAuthorizationResultFromIntent(data)
                val token = auth.accessToken
                if (token != null) onToken(token) else onError(null)
            } catch (e: ApiException) {
                onError(e.message)
            }
        } else {
            onError(null) // user backed out of the consent screen
        }
    }

    /** Silent when already granted; shows Google's consent UI otherwise. */
    fun requestAccess() {
        val request = AuthorizationRequest.builder()
            .setRequestedScopes(listOf(Scope(PICKER_SCOPE)))
            .build()
        Identity.getAuthorizationClient(activity)
            .authorize(request)
            .addOnSuccessListener { result ->
                if (result.hasResolution()) {
                    val pendingIntent = result.pendingIntent
                    if (pendingIntent == null) {
                        onError(null)
                        return@addOnSuccessListener
                    }
                    try {
                        consentLauncher.launch(
                            IntentSenderRequest.Builder(pendingIntent.intentSender).build()
                        )
                    } catch (e: Exception) {
                        onError(e.message)
                    }
                } else {
                    val token = result.accessToken
                    if (token != null) onToken(token) else onError(null)
                }
            }
            .addOnFailureListener { e -> onError(e.message) }
    }

    companion object {
        private const val PICKER_SCOPE =
            "https://www.googleapis.com/auth/photospicker.mediaitems.readonly"

        /** Frames without Play services can still use the local source. */
        fun isPlayServicesAvailable(context: Context): Boolean =
            GoogleApiAvailability.getInstance()
                .isGooglePlayServicesAvailable(context) == ConnectionResult.SUCCESS
    }
}
