package app.beyoureyes.monitor

import kotlinx.coroutines.CompletableDeferred

/** One in-memory decision for the exact signed package selected by preparation. */
class ModelDownloadRequest(
    val modelName: String,
    val purpose: String,
    val totalBytes: Long,
    val meteredNetwork: Boolean,
) {
    private val confirmation = CompletableDeferred<Unit>()

    init { require(totalBytes > 0) }

    fun confirm() { confirmation.complete(Unit) }

    internal suspend fun awaitConfirmation() { confirmation.await() }
}
