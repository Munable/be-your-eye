package app.beyoureyes.monitor.feature.reference

import android.net.Uri
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.beyoureyes.core.data.MonitorRepository
import app.beyoureyes.core.data.reference.ReferenceImportResult
import app.beyoureyes.core.domain.MAX_REFERENCE_IMAGES
import app.beyoureyes.core.domain.MonitorRule
import app.beyoureyes.core.domain.PresenceRuleKind
import app.beyoureyes.core.domain.ReferenceMaterial
import app.beyoureyes.core.domain.materialSufficiency
import java.io.File
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

internal data class ReferenceCreationState(
    val name: String = "",
    val materials: List<ReferenceMaterial> = emptyList(),
    val rule: MonitorRule.TargetPresence = MonitorRule.TargetPresence(),
    val notificationsEnabled: Boolean = false,
    val importing: Boolean = false,
    val saving: Boolean = false,
    val message: ReferenceCreationMessage? = null,
) {
    val sufficiency get() = materialSufficiency(materials.size)
}

internal sealed interface ReferenceCreationMessage {
    data object DraftSaveFailed : ReferenceCreationMessage
    data object ImageReadFailed : ReferenceCreationMessage
    data object PhotoRemoveFailed : ReferenceCreationMessage
    data object PhotoDraftSaveFailed : ReferenceCreationMessage
    data class ImportSummary(
        val duplicateCount: Int,
        val unreadableCount: Int,
    ) : ReferenceCreationMessage
}

internal class ReferenceCreationViewModel(
    private val repository: MonitorRepository,
    private val savedStateHandle: SavedStateHandle,
    initialName: String = "",
    private val initialRule: MonitorRule.TargetPresence = MonitorRule.TargetPresence(),
    private val initialNotificationsEnabled: Boolean = false,
    private val requiredModelProfileKey: String? = null,
    private val requiredPackageId: String? = null,
    private val requiredIntentKey: String? = null,
) : ViewModel() {
    init {
        require(
            listOf(requiredModelProfileKey, requiredPackageId, requiredIntentKey)
                .all { it == null } ||
                listOf(requiredModelProfileKey, requiredPackageId, requiredIntentKey)
                    .all { !it.isNullOrBlank() },
        ) { "reference model binding must be complete" }
    }

    private val draftSession: File
    private val draftSessionId: String
    private val mutableState: MutableStateFlow<ReferenceCreationState>
    private val mutableCameraRequested = MutableStateFlow(false)
    val cameraRequested: StateFlow<Boolean> = mutableCameraRequested.asStateFlow()
    private var completed = false
    private val mutableStartRequested = MutableStateFlow(false)
    val startRequested: StateFlow<Boolean> = mutableStartRequested.asStateFlow()
    private val startingRule = runCatching {
        MonitorRule.TargetPresence(
            kind = savedStateHandle.get<String>(KEY_RULE_KIND)
                ?.let(PresenceRuleKind::valueOf)
                ?: initialRule.kind,
            durationSeconds = savedStateHandle.get<Int>(KEY_RULE_DURATION_SECONDS)
                ?: initialRule.durationSeconds,
        )
    }.getOrDefault(initialRule)
    private val startingNotificationsEnabled =
        savedStateHandle.get<Boolean>(KEY_NOTIFICATIONS_ENABLED) ?: initialNotificationsEnabled

    init {
        val savedSessionId = savedStateHandle.get<String>(KEY_SESSION_ID)
        val restored = savedSessionId?.let(repository::restoreReferenceDraftSession)
        if (restored != null) {
            draftSession = restored.first
            draftSessionId = requireNotNull(savedSessionId)
            mutableState = MutableStateFlow(
                ReferenceCreationState(
                    name = restored.second,
                    materials = restored.third,
                    rule = startingRule,
                    notificationsEnabled = startingNotificationsEnabled,
                ),
            )
        } else {
            draftSession = repository.newReferenceDraftSession()
            draftSessionId = repository.referenceDraftSessionId(draftSession)
            mutableState = MutableStateFlow(
                ReferenceCreationState(
                    name = initialName.take(100),
                    rule = startingRule,
                    notificationsEnabled = startingNotificationsEnabled,
                ),
            )
            repository.persistReferenceDraftSession(draftSession, initialName.take(100), emptyList())
        }
        savedStateHandle[KEY_SESSION_ID] = draftSessionId
        persistRule(startingRule)
        savedStateHandle[KEY_NOTIFICATIONS_ENABLED] = startingNotificationsEnabled
    }

    val state: StateFlow<ReferenceCreationState> = mutableState.asStateFlow()

    fun setName(value: String) {
        if (mutableState.value.saving) return
        val name = value.take(100)
        runCatching {
            repository.persistReferenceDraftSession(draftSession, name, mutableState.value.materials)
        }.onSuccess {
            mutableState.update { it.copy(name = name, message = null) }
        }.onFailure {
            mutableState.update { it.copy(message = ReferenceCreationMessage.DraftSaveFailed) }
        }
    }

    fun setPresenceRule(value: MonitorRule.TargetPresence) {
        if (mutableState.value.saving) return
        persistRule(value)
        mutableState.update { it.copy(rule = value, message = null) }
    }

    fun setNotificationsEnabled(enabled: Boolean) {
        savedStateHandle[KEY_NOTIFICATIONS_ENABLED] = enabled
        mutableState.update { it.copy(notificationsEnabled = enabled) }
    }

    fun addImages(uris: List<Uri>) {
        if (uris.isEmpty() || mutableState.value.importing || mutableState.value.saving) return
        mutableState.update { it.copy(importing = true, message = null) }
        viewModelScope.launch {
            runCatching {
                repository.importReferenceImages(
                    uris.take(MAX_REFERENCE_IMAGES - mutableState.value.materials.size),
                    mutableState.value.materials,
                    draftSession,
                )
            }.onSuccess(::acceptImport).onFailure {
                mutableState.update { state ->
                    state.copy(importing = false, message = ReferenceCreationMessage.ImageReadFailed)
                }
            }
        }
    }

    fun remove(material: ReferenceMaterial) {
        val current = mutableState.value
        if (current.saving) return
        val remaining = current.materials - material
        runCatching {
            repository.persistReferenceDraftSession(draftSession, current.name, remaining)
        }.onSuccess {
            repository.discardReferenceDrafts(listOf(material))
            mutableState.update { it.copy(materials = remaining, message = null) }
        }.onFailure {
            mutableState.update { it.copy(message = ReferenceCreationMessage.PhotoRemoveFailed) }
        }
    }

    fun createAndOpen() {
        val current = mutableState.value
        if (!current.sufficiency.canContinue || current.saving) return
        mutableState.update { it.copy(saving = true, message = null) }
        mutableCameraRequested.value = true
    }

    /** 配置完整即可创建并启动：不经过相机，目标不需要在场。 */
    fun createAndStart() {
        val current = mutableState.value
        if (!current.sufficiency.canContinue || current.saving) return
        mutableState.update { it.copy(saving = true, message = null) }
        mutableStartRequested.value = true
    }

    fun acknowledgeStartRequest() {
        mutableStartRequested.value = false
    }

    private fun acceptImport(result: ReferenceImportResult) {
        val detail = if (result.duplicateCount > 0 || result.unreadableCount > 0) {
            ReferenceCreationMessage.ImportSummary(
                duplicateCount = result.duplicateCount,
                unreadableCount = result.unreadableCount,
            )
        } else {
            null
        }
        val current = mutableState.value
        val materials = current.materials + result.accepted
        runCatching {
            repository.persistReferenceDraftSession(draftSession, current.name, materials)
        }.onSuccess {
            mutableState.update {
                it.copy(materials = materials, importing = false, message = detail)
            }
        }.onFailure {
            repository.discardReferenceDrafts(result.accepted)
            mutableState.update {
                it.copy(importing = false, message = ReferenceCreationMessage.PhotoDraftSaveFailed)
            }
        }
    }

    fun draft(): ReferenceDraft? {
        val current = mutableState.value
        return current.sufficiency.canContinue
            .takeIf { it }
            ?.let {
                ReferenceDraft(
                    requestedName = current.name,
                    materials = current.materials.toList(),
                    rule = current.rule,
                    notificationsEnabled = current.notificationsEnabled,
                    requiredModelProfileKey = requiredModelProfileKey,
                    requiredPackageId = requiredPackageId,
                    requiredIntentKey = requiredIntentKey,
                )
            }
    }

    fun acknowledgeCameraRequest() {
        mutableCameraRequested.value = false
    }

    fun resumeAfterCamera() {
        if (completed) return
        mutableCameraRequested.value = false
        mutableStartRequested.value = false
        mutableState.update { it.copy(saving = false, message = null) }
    }

    fun completeCameraCreation() {
        if (completed) return
        completed = true
        mutableCameraRequested.value = false
        repository.discardReferenceDraftSession(draftSessionId)
        savedStateHandle.remove<String>(KEY_SESSION_ID)
        savedStateHandle.remove<String>(KEY_RULE_KIND)
        savedStateHandle.remove<Int>(KEY_RULE_DURATION_SECONDS)
        savedStateHandle.remove<Boolean>(KEY_NOTIFICATIONS_ENABLED)
    }

    override fun onCleared() {
        // 离开创建流程不再丢弃草稿：首页的草稿恢复卡片让用户从已选素材继续。
        // 草稿只在创建完成（completeCameraCreation）后回收；空草稿由首页读取时顺手清理。
        super.onCleared()
    }

    private fun persistRule(rule: MonitorRule.TargetPresence) {
        savedStateHandle[KEY_RULE_KIND] = rule.kind.name
        savedStateHandle[KEY_RULE_DURATION_SECONDS] = rule.durationSeconds
    }

    internal companion object {
        const val DRAFT_SESSION_KEY = "reference_creation_session_id"
        private const val KEY_SESSION_ID = DRAFT_SESSION_KEY
        const val KEY_RULE_KIND = "reference_creation_rule_kind"
        const val KEY_RULE_DURATION_SECONDS = "reference_creation_rule_duration_seconds"
        const val KEY_NOTIFICATIONS_ENABLED = "reference_creation_notifications_enabled"
    }
}
