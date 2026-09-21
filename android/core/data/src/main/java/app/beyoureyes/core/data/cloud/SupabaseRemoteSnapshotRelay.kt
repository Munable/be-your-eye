package app.beyoureyes.core.data.cloud

import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.realtime.RealtimeChannel
import io.github.jan.supabase.realtime.broadcastFlow
import io.github.jan.supabase.realtime.channel
import io.github.jan.supabase.realtime.realtime
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject

internal class SupabaseRemoteSnapshotRelay(
    private val client: SupabaseClient,
    private val json: Json,
) : RemoteSnapshotRelay {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val operationMutex = Mutex()
    private val mutableRequests = MutableSharedFlow<RemoteSnapshotRequest>(extraBufferCapacity = 16)
    private val mutableResponses = MutableSharedFlow<RemoteSnapshotResponse>(extraBufferCapacity = 16)
    private var connection: Connection? = null

    override val configured: Boolean = true
    override val requests: Flow<RemoteSnapshotRequest> = mutableRequests
    override val responses: Flow<RemoteSnapshotResponse> = mutableResponses

    override suspend fun connect(accountId: String) = operationMutex.withLock {
        requireAccountUuid(accountId)
        val existing = connection
        if (existing?.accountId == accountId && existing.channel.status.value == RealtimeChannel.Status.SUBSCRIBED) {
            return@withLock
        }
        closeLocked()
        val channel = client.channel(snapshotRelayTopic(accountId)) {
            isPrivate = true
            broadcast {
                acknowledgeBroadcasts = true
                receiveOwnBroadcasts = false
            }
        }
        val requestFlow = channel.broadcastFlow<RemoteSnapshotRequest>(REQUEST_EVENT)
        val responseFlow = channel.broadcastFlow<RemoteSnapshotResponse>(RESPONSE_EVENT)
        val requestJob = scope.launch { requestFlow.collect(mutableRequests::emit) }
        val responseJob = scope.launch { responseFlow.collect(mutableResponses::emit) }
        try {
            channel.subscribe(blockUntilSubscribed = true)
            connection = Connection(accountId, channel, requestJob, responseJob)
        } catch (failure: Throwable) {
            requestJob.cancelAndJoin()
            responseJob.cancelAndJoin()
            runCatching { client.realtime.removeChannel(channel) }
            throw failure
        }
    }

    override suspend fun disconnect() = operationMutex.withLock { closeLocked() }

    override suspend fun send(request: RemoteSnapshotRequest) {
        val channel = requireSubscribedChannel()
        channel.broadcast(
            REQUEST_EVENT,
            json.encodeToJsonElement(RemoteSnapshotRequest.serializer(), request).jsonObject,
        )
    }

    override suspend fun send(response: RemoteSnapshotResponse) {
        val channel = requireSubscribedChannel()
        channel.broadcast(
            RESPONSE_EVENT,
            json.encodeToJsonElement(RemoteSnapshotResponse.serializer(), response).jsonObject,
        )
    }

    private suspend fun requireSubscribedChannel(): RealtimeChannel {
        val current = operationMutex.withLock { connection }
            ?: throw CloudUnavailableException("snapshot relay is disconnected")
        if (current.channel.status.value != RealtimeChannel.Status.SUBSCRIBED) {
            connect(current.accountId)
        }
        return operationMutex.withLock { connection?.channel }
            ?: throw CloudUnavailableException("snapshot relay is disconnected")
    }

    private suspend fun closeLocked() {
        val current = connection ?: return
        connection = null
        current.requestJob.cancelAndJoin()
        current.responseJob.cancelAndJoin()
        runCatching { client.realtime.removeChannel(current.channel) }
    }

    private data class Connection(
        val accountId: String,
        val channel: RealtimeChannel,
        val requestJob: Job,
        val responseJob: Job,
    )

    private companion object {
        const val REQUEST_EVENT = "snapshot_request"
        const val RESPONSE_EVENT = "snapshot_response"
    }
}

internal object DisabledRemoteSnapshotRelay : RemoteSnapshotRelay {
    override val configured: Boolean = false
    override val requests: Flow<RemoteSnapshotRequest> = emptyFlow()
    override val responses: Flow<RemoteSnapshotResponse> = emptyFlow()
    override suspend fun connect(accountId: String) = Unit
    override suspend fun disconnect() = Unit
    override suspend fun send(request: RemoteSnapshotRequest): Nothing = unavailable()
    override suspend fun send(response: RemoteSnapshotResponse): Nothing = unavailable()
    private fun unavailable(): Nothing = throw CloudUnavailableException("snapshot relay is unavailable")
}
