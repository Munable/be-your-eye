package app.beyoureyes.monitor.feature.monitoring

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.beyoureyes.core.data.MonitorRepository
import app.beyoureyes.core.data.PersistedMonitor
import app.beyoureyes.core.domain.MonitorKind
import app.beyoureyes.core.domain.MonitorRule
import app.beyoureyes.core.domain.ReadingTargetConfig
import app.beyoureyes.core.domain.ReferenceMaterial
import app.beyoureyes.monitor.BuildConfig
import app.beyoureyes.monitor.ModelPreparationCoordinator
import app.beyoureyes.monitor.ModelPreparationFailure
import app.beyoureyes.monitor.ModelPreparationProgress
import app.beyoureyes.monitor.ModelPreparationResult
import app.beyoureyes.monitor.SamplingConfigResolution
import app.beyoureyes.monitor.TaskBoundSamplingConfigResolver
import app.beyoureyes.monitor.R
import app.beyoureyes.monitor.design.UiText
import app.beyoureyes.monitor.design.uiText
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

internal sealed interface MonitorCameraState {
    data class Loading(
        val message: UiText = uiText(R.string.camera_loading_settings),
        val progress: ModelPreparationProgress? = null,
    ) : MonitorCameraState
    data class Ready(
        val persisted: PersistedMonitor,
        val runtime: SamplingConfigResolution.Ready,
        val referenceMaterials: List<ReferenceMaterial> = emptyList(),
        val notificationsEnabled: Boolean = false,
        val savingCondition: Boolean = false,
        val conditionError: UiText? = null,
    ) : MonitorCameraState
    data class Error(
        val message: UiText,
        val canReplaceReferenceImages: Boolean = false,
        val removingMonitor: Boolean = false,
        val retryable: Boolean = true,
        val canOpenAccount: Boolean = false,
    ) : MonitorCameraState
}

internal class MonitorCameraViewModel(
    private val monitorId: String,
    private val repository: MonitorRepository,
    private val preparation: ModelPreparationCoordinator,
    private val samplingResolver: TaskBoundSamplingConfigResolver,
    private val diagnosticEvent: (String) -> Unit = {},
    private val diagnosticFailure: (String, String) -> Unit = { event, _ -> diagnosticEvent(event) },
    private val clearMonitoringHeartbeat: (String) -> Unit = {},
) : ViewModel() {
    private val mutableState = MutableStateFlow<MonitorCameraState>(MonitorCameraState.Loading())
    val state: StateFlow<MonitorCameraState> = mutableState.asStateFlow()
    private var loadJob: Job? = null
    private val readingPersistence = ReadingRulePersistenceOwner(viewModelScope)

    @Volatile
    private var lifecycleGeneration = 0L

    init {
        load()
    }

    fun retry() = load()

    fun setNotificationsEnabled(enabled: Boolean) {
        val ready = mutableState.value as? MonitorCameraState.Ready ?: return
        if (ready.notificationsEnabled == enabled) return
        val generation = lifecycleGeneration
        viewModelScope.launch {
            try {
                repository.setNotificationsEnabled(monitorId, enabled)
                if (!isCurrentGeneration(generation)) return@launch
                val current = mutableState.value as? MonitorCameraState.Ready ?: return@launch
                mutableState.value = current.copy(notificationsEnabled = enabled)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Throwable) {
                if (!isCurrentGeneration(generation)) return@launch
                val current = mutableState.value as? MonitorCameraState.Ready ?: return@launch
                mutableState.value = current.copy(
                    conditionError = uiText(R.string.error_notification_save_failed),
                )
            }
        }
    }

    fun leave(onLeft: () -> Unit) {
        advanceLifecycleGeneration()
        loadJob?.cancel()
        val pendingReadingSave = readingPersistence.beginLeave()
        viewModelScope.launch {
            try {
                pendingReadingSave?.await()
            } catch (error: CancellationException) {
                throw error
            } catch (_: Throwable) {
                // The stored reading was already complete before this edit; preserve its last
                // committed revision if the optional rule update failed.
            } finally {
                onLeft()
            }
        }
    }

    fun replaceReferenceImages(onDeleted: () -> Unit) {
        val error = mutableState.value as? MonitorCameraState.Error ?: return
        if (!error.canReplaceReferenceImages || error.removingMonitor) return
        mutableState.value = error.copy(removingMonitor = true)
        val generation = lifecycleGeneration
        viewModelScope.launch {
            try {
                if (repository.delete(monitorId)) {
                    if (!isCurrentGeneration(generation)) return@launch
                    clearMonitoringHeartbeat(monitorId)
                    diagnosticEvent("reference_creation_discarded")
                    onDeleted()
                } else if (isCurrentGeneration(generation)) {
                    mutableState.value = error.copy(message = uiText(R.string.error_cleanup_failed))
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Throwable) {
                if (isCurrentGeneration(generation)) {
                    mutableState.value = error.copy(message = uiText(R.string.error_cleanup_failed))
                }
            }
        }
    }

    suspend fun configureReadingAndRefresh(
        rule: MonitorRule.ReadingThreshold,
        targetConfig: ReadingTargetConfig,
    ): MonitorCameraState.Ready? {
        readingPersistence.current()?.let {
            return readingPersistence.forHandoff(it.await())
        }
        if (readingPersistence.isLeaving()) return null
        val ready = mutableState.value as? MonitorCameraState.Ready ?: return null
        if (ready.persisted.monitor.kind != MonitorKind.READING || ready.savingCondition) return null
        if (!rule.configured) {
            mutableState.value = ready.copy(conditionError = uiText(R.string.error_enter_valid_number))
            return null
        }
        mutableState.value = ready.copy(savingCondition = true, conditionError = null)
        val persistence = readingPersistence.startOrNull {
            val next = persistReadingRuleForHandoff(ready, rule, targetConfig) {
                    id, expectedRevision, configuredRule, configuredTarget,
                ->
                repository.configureReading(id, expectedRevision, configuredRule, configuredTarget)
            }
            mutableState.value = next ?: readingRuleSaveFailureState(ready)
            next
        } ?: return null
        return readingPersistence.forHandoff(persistence.await())
    }

    private fun load() {
        if (loadJob?.isActive == true) return
        val generation = advanceLifecycleGeneration()
        loadJob = viewModelScope.launch {
            mutableState.value = MonitorCameraState.Loading()
            try {
                val persistedResult = runCatching { repository.findMonitor(monitorId) }
                ensureCurrentGeneration(generation)
                if (persistedResult.isFailure) {
                    mutableState.value = MonitorCameraState.Error(
                        uiText(R.string.error_monitor_settings_read_failed),
                    )
                    return@launch
                }
                val persisted = persistedResult.getOrNull()
                if (persisted == null) {
                    mutableState.value = missingMonitorCameraError()
                    return@launch
                }

                val cached = samplingResolver.resolve(monitorId)
                ensureCurrentGeneration(generation)
                if (cached is SamplingConfigResolution.Ready) {
                    val referenceMaterials = loadReferenceMaterials(persisted)
                    ensureCurrentGeneration(generation)
                    mutableState.value = MonitorCameraState.Ready(
                        persisted = persisted,
                        runtime = cached,
                        referenceMaterials = referenceMaterials,
                        notificationsEnabled = repository.state.value.notificationsEnabled(monitorId),
                    )
                    return@launch
                }
                if (!BuildConfig.PRODUCT_RUNTIME_ENABLED) {
                    mutableState.value = MonitorCameraState.Error(
                        uiText(R.string.error_ui_only_build),
                    )
                    return@launch
                }
                diagnosticEvent("package_preparation_started")
                when (
                    val prepared = preparation.prepareTask(monitorId) { progress ->
                        if (isCurrentGeneration(generation)) {
                            mutableState.value = MonitorCameraState.Loading(
                                message = modelPreparationMessage(progress),
                                progress = progress,
                            )
                        }
                    }
                ) {
                    is ModelPreparationResult.Ready -> {
                        ensureCurrentGeneration(generation)
                        val updatedResult = runCatching { repository.findMonitor(monitorId) }
                        ensureCurrentGeneration(generation)
                        if (updatedResult.isFailure) {
                            mutableState.value = MonitorCameraState.Error(
                                uiText(R.string.error_monitor_settings_read_failed),
                            )
                            return@launch
                        }
                        val updated = updatedResult.getOrNull()
                        if (updated == null) {
                            mutableState.value = missingMonitorCameraError()
                            return@launch
                        }
                        if (updated.runtimePackagePointer == null) {
                            mutableState.value = MonitorCameraState.Error(
                                uiText(R.string.error_detection_settings_save_failed),
                            )
                            return@launch
                        }
                        when (val runtime = samplingResolver.resolve(monitorId)) {
                            is SamplingConfigResolution.Ready -> {
                                ensureCurrentGeneration(generation)
                                val referenceMaterials = loadReferenceMaterials(updated)
                                ensureCurrentGeneration(generation)
                                mutableState.value = MonitorCameraState.Ready(
                                    persisted = updated,
                                    runtime = runtime,
                                    referenceMaterials = referenceMaterials,
                                    notificationsEnabled = repository.state.value.notificationsEnabled(monitorId),
                                )
                                diagnosticEvent("package_preparation_completed")
                            }
                            is SamplingConfigResolution.Unavailable -> {
                                diagnosticFailure(
                                    "package_preparation_failed",
                                    runtime.failure.name.lowercase(),
                                )
                                mutableState.value = MonitorCameraState.Error(
                                    UiText.Verbatim(runtime.userMessage),
                                )
                            }
                        }
                    }
                    is ModelPreparationResult.Unavailable -> {
                        ensureCurrentGeneration(generation)
                        diagnosticFailure(
                            "package_preparation_failed",
                            prepared.failure.name.lowercase(),
                        )
                        mutableState.value = MonitorCameraState.Error(
                            message = UiText.Verbatim(prepared.userMessage),
                            canOpenAccount = prepared.canOpenAccount,
                        )
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Throwable) {
                if (isCurrentGeneration(generation)) {
                    diagnosticFailure("package_preparation_failed", "preparation_exception")
                    mutableState.value = MonitorCameraState.Error(
                        uiText(R.string.error_detection_settings_not_ready),
                        retryable = true,
                    )
                }
            }
        }
    }

    @Synchronized
    private fun advanceLifecycleGeneration(): Long {
        lifecycleGeneration += 1
        return lifecycleGeneration
    }

    private suspend fun ensureCurrentGeneration(generation: Long) {
        currentCoroutineContext().ensureActive()
        if (lifecycleGeneration != generation) {
            throw CancellationException("monitor camera lifecycle generation changed")
        }
    }

    private fun isCurrentGeneration(generation: Long): Boolean =
        lifecycleGeneration == generation

    private suspend fun loadReferenceMaterials(persisted: PersistedMonitor): List<ReferenceMaterial> =
        if (persisted.monitor.kind == MonitorKind.REFERENCE) {
            try {
                repository.loadReferenceMaterials(persisted.monitor.id)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Throwable) {
                emptyList()
            }
        } else {
            emptyList()
        }
}

internal fun modelPreparationMessage(
    progress: ModelPreparationProgress,
): UiText = when (progress) {
    ModelPreparationProgress.Checking -> uiText(R.string.model_progress_checking)
    is ModelPreparationProgress.AwaitingDownload -> uiText(R.string.model_download_title)
    is ModelPreparationProgress.UsingDownloaded -> uiText(R.string.model_progress_cached)
    is ModelPreparationProgress.Downloading -> uiText(R.string.model_progress_downloading)
    ModelPreparationProgress.Verifying -> uiText(R.string.model_progress_verifying)
    ModelPreparationProgress.Activating -> uiText(R.string.model_progress_activating)
}

/** A screen may await this job, but only the ViewModel scope owns and may cancel the write. */
internal class ReadingRulePersistenceOwner(
    private val scope: CoroutineScope,
) {
    private var active: Deferred<MonitorCameraState.Ready?>? = null
    private var leaving = false

    @Synchronized
    fun current(): Deferred<MonitorCameraState.Ready?>? = active

    @Synchronized
    fun isLeaving(): Boolean = leaving

    @Synchronized
    fun beginLeave(): Deferred<MonitorCameraState.Ready?>? {
        leaving = true
        return active
    }

    @Synchronized
    fun forHandoff(result: MonitorCameraState.Ready?): MonitorCameraState.Ready? =
        result.takeUnless { leaving }

    @Synchronized
    fun startOrNull(
        persist: suspend () -> MonitorCameraState.Ready?,
    ): Deferred<MonitorCameraState.Ready?>? {
        if (leaving) return null
        check(active == null) { "a reading rule persistence job is already active" }
        val deferred = scope.async(start = CoroutineStart.LAZY) { persist() }
        active = deferred
        deferred.invokeOnCompletion { clearIfCurrent(deferred) }
        deferred.start()
        return deferred
    }

    @Synchronized
    private fun clearIfCurrent(completed: Deferred<MonitorCameraState.Ready?>) {
        if (active === completed) active = null
    }
}

internal suspend fun persistReadingRuleForHandoff(
    ready: MonitorCameraState.Ready,
    rule: MonitorRule.ReadingThreshold,
    targetConfig: ReadingTargetConfig,
    persist: suspend (String, Long, MonitorRule.ReadingThreshold, ReadingTargetConfig) -> PersistedMonitor?,
): MonitorCameraState.Ready? = try {
    val updated = persist(
        ready.persisted.monitor.id,
        ready.persisted.monitor.revision,
        rule,
        targetConfig,
    )
    // The live reading preview intentionally keeps the package lease until camera handoff.
    // Re-resolving here would try to acquire that same package a second time and fail as
    // PACKAGE_UNAVAILABLE. The repository advances task and sampling revisions atomically, so
    // carry the already verified immutable runtime to that exact next revision.
    val runtime = updated?.let {
        carryRuntimeAfterReadingRuleSave(ready.persisted, it, ready.runtime)
    }
    runtime?.let { MonitorCameraState.Ready(checkNotNull(updated), it) }
} catch (error: CancellationException) {
    throw error
} catch (_: Exception) {
    null
}

internal fun readingRuleSaveFailureState(
    ready: MonitorCameraState.Ready,
): MonitorCameraState.Ready = ready.copy(
    savingCondition = false,
    conditionError = uiText(R.string.error_condition_save_failed),
)

internal fun carryRuntimeAfterReadingRuleSave(
    previous: PersistedMonitor,
    updated: PersistedMonitor,
    runtime: SamplingConfigResolution.Ready,
): SamplingConfigResolution.Ready? {
    val nextRevision = runCatching { Math.addExact(previous.monitor.revision, 1L) }.getOrNull()
        ?: return null
    if (updated.monitor.id != previous.monitor.id || updated.monitor.revision != nextRevision ||
        runtime.config.taskId != previous.monitor.id ||
        runtime.config.taskRevision != previous.monitor.revision ||
        previous.runtimePackagePointer == null ||
        updated.runtimePackagePointer != previous.runtimePackagePointer ||
        runtime.config.packagePointer != previous.runtimePackagePointer
    ) {
        return null
    }
    val readingPreviewSpec = runtime.readingPreviewSpec?.let { spec ->
        if (spec.taskId != previous.monitor.id ||
            spec.taskRevision != previous.monitor.revision ||
            spec.packagePointer != previous.runtimePackagePointer
        ) {
            return null
        }
        spec.rebindTaskRevision(nextRevision)
    }
    return runtime.copy(
        config = runtime.config.copy(taskRevision = nextRevision),
        readingPreviewSpec = readingPreviewSpec,
    )
}

internal fun missingMonitorCameraError() = MonitorCameraState.Error(
    message = uiText(R.string.monitor_missing_return_home),
    retryable = false,
)
