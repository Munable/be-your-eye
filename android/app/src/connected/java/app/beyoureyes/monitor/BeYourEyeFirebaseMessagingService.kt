package app.beyoureyes.monitor

import android.annotation.SuppressLint
import app.beyoureyes.core.data.cloud.PushEnvelope
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage

// Firebase Messaging 25.1+ replaces the deprecated token callback with the
// manifest-enabled FID registration callbacks below. AGP 9.3 lint has not yet
// learned that mutually exclusive API, so suppress only its legacy-token rule.
@SuppressLint("MissingFirebaseInstanceTokenRefresh")
class BeYourEyeFirebaseMessagingService : FirebaseMessagingService() {
    override fun onRegistered(installationId: String) {
        CloudWorkScheduler.enqueueToken(this, installationId)
    }

    override fun onUnregistered(installationId: String) {
        CloudWorkScheduler.enqueueTokenRemoval(this)
    }

    override fun onMessageReceived(message: RemoteMessage) {
        val envelope = PushEnvelope.parse(message.data) ?: return
        CloudWorkScheduler.enqueuePush(this, envelope)
    }

    override fun onDeletedMessages() {
        CloudWorkScheduler.enqueueSync(this)
    }
}
