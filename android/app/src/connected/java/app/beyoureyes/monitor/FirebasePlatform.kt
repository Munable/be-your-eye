package app.beyoureyes.monitor

import android.content.Context
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailability
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import com.google.firebase.messaging.FirebaseMessaging

internal data class OptionalFirebaseConfiguration(
    val apiKey: String,
    val applicationId: String,
    val projectId: String,
    val gcmSenderId: String,
) {
    val enabled: Boolean
        get() = listOf(apiKey, applicationId, projectId, gcmSenderId).all(String::isNotBlank)

    fun initialize(context: Context): Boolean {
        if (!enabled) return false
        if (runCatching { FirebaseApp.getInstance() }.isSuccess) return true
        val options = FirebaseOptions.Builder()
            .setApiKey(apiKey)
            .setApplicationId(applicationId)
            .setProjectId(projectId)
            .setGcmSenderId(gcmSenderId)
            .build()
        FirebaseApp.initializeApp(context, options)
        return true
    }
    fun setPushEnabled(context: Context, enabled: Boolean) {
        if (enabled && initialize(context)) {
            FirebaseMessaging.getInstance().apply { setAutoInitEnabled(true); register() }
        } else if (runCatching { FirebaseApp.getInstance() }.isSuccess) {
            FirebaseMessaging.getInstance().setAutoInitEnabled(false)
        }
    }
}

internal fun hasUsableGooglePlayServices(context: Context): Boolean = runCatching {
    isUsableGooglePlayServicesStatus(GoogleApiAvailability.getInstance().isGooglePlayServicesAvailable(context))
}.getOrDefault(false)
