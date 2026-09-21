package app.beyoureyes.core.data

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters

/**
 * VM-013 supplies the authenticated uploader. Until then, this worker fails explicitly instead of
 * deleting or pretending to deliver queued events.
 */
class EventUploadWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result = Result.failure()
}
