package app.beyoureyes.monitor

import android.content.Context

@Suppress("UNUSED_PARAMETER")
internal data class OptionalFirebaseConfiguration(
    val apiKey: String, val applicationId: String, val projectId: String, val gcmSenderId: String,
) {
    val enabled: Boolean = false
    fun initialize(context: Context): Boolean = false
    fun setPushEnabled(context: Context, enabled: Boolean) = Unit
}
@Suppress("UNUSED_PARAMETER")
internal fun hasUsableGooglePlayServices(context: Context): Boolean = false
