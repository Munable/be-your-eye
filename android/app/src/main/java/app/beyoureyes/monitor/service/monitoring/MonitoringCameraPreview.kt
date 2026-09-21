package app.beyoureyes.monitor.service.monitoring

import androidx.camera.core.Preview

/**
 * In-process bridge between the camera-owning foreground service and the currently visible
 * monitoring route.
 *
 * ImageAnalysis always remains owned by [MonitoringService]. A visible screen may attach one
 * CameraX preview surface, detach it for black-screen monitoring, and attach a new surface after
 * returning from Home. No camera frame is copied into app state or storage.
 */
internal object MonitoringCameraPreview {
    private val lock = Any()
    private var boundPreview: Preview? = null
    private var attachedSurface: Preview.SurfaceProvider? = null

    fun attach(surfaceProvider: Preview.SurfaceProvider) = synchronized(lock) {
        if (attachedSurface === surfaceProvider) return@synchronized
        attachedSurface = surfaceProvider
        boundPreview?.setSurfaceProvider(surfaceProvider)
    }

    fun detach(surfaceProvider: Preview.SurfaceProvider) = synchronized(lock) {
        if (attachedSurface !== surfaceProvider) return@synchronized
        attachedSurface = null
        boundPreview?.setSurfaceProvider(null)
    }

    fun bind(preview: Preview) = synchronized(lock) {
        check(boundPreview == null || boundPreview === preview)
        boundPreview = preview
        attachedSurface?.let(preview::setSurfaceProvider)
    }

    fun unbind(preview: Preview) = synchronized(lock) {
        if (boundPreview !== preview) return@synchronized
        preview.setSurfaceProvider(null)
        boundPreview = null
    }
}
