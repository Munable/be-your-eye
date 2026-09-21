package app.beyoureyes.monitor.feature.monitoring

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.beyoureyes.core.data.MonitorRepository
import app.beyoureyes.core.data.MonitorRepositoryState
import app.beyoureyes.core.data.reference.ReferenceImportResult
import app.beyoureyes.core.domain.MAX_REFERENCE_IMAGES
import app.beyoureyes.core.domain.MIN_REFERENCE_IMAGES
import app.beyoureyes.core.domain.MonitorRule
import app.beyoureyes.core.domain.ReferenceMaterial
import app.beyoureyes.monitor.R
import app.beyoureyes.monitor.TaskBoundSamplingConfigResolver
import app.beyoureyes.monitor.design.UiText
import app.beyoureyes.monitor.design.pluralText
import app.beyoureyes.monitor.design.uiText
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

internal sealed interface ReferencePreviewState {
    data object Loading : ReferencePreviewState
    data class Ready(val materials: List<ReferenceMaterial>) : ReferencePreviewState
    data class Failed(val message: UiText) : ReferencePreviewState
}

internal data class MonitorDetailActionState(
    val savingPresenceRule: Boolean = false,
    val presenceRuleError: UiText? = null,
    val savingReadingRule: Boolean = false,
    val readingRuleError: UiText? = null,
)

internal sealed interface ModelDisclosureState {
    data object Loading : ModelDisclosureState
    data class Ready(val displayName: String) : ModelDisclosureState
    data object Unavailable : ModelDisclosureState
}

internal data class ReferenceEditorState(
    val expectedRevision: Long,
    val original: List<ReferenceMaterial>,
    val materials: List<ReferenceMaterial>,
    val importing: Boolean = false,
    val saving: Boolean = false,
    val message: UiText? = null,
) {
    val changed: Boolean
        get() = materials.map(ReferenceMaterial::exactSha256) !=
            original.map(ReferenceMaterial::exactSha256)
    val canSave: Boolean
        get() = materials.size in MIN_REFERENCE_IMAGES..MAX_REFERENCE_IMAGES &&
            changed && !importing && !saving
}

internal class MonitorDetailViewModel(
    val monitorId: String,
    private val repository: MonitorRepository,
    private val samplingResolver: TaskBoundSamplingConfigResolver,
) : ViewModel() {
    val state: StateFlow<MonitorRepositoryState> = repository.state
    private val mutableReferencePreview = MutableStateFlow<ReferencePreviewState>(
        ReferencePreviewState.Loading,
    )
    val referencePreview: StateFlow<ReferencePreviewState> = mutableReferencePreview.asStateFlow()
    private val mutableReferenceEditor = MutableStateFlow<ReferenceEditorState?>(null)
    val referenceEditor: StateFlow<ReferenceEditorState?> = mutableReferenceEditor.asStateFlow()
    private val mutableActionState = MutableStateFlow(MonitorDetailActionState())
    val actionState: StateFlow<MonitorDetailActionState> = mutableActionState.asStateFlow()
    private val mutableModelDisclosure = MutableStateFlow<ModelDisclosureState>(
        ModelDisclosureState.Loading,
    )
    val modelDisclosure: StateFlow<ModelDisclosureState> = mutableModelDisclosure.asStateFlow()
    private var referenceDraftSession: File? = null
    private var modelDisclosureJob: Job? = null

    init {
        retryModelDisclosure()
        viewModelScope.launch {
            mutableReferencePreview.value = runCatching {
                ReferencePreviewState.Ready(repository.loadReferenceMaterials(monitorId))
            }.getOrElse {
                ReferencePreviewState.Failed(uiText(R.string.error_reference_images_read_failed))
            }
        }
    }

    fun retryModelDisclosure() {
        if (modelDisclosureJob?.isActive == true) return
        mutableModelDisclosure.value = ModelDisclosureState.Loading
        modelDisclosureJob = viewModelScope.launch {
            mutableModelDisclosure.value = resolveModelDisclosureState {
                samplingResolver.resolveModelDisplayName(monitorId)
            }
        }
    }

    fun rename(name: String) {
        viewModelScope.launch { repository.rename(monitorId, name) }
    }

    /**
     * 详情页「开始监控」的无相机直接启动：采样配置必须仍然可用；不可用（如模型包被清理）时
     * 返回 null，界面回退到测试识别相机页完成重新准备。
     */
    suspend fun buildDirectStartConfig(
        targetRotation: Int,
        viewPortWidth: Int,
        viewPortHeight: Int,
    ): app.beyoureyes.monitor.RuntimeCameraConfig? {
        val row = state.value.local.singleOrNull { it.monitor.id == monitorId } ?: return null
        val ready = samplingResolver.resolve(monitorId)
            as? app.beyoureyes.monitor.SamplingConfigResolution.Ready ?: return null
        return app.beyoureyes.monitor.RuntimeCameraConfig(
            taskId = monitorId,
            taskRevision = row.monitor.revision,
            roi = app.beyoureyes.monitor.FULL_FRAME_MONITOR_REGION,
            viewPortWidth = viewPortWidth,
            viewPortHeight = viewPortHeight,
            targetRotation = targetRotation,
            resolvedSamplingConfig = ready.config,
            manualReadingScanRegion = (
                row.monitor.target as? app.beyoureyes.core.domain.MonitorTarget.NumericReading
                )?.manualRoi,
        )
    }

    fun setNotifications(enabled: Boolean) {
        viewModelScope.launch { repository.setNotificationsEnabled(monitorId, enabled) }
    }

    fun setPresenceRule(revision: Long, rule: MonitorRule.TargetPresence) {
        if (mutableActionState.value.savingPresenceRule) return
        mutableActionState.value = MonitorDetailActionState(savingPresenceRule = true)
        viewModelScope.launch {
            val updated = runCatching {
                repository.updatePresenceRule(monitorId, revision, rule)
            }.getOrNull()
            mutableActionState.value = if (updated == null) {
                MonitorDetailActionState(
                    presenceRuleError = uiText(R.string.error_condition_save_failed),
                )
            } else {
                MonitorDetailActionState()
            }
        }
    }

    fun setReadingRule(revision: Long, rule: MonitorRule.ReadingThreshold) {
        if (mutableActionState.value.savingReadingRule) return
        mutableActionState.value = MonitorDetailActionState(savingReadingRule = true)
        viewModelScope.launch {
            val updated = runCatching {
                repository.configureReading(monitorId, revision, rule)
            }.getOrNull()
            mutableActionState.value = if (updated == null) {
                MonitorDetailActionState(
                    readingRuleError = uiText(R.string.error_condition_save_failed),
                )
            } else {
                MonitorDetailActionState()
            }
        }
    }

    fun beginReferenceEdit(expectedRevision: Long) {
        val materials = (mutableReferencePreview.value as? ReferencePreviewState.Ready)?.materials
            ?: return
        if (mutableReferenceEditor.value != null) return
        mutableReferenceEditor.value = ReferenceEditorState(
            expectedRevision = expectedRevision,
            original = materials,
            materials = materials,
        )
    }

    fun addReferenceImages(uris: List<Uri>) {
        val current = mutableReferenceEditor.value ?: return
        if (uris.isEmpty() || current.importing || current.saving ||
            current.materials.size >= MAX_REFERENCE_IMAGES
        ) return
        val session = referenceDraftSession ?: repository.newReferenceDraftSession().also {
            referenceDraftSession = it
        }
        mutableReferenceEditor.value = current.copy(importing = true, message = null)
        viewModelScope.launch {
            runCatching {
                repository.importReferenceImages(
                    uris.take(MAX_REFERENCE_IMAGES - current.materials.size),
                    current.materials,
                    session,
                )
            }.onSuccess(::acceptReferenceEditImport).onFailure {
                mutableReferenceEditor.value = mutableReferenceEditor.value?.copy(
                    importing = false,
                    message = uiText(R.string.error_images_read_failed),
                )
            }
        }
    }

    fun removeReferenceMaterial(material: ReferenceMaterial) {
        val current = mutableReferenceEditor.value ?: return
        if (current.importing || current.saving) return
        if (current.original.none { it.sourceUri == material.sourceUri }) {
            repository.discardReferenceDrafts(listOf(material))
        }
        mutableReferenceEditor.value = current.copy(
            materials = current.materials - material,
            message = null,
        )
    }

    fun cancelReferenceEdit() {
        val current = mutableReferenceEditor.value ?: return
        if (current.saving) return
        discardReferenceEditorDrafts(current)
        mutableReferenceEditor.value = null
        referenceDraftSession = null
    }

    fun saveReferenceEdit() {
        val current = mutableReferenceEditor.value ?: return
        if (!current.canSave) return
        mutableReferenceEditor.value = current.copy(saving = true, message = null)
        viewModelScope.launch {
            val updated = runCatching {
                repository.replaceReferenceMaterials(
                    monitorId,
                    current.expectedRevision,
                    current.materials,
                )
            }.getOrNull()
            if (updated == null) {
                val latestRevision = state.value.local.singleOrNull {
                    it.monitor.id == monitorId
                }?.monitor?.revision
                mutableReferenceEditor.value = mutableReferenceEditor.value?.copy(
                    expectedRevision = latestRevision ?: current.expectedRevision,
                    saving = false,
                    message = uiText(R.string.error_reference_images_save_failed),
                )
            } else {
                referenceDraftSession = null
                mutableReferenceEditor.value = null
                mutableReferencePreview.value = runCatching {
                    ReferencePreviewState.Ready(repository.loadReferenceMaterials(monitorId))
                }.getOrElse {
                    ReferencePreviewState.Failed(uiText(R.string.error_reference_images_read_failed))
                }
            }
        }
    }

    private fun acceptReferenceEditImport(result: ReferenceImportResult) {
        val notices = buildList {
            if (result.duplicateCount > 0) {
                add(pluralText(R.plurals.reference_duplicates_skipped, result.duplicateCount, result.duplicateCount))
            }
            if (result.unreadableCount > 0) {
                add(pluralText(R.plurals.reference_unreadable_photos, result.unreadableCount, result.unreadableCount))
            }
        }
        val detail = when (notices.size) {
            0 -> null
            1 -> notices.single()
            else -> uiText(R.string.text_pair, notices[0], notices[1])
        }
        mutableReferenceEditor.value = mutableReferenceEditor.value?.let { state ->
            state.copy(
                materials = state.materials + result.accepted,
                importing = false,
                message = detail,
            )
        }
    }

    private fun discardReferenceEditorDrafts(state: ReferenceEditorState) {
        val originalSources = state.original.mapTo(hashSetOf(), ReferenceMaterial::sourceUri)
        repository.discardReferenceDrafts(
            state.materials.filterNot { it.sourceUri in originalSources },
        )
    }

    fun delete(onDeleted: () -> Unit) {
        viewModelScope.launch { if (repository.delete(monitorId)) onDeleted() }
    }

    override fun onCleared() {
        mutableReferenceEditor.value?.let(::discardReferenceEditorDrafts)
        super.onCleared()
    }
}

internal suspend fun resolveModelDisclosureState(
    resolveDisplayName: suspend () -> String?,
): ModelDisclosureState = try {
    resolveDisplayName()?.let(ModelDisclosureState::Ready)
        ?: ModelDisclosureState.Unavailable
} catch (cancellation: CancellationException) {
    throw cancellation
} catch (_: Exception) {
    ModelDisclosureState.Unavailable
}
