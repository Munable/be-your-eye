package app.beyoureyes.monitor.feature.reading

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.beyoureyes.core.data.PersistedMonitor
import app.beyoureyes.core.data.ResolvedSamplingConfig
import app.beyoureyes.core.data.UuidV7
import app.beyoureyes.core.domain.Monitor
import app.beyoureyes.core.domain.MonitorRule
import app.beyoureyes.core.domain.MonitorTarget
import app.beyoureyes.core.domain.ReadingComparison
import app.beyoureyes.core.domain.ReadingTargetConfig
import app.beyoureyes.monitor.ModelPreparationProgress
import app.beyoureyes.monitor.R
import app.beyoureyes.monitor.TransientReadingPreparationResult
import app.beyoureyes.monitor.design.UiText
import app.beyoureyes.monitor.design.uiText
import app.beyoureyes.monitor.feature.monitoring.MonitorCameraState
import app.beyoureyes.monitor.feature.monitoring.carryRuntimeAfterReadingRuleSave
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

internal class TransientReadingSetupViewModel(
    private val preparation: ReadingPreparation,
    private val persistence: ReadingTaskPersistence,
    private val monitorName: String,
    private val initialNotificationsEnabled: Boolean = false,
    private val diagnosticEvent: (String) -> Unit = {},
    private val diagnosticFailure: (String, String) -> Unit = { event, _ -> diagnosticEvent(event) },
    private val taskId: String = UuidV7.generate(),
    private val nowEpochMillis: () -> Long = System::currentTimeMillis,
    private val workerScope: CoroutineScope? = null,
) : ViewModel() {
    private val mutableState = MutableStateFlow<MonitorCameraState>(MonitorCameraState.Loading())
    val state: StateFlow<MonitorCameraState> = mutableState.asStateFlow()
    private var loadJob: Job? = null
    private val promotionMutex = Mutex()
    private var lifecycleGeneration = 0L

    var hasPersisted: Boolean = false
        private set

    init { load() }

    fun retry() = load()

    fun setNotificationsEnabled(enabled: Boolean) {
        val ready = mutableState.value as? MonitorCameraState.Ready ?: return
        mutableState.value = ready.copy(notificationsEnabled = enabled, conditionError = null)
        if (hasPersisted) launchWorker {
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

    suspend fun configureReadingAndPersist(
        rule: MonitorRule.ReadingThreshold,
        targetConfig: ReadingTargetConfig,
    ): MonitorCameraState.Ready? = promotionMutex.withLock {
        val ready = mutableState.value as? MonitorCameraState.Ready ?: return@withLock null
        if (ready.savingCondition || !rule.configured || targetConfig.confirmedFormat == null) return@withLock null
        if (hasPersisted) {
            val persistedTarget = ready.persisted.monitor.target as? MonitorTarget.NumericReading
            if (ready.persisted.monitor.rule == rule && persistedTarget?.config == targetConfig) return@withLock ready
            mutableState.value = ready.copy(savingCondition = true, conditionError = null)
            return@withLock try {
                withContext(NonCancellable) {
                    val persisted = persistence.revise(taskId, ready.persisted.monitor.revision, rule, targetConfig)
                        ?: error("reading revision changed")
                    val runtime = carryRuntimeAfterReadingRuleSave(ready.persisted, persisted, ready.runtime)
                        ?: error("reading revision lost its exact runtime")
                    ready.copy(persisted = persisted, runtime = runtime, savingCondition = false, conditionError = null)
                        .also { mutableState.value = it }
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Throwable) {
                ready.copy(
                    savingCondition = false,
                    conditionError = uiText(R.string.error_condition_save_failed),
                )
                    .also { mutableState.value = it }
                null
            }
        }
        mutableState.value = ready.copy(savingCondition = true, conditionError = null)
        try {
            withContext(NonCancellable) {
                val persisted = persistence.persist(taskId, rule, targetConfig, ready.runtime.config, nowEpochMillis())
                hasPersisted = true
                val notificationsSaved = runCatching {
                    persistence.setNotificationsEnabled(taskId, ready.notificationsEnabled)
                }.isSuccess
                ready.copy(
                    persisted = persisted,
                    savingCondition = false,
                    notificationsEnabled = ready.notificationsEnabled && notificationsSaved,
                    conditionError = if (notificationsSaved) null else {
                        uiText(R.string.error_monitor_saved_notification_failed)
                    },
                ).also { mutableState.value = it }
            }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Throwable) {
            ready.copy(
                savingCondition = false,
                conditionError = uiText(R.string.error_condition_save_failed),
            )
                .also { mutableState.value = it }
            null
        }
    }

    /**
     * Persists the task as pending-baseline (unconfirmed rule, no confirmed format) so the user
     * can start monitoring immediately and confirm the first stable reading later. A rejected
     * camera handoff can retry with the same saved task instead of inserting it again.
     */
    suspend fun persistPendingBaseline(
        targetConfig: ReadingTargetConfig,
    ): MonitorCameraState.Ready? = promotionMutex.withLock {
        val ready = mutableState.value as? MonitorCameraState.Ready ?: return@withLock null
        if (ready.savingCondition || targetConfig.confirmedFormat != null ||
            (ready.persisted.monitor.rule as? MonitorRule.ReadingThreshold)?.configured == true
        ) {
            return@withLock null
        }
        if (hasPersisted) return@withLock ready
        mutableState.value = ready.copy(savingCondition = true, conditionError = null)
        try {
            withContext(NonCancellable) {
                val persisted = persistence.persistPending(
                    taskId,
                    targetConfig,
                    ready.runtime.config,
                    nowEpochMillis(),
                )
                hasPersisted = true
                val notificationsSaved = runCatching {
                    persistence.setNotificationsEnabled(taskId, ready.notificationsEnabled)
                }.isSuccess
                ready.copy(
                    persisted = persisted,
                    savingCondition = false,
                    notificationsEnabled = ready.notificationsEnabled && notificationsSaved,
                    conditionError = if (notificationsSaved) null else {
                        uiText(R.string.error_monitor_saved_notification_failed)
                    },
                ).also { mutableState.value = it }
            }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Throwable) {
            ready.copy(
                savingCondition = false,
                conditionError = uiText(R.string.error_condition_save_failed),
            )
                .also { mutableState.value = it }
            null
        }
    }

    fun leave(onLeft: () -> Unit) {
        lifecycleGeneration++
        loadJob?.cancel()
        launchWorker { withContext(NonCancellable) { promotionMutex.withLock { onLeft() } } }
    }

    private fun load() {
        if (loadJob?.isActive == true || hasPersisted) return
        val generation = ++lifecycleGeneration
        loadJob = launchWorker {
            mutableState.value = MonitorCameraState.Loading()
            diagnosticEvent("manual_reading_package_preparation_started")
            try {
                when (val result = preparation.prepare(taskId) { progress ->
                    mutableState.value = MonitorCameraState.Loading(
                        message = modelPreparationMessage(progress),
                        progress = progress,
                    )
                }) {
                    is TransientReadingPreparationResult.Ready -> {
                        current(generation)
                        val createdAt = nowEpochMillis()
                        mutableState.value = MonitorCameraState.Ready(
                            persisted = PersistedMonitor(
                                monitor = Monitor(
                                    id = taskId,
                                    revision = 1,
                                    name = monitorName,
                                    target = MonitorTarget.NumericReading(),
                                    rule = MonitorRule.ReadingThreshold.Single(
                                        comparison = ReadingComparison.GT,
                                        thresholdDecimal = "0",
                                        configured = false,
                                    ),
                                    createdAtEpochMillis = createdAt,
                                ),
                                runtimePackagePointer = result.runtime.config.packagePointer,
                            ),
                            runtime = result.runtime,
                            notificationsEnabled = initialNotificationsEnabled,
                        )
                        diagnosticEvent("manual_reading_package_preparation_completed")
                    }
                    is TransientReadingPreparationResult.Unavailable -> {
                        current(generation)
                        diagnosticFailure("manual_reading_package_preparation_failed", result.failure.name.lowercase())
                        mutableState.value = MonitorCameraState.Error(
                            UiText.Verbatim(result.userMessage),
                            canOpenAccount = result.canOpenAccount,
                        )
                    }
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Throwable) {
                if (generation == lifecycleGeneration) {
                    diagnosticFailure("manual_reading_package_preparation_failed", "preparation_exception")
                    mutableState.value = MonitorCameraState.Error(
                        uiText(R.string.error_reading_not_ready),
                        retryable = true,
                    )
                }
            }
        }
    }

    private suspend fun current(generation: Long) {
        currentCoroutineContext().ensureActive()
        check(generation == lifecycleGeneration) { "reading setup generation changed" }
    }

    private fun launchWorker(block: suspend CoroutineScope.() -> Unit): Job =
        (workerScope ?: viewModelScope).launch(block = block)
}

internal fun interface ReadingPreparation {
    suspend fun prepare(taskId: String, onProgress: (ModelPreparationProgress) -> Unit): TransientReadingPreparationResult
}

internal interface ReadingTaskPersistence {
    suspend fun persist(
        taskId: String,
        rule: MonitorRule.ReadingThreshold,
        targetConfig: ReadingTargetConfig,
        resolvedSamplingConfig: ResolvedSamplingConfig,
        nowEpochMillis: Long,
    ): PersistedMonitor

    suspend fun persistPending(
        taskId: String,
        targetConfig: ReadingTargetConfig,
        resolvedSamplingConfig: ResolvedSamplingConfig,
        nowEpochMillis: Long,
    ): PersistedMonitor

    suspend fun revise(
        taskId: String,
        expectedRevision: Long,
        rule: MonitorRule.ReadingThreshold,
        targetConfig: ReadingTargetConfig,
    ): PersistedMonitor?

    suspend fun setNotificationsEnabled(taskId: String, enabled: Boolean)
}
