package app.beyoureyes.monitor

import android.content.Context
import android.view.OrientationEventListener
import android.view.Surface
import kotlin.coroutines.resume
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/** Use the same physical orientation as setup even when starting without a preview. */
internal suspend fun currentCameraTargetRotation(context: Context): Int =
    withContext(Dispatchers.Main.immediate) {
        withTimeoutOrNull(1_000L) {
            suspendCancellableCoroutine { continuation ->
                val listener = object : OrientationEventListener(context) {
                    override fun onOrientationChanged(orientation: Int) {
                        val rotation = surfaceRotationForPhysicalOrientation(orientation) ?: return
                        disable()
                        if (continuation.isActive) continuation.resume(rotation)
                    }
                }
                continuation.invokeOnCancellation { listener.disable() }
                if (listener.canDetectOrientation()) {
                    listener.enable()
                } else {
                    continuation.resume(Surface.ROTATION_0)
                }
            }
        } ?: Surface.ROTATION_0
    }
