package app.beyoureyes.monitor

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import app.beyoureyes.core.data.cloud.PushEnvelope
import java.time.Duration
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import app.beyoureyes.monitor.feature.subscription.isGranted

internal object CloudWorkScheduler {
    private val networkConstraint = Constraints.Builder()
        .setRequiredNetworkType(NetworkType.CONNECTED)
        .build()

    fun enqueueSync(context: Context) {
        val request = OneTimeWorkRequestBuilder<OptionalCloudWorker>()
            .setConstraints(networkConstraint)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .setInputData(workDataOf(KEY_MODE to MODE_SYNC))
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(
            UNIQUE_SYNC,
            ExistingWorkPolicy.KEEP,
            request,
        )
    }

    fun enqueuePush(context: Context, envelope: PushEnvelope) {
        val request = OneTimeWorkRequestBuilder<OptionalCloudWorker>()
            .setConstraints(networkConstraint)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .setInputData(
                workDataOf(
                    KEY_MODE to MODE_PUSH,
                    KEY_EVENT_ID to envelope.eventId,
                    KEY_CURSOR_HINT to envelope.cursorHint,
                ),
            )
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(
            "cloud-push-${envelope.eventId}",
            ExistingWorkPolicy.KEEP,
            request,
        )
    }

    fun enqueueToken(context: Context, token: String) {
        if (token.length !in 20..4096) return
        val request = OneTimeWorkRequestBuilder<OptionalCloudWorker>()
            .setConstraints(networkConstraint)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .setInputData(workDataOf(KEY_MODE to MODE_TOKEN, KEY_TOKEN to token))
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(
            UNIQUE_TOKEN,
            ExistingWorkPolicy.REPLACE,
            request,
        )
    }

    fun enqueueTokenRemoval(context: Context) {
        val request = OneTimeWorkRequestBuilder<OptionalCloudWorker>()
            .setConstraints(networkConstraint)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .setInputData(workDataOf(KEY_MODE to MODE_TOKEN_REMOVED))
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(
            UNIQUE_TOKEN,
            ExistingWorkPolicy.REPLACE,
            request,
        )
    }

    fun ensurePeriodicSync(context: Context) {
        val request = PeriodicWorkRequestBuilder<OptionalCloudWorker>(Duration.ofMinutes(15))
            .setConstraints(networkConstraint)
            .setInputData(workDataOf(KEY_MODE to MODE_SYNC))
            .build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            UNIQUE_PERIODIC_SYNC,
            ExistingPeriodicWorkPolicy.KEEP,
            request,
        )
    }

    fun cancelAccountSync(context: Context) {
        WorkManager.getInstance(context).apply {
            cancelUniqueWork(UNIQUE_SYNC)
            cancelUniqueWork(UNIQUE_PERIODIC_SYNC)
        }
    }

    internal const val KEY_MODE = "mode"
    internal const val KEY_EVENT_ID = "event_id"
    internal const val KEY_CURSOR_HINT = "cursor_hint"
    internal const val KEY_TOKEN = "token"
    internal const val MODE_SYNC = "sync"
    internal const val MODE_PUSH = "push"
    internal const val MODE_TOKEN = "token"
    internal const val MODE_TOKEN_REMOVED = "token_removed"
    internal const val UNIQUE_SYNC = "optional-cloud-sync-v1"
    private const val UNIQUE_TOKEN = "optional-cloud-token-v1"
    private const val UNIQUE_PERIODIC_SYNC = "optional-cloud-periodic-sync-v1"
}

internal class OptionalCloudWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result = try {
        val coordinator = CloudBootstrap.coordinator(applicationContext)
        val mode = inputData.getString(CloudWorkScheduler.KEY_MODE)
        if (mode != CloudWorkScheduler.MODE_TOKEN_REMOVED &&
            !applicationContext.appContainer.currentProductAccess().isGranted()
        ) {
            return Result.success()
        }
        when (mode) {
            CloudWorkScheduler.MODE_SYNC -> {
                coordinator.sync()
                CloudBootstrap.snapshotTransfers(applicationContext).reconcileCache()
            }
            CloudWorkScheduler.MODE_PUSH -> {
                val envelope = runCatching {
                    PushEnvelope(
                        eventId = inputData.getString(CloudWorkScheduler.KEY_EVENT_ID).orEmpty(),
                        cursorHint =
                            inputData.getString(CloudWorkScheduler.KEY_CURSOR_HINT).orEmpty(),
                    )
                }.getOrNull() ?: return Result.failure()
                coordinator.processPush(envelope)
            }
            CloudWorkScheduler.MODE_TOKEN -> {
                val token = inputData.getString(CloudWorkScheduler.KEY_TOKEN)
                    ?: return Result.failure()
                coordinator.registerLatestPushToken(token)
            }
            CloudWorkScheduler.MODE_TOKEN_REMOVED -> {
                coordinator.unregisterCurrentPushToken(clearLocalToken = true)
            }
            else -> return Result.failure()
        }
        Result.success()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: IllegalArgumentException) {
        Result.failure()
    } catch (_: Throwable) {
        if (runAttemptCount < 5) Result.retry() else Result.failure()
    }
}
