package app.beyoureyes.monitor.feature.reference

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.beyoureyes.core.data.PersistedMonitor
import app.beyoureyes.core.data.ResolvedSamplingConfig
import app.beyoureyes.core.data.StoredLocalTask
import app.beyoureyes.core.data.UuidV7
import app.beyoureyes.core.domain.ReferenceMaterial
import app.beyoureyes.core.domain.MonitorRule
import app.beyoureyes.monitor.ModelPreparationProgress
import app.beyoureyes.monitor.R
import app.beyoureyes.monitor.TransientReferencePreparationResult
import app.beyoureyes.monitor.design.UiText
import app.beyoureyes.monitor.design.uiText
import app.beyoureyes.monitor.feature.monitoring.MonitorCameraState
import app.beyoureyes.monitor.feature.monitoring.FieldSetupController
import app.beyoureyes.monitor.feature.monitoring.modelPreparationMessage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

internal data class ReferenceStage(
    val task: StoredLocalTask,
    val preview: PersistedMonitor,
    internal val repositoryHandle: Any,
) {
    init {
        require(preview.monitor.id == task.taskId)
        require(preview.monitor.revision == task.revision)
        require(preview.runtimePackagePointer == null)
    }
}

internal class ReferenceSetupViewModel(
    private val draft: ReferenceDraft,
    private val preparation: ReferencePreparation,
    private val persistence: ReferenceTaskPersistence,
    private val diagnosticEvent: (String) -> Unit = {},
    private val diagnosticFailure: (String, String) -> Unit = { event, _ -> diagnosticEvent(event) },
    private val taskId: String = UuidV7.generate(),
    private val nowEpochMillis: () -> Long = System::currentTimeMillis,
    private val workerScope: CoroutineScope? = null,
) : ViewModel(), FieldSetupController {
    private val mutableState = MutableStateFlow<MonitorCameraState>(MonitorCameraState.Loading())
    override val state: StateFlow<MonitorCameraState> = mutableState.asStateFlow()
    private var loadJob: Job? = null
    private var staged: ReferenceStage? = null
    private var persisted = false
    private val promotionMutex = Mutex()
    private var lifecycleGeneration = 0L

    val hasPersisted: Boolean get() = persisted

    init { load() }

    override fun retry() = load()

    override fun setNotificationsEnabled(enabled: Boolean) {
        val ready = mutableState.value as? MonitorCameraState.Ready ?: return
        if (ready.notificationsEnabled == enabled) return
        mutableState.value = ready.copy(notificationsEnabled = enabled, conditionError = null)
        if (persisted) {
            launchWorker {
                runCatching { persistence.setNotificationsEnabled(taskId, enabled) }
                    .onFailure {
                        val current = mutableState.value as? MonitorCameraState.Ready ?: return@onFailure
                        mutableState.value = current.copy(
                            notificationsEnabled = !enabled,
                            conditionError = uiText(R.string.error_notification_save_failed),
                        )
                    }
            }
        }
    }

    override suspend fun persist(): MonitorCameraState.Ready? = promotionMutex.withLock {
        val ready = mutableState.value as? MonitorCameraState.Ready ?: return@withLock null
        if (persisted) return@withLock ready
        val pending = staged ?: return@withLock null
        try {
            withContext(NonCancellable) {
                val committed = persistence.commit(pending, ready.runtime.config)
                persisted = true
                staged = null
                val notificationsSaved = if (ready.notificationsEnabled) {
                    runCatching { persistence.setNotificationsEnabled(taskId, true) }.isSuccess
                } else true
                ready.copy(
                    persisted = committed,
                    notificationsEnabled = ready.notificationsEnabled && notificationsSaved,
                    conditionError = if (notificationsSaved) null else {
                        uiText(R.string.error_monitor_saved_notification_failed)
                    },
                ).also { mutableState.value = it }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Throwable) {
            staged = null
            mutableState.value = MonitorCameraState.Error(
                uiText(R.string.error_reference_persist_reprepare),
            )
            load()
            null
        }
    }

    override fun leave(onLeft: () -> Unit) {
        lifecycleGeneration += 1
        loadJob?.cancel()
        launchWorker {
            withContext(NonCancellable) {
                promotionMutex.withLock {
                    staged?.let { persistence.discard(it) }
                    staged = null
                    onLeft()
                }
            }
        }
    }

    private fun load() {
        if (loadJob?.isActive == true || persisted) return
        val generation = ++lifecycleGeneration
        loadJob = launchWorker {
            mutableState.value = MonitorCameraState.Loading()
            diagnosticEvent("manual_reference_package_preparation_started")
            try {
                staged?.let { persistence.discard(it) }
                staged = null
                val newStage = persistence.stage(
                    taskId,
                    draft.requestedName,
                    draft.materials,
                    draft.rule,
                    nowEpochMillis(),
                )
                if (generation != lifecycleGeneration) {
                    persistence.discard(newStage)
                    return@launchWorker
                }
                staged = newStage
                when (val result = preparation.prepare(newStage.task) { progress ->
                    mutableState.value = MonitorCameraState.Loading(
                        message = modelPreparationMessage(progress),
                        progress = progress,
                    )
                }) {
                    is TransientReferencePreparationResult.Ready -> {
                        current(generation)
                        mutableState.value = MonitorCameraState.Ready(
                            persisted = newStage.preview.copy(runtimePackagePointer = result.runtime.config.packagePointer),
                            runtime = result.runtime,
                            referenceMaterials = draft.materials,
                            notificationsEnabled = draft.notificationsEnabled,
                        )
                        diagnosticEvent("manual_reference_package_preparation_completed")
                    }
                    is TransientReferencePreparationResult.Unavailable -> {
                        current(generation)
                        staged?.let { persistence.discard(it) }
                        staged = null
                        diagnosticFailure("manual_reference_package_preparation_failed", result.failure.name.lowercase())
                        mutableState.value = MonitorCameraState.Error(
                            UiText.Verbatim(result.userMessage),
                        )
                    }
                }
            } catch (cancelled: CancellationException) {
                staged?.let { persistence.discard(it) }
                staged = null
                throw cancelled
            } catch (_: Throwable) {
                staged?.let { persistence.discard(it) }
                staged = null
                if (generation == lifecycleGeneration) {
                    diagnosticFailure("manual_reference_package_preparation_failed", "preparation_exception")
                    mutableState.value = MonitorCameraState.Error(
                        uiText(R.string.error_reference_not_ready),
                        retryable = true,
                    )
                }
            }
        }
    }

    private suspend fun current(generation: Long) {
        currentCoroutineContext().ensureActive()
        check(generation == lifecycleGeneration) { "reference setup generation changed" }
    }

    private fun launchWorker(block: suspend CoroutineScope.() -> Unit): Job =
        (workerScope ?: viewModelScope).launch(block = block)
}

internal data class ReferenceDraft(
    val requestedName: String,
    val materials: List<ReferenceMaterial>,
    val rule: MonitorRule.TargetPresence = MonitorRule.TargetPresence(),
    val notificationsEnabled: Boolean = false,
    val requiredModelProfileKey: String? = null,
    val requiredPackageId: String? = null,
    val requiredIntentKey: String? = null,
) {
    init {
        require(
            listOf(requiredModelProfileKey, requiredPackageId, requiredIntentKey)
                .all { it == null } ||
                listOf(requiredModelProfileKey, requiredPackageId, requiredIntentKey)
                    .all { !it.isNullOrBlank() },
        ) { "reference model binding must be complete" }
    }
}

internal fun interface ReferencePreparation {
    suspend fun prepare(
        stagedTask: StoredLocalTask,
        onProgress: (ModelPreparationProgress) -> Unit,
    ): TransientReferencePreparationResult
}

internal interface ReferenceTaskPersistence {
    suspend fun stage(
        taskId: String,
        requestedName: String,
        materials: List<ReferenceMaterial>,
        rule: MonitorRule.TargetPresence,
        nowEpochMillis: Long,
    ): ReferenceStage

    suspend fun commit(stage: ReferenceStage, resolvedSamplingConfig: ResolvedSamplingConfig): PersistedMonitor

    suspend fun discard(stage: ReferenceStage)

    suspend fun setNotificationsEnabled(taskId: String, enabled: Boolean)
}
