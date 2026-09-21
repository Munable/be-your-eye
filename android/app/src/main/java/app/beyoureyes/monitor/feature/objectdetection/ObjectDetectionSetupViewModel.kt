package app.beyoureyes.monitor.feature.objectdetection

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.beyoureyes.core.data.PersistedMonitor
import app.beyoureyes.core.data.ResolvedSamplingConfig
import app.beyoureyes.core.data.UuidV7
import app.beyoureyes.core.domain.Monitor
import app.beyoureyes.core.domain.MonitorRule
import app.beyoureyes.core.domain.MonitorTarget
import app.beyoureyes.monitor.ModelPreparationProgress
import app.beyoureyes.monitor.R
import app.beyoureyes.monitor.TransientObjectPreparationResult
import app.beyoureyes.monitor.design.UiText
import app.beyoureyes.monitor.design.uiText
import app.beyoureyes.monitor.feature.monitoring.FieldSetupController
import app.beyoureyes.monitor.feature.monitoring.MonitorCameraState
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

internal class ObjectDetectionSetupViewModel(
    private val target: MonitorTarget.ObjectClass,
    private val monitorName: String = "${target.labelEn.trim()} monitor",
    private val rule: MonitorRule.TargetPresence = MonitorRule.TargetPresence(),
    private val initialNotificationsEnabled: Boolean = false,
    private val preparation: ObjectDetectionPreparation,
    private val persistence: ObjectDetectionTaskPersistence,
    private val diagnosticEvent: (String) -> Unit = {},
    private val diagnosticFailure: (String, String) -> Unit = { event, _ -> diagnosticEvent(event) },
    private val taskId: String = UuidV7.generate(),
    private val nowEpochMillis: () -> Long = System::currentTimeMillis,
    private val workerScope: CoroutineScope? = null,
) : ViewModel(), FieldSetupController {
    private val mutableState = MutableStateFlow<MonitorCameraState>(MonitorCameraState.Loading())
    override val state: StateFlow<MonitorCameraState> = mutableState.asStateFlow()
    private val promotionMutex = Mutex()
    private var loadJob: Job? = null
    private var generation = 0L
    private var persisted = false

    init { load() }

    override fun retry() = load()

    override fun setNotificationsEnabled(enabled: Boolean) {
        val ready = mutableState.value as? MonitorCameraState.Ready ?: return
        mutableState.value = ready.copy(notificationsEnabled = enabled, conditionError = null)
        if (persisted) launchWorker {
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

    override suspend fun persist(): MonitorCameraState.Ready? = promotionMutex.withLock {
        val ready = mutableState.value as? MonitorCameraState.Ready ?: return@withLock null
        if (persisted) return@withLock ready
        try {
            withContext(NonCancellable) {
                val saved = persistence.persist(taskId, target, rule, ready.runtime.config, nowEpochMillis())
                persisted = true
                ready.copy(
                    persisted = saved,
                    notificationsEnabled = if (ready.notificationsEnabled) {
                        runCatching { persistence.setNotificationsEnabled(taskId, true) }.isSuccess
                    } else false,
                ).also { mutableState.value = it }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Throwable) {
            mutableState.value = MonitorCameraState.Error(
                uiText(R.string.error_monitor_save_failed),
            )
            null
        }
    }

    override fun leave(onLeft: () -> Unit) {
        generation++
        loadJob?.cancel()
        launchWorker {
            withContext(NonCancellable) { promotionMutex.withLock { onLeft() } }
        }
    }

    private fun load() {
        if (loadJob?.isActive == true || persisted) return
        val currentGeneration = ++generation
        loadJob = launchWorker {
            mutableState.value = MonitorCameraState.Loading()
            diagnosticEvent("object_detection_package_preparation_started")
            try {
                when (val result = preparation.prepare(taskId, target) { progress ->
                    mutableState.value = MonitorCameraState.Loading(
                        message = modelPreparationMessage(progress),
                        progress = progress,
                    )
                }) {
                    is TransientObjectPreparationResult.Ready -> {
                        current(currentGeneration)
                        mutableState.value = MonitorCameraState.Ready(
                            persisted = PersistedMonitor(
                                monitor = Monitor(
                                    id = taskId,
                                    revision = 1,
                                    name = monitorName,
                                    target = target,
                                    rule = rule,
                                    createdAtEpochMillis = nowEpochMillis(),
                                ),
                                runtimePackagePointer = result.runtime.config.packagePointer,
                            ),
                            runtime = result.runtime,
                            notificationsEnabled = initialNotificationsEnabled,
                        )
                        diagnosticEvent("object_detection_package_preparation_completed")
                    }
                    is TransientObjectPreparationResult.Unavailable -> {
                        current(currentGeneration)
                        diagnosticFailure("object_detection_package_preparation_failed", result.failure.name.lowercase())
                        mutableState.value = MonitorCameraState.Error(
                            UiText.Verbatim(result.userMessage),
                            canOpenAccount = result.canOpenAccount,
                        )
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Throwable) {
                if (currentGeneration == generation) {
                    diagnosticFailure("object_detection_package_preparation_failed", "preparation_exception")
                    mutableState.value = MonitorCameraState.Error(
                        uiText(R.string.error_object_not_ready),
                        retryable = true,
                    )
                }
            }
        }
    }

    private suspend fun current(expected: Long) {
        currentCoroutineContext().ensureActive()
        check(expected == generation)
    }

    private fun launchWorker(block: suspend CoroutineScope.() -> Unit): Job =
        (workerScope ?: viewModelScope).launch(block = block)
}

internal fun interface ObjectDetectionPreparation {
    suspend fun prepare(
        taskId: String,
        target: MonitorTarget.ObjectClass,
        onProgress: (ModelPreparationProgress) -> Unit,
    ): TransientObjectPreparationResult
}

internal interface ObjectDetectionTaskPersistence {
    suspend fun persist(
        taskId: String,
        target: MonitorTarget.ObjectClass,
        rule: MonitorRule.TargetPresence,
        resolvedSamplingConfig: ResolvedSamplingConfig,
        nowEpochMillis: Long,
    ): PersistedMonitor

    suspend fun setNotificationsEnabled(taskId: String, enabled: Boolean)
}
