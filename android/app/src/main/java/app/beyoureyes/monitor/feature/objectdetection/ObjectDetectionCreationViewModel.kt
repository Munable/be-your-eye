package app.beyoureyes.monitor.feature.objectdetection

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.beyoureyes.core.domain.MonitorRule
import app.beyoureyes.core.domain.MonitorTarget
import app.beyoureyes.core.domain.ObjectClassCatalog
import app.beyoureyes.core.domain.ObjectClassDefinition
import app.beyoureyes.core.domain.ObjectClassLookup
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

internal fun interface ObjectTargetCatalogProvider {
    suspend fun load(): List<ObjectClassDefinition>
}

internal data class ObjectDetectionCreationState(
    val query: String = "",
    val selected: ObjectClassDefinition? = null,
    val suggestions: List<ObjectClassDefinition> = emptyList(),
    val loading: Boolean = true,
    val error: ObjectCreationError? = null,
    val catalogUnavailable: Boolean = false,
)

internal enum class ObjectCreationError {
    CATALOG_UNAVAILABLE,
    NO_MATCH,
}

internal data class ObjectDetectionModelBinding(
    val modelProfileKey: String,
    val packageId: String,
    val intentKey: String,
)

internal class ObjectDetectionCreationViewModel(
    private val catalogProvider: ObjectTargetCatalogProvider,
    private val initialTargetId: String? = null,
    private val initialTargetQuery: String? = null,
    private val languageTag: String = "en",
    initialModelBinding: ObjectDetectionModelBinding? = null,
    initialRule: MonitorRule.TargetPresence = MonitorRule.TargetPresence(),
    initialNotificationsEnabled: Boolean = false,
) : ViewModel() {
    private val mutableState = MutableStateFlow(ObjectDetectionCreationState())
    val state: StateFlow<ObjectDetectionCreationState> = mutableState.asStateFlow()
    private var catalog: ObjectClassCatalog? = null
    private var cameraRequested = false
    private var modelBinding = initialModelBinding
    private var initialSelectionPending = initialTargetId != null
    private var loadJob: Job? = null
    private val mutableRule = MutableStateFlow(initialRule)
    val rule: StateFlow<MonitorRule.TargetPresence> = mutableRule.asStateFlow()
    private val mutableNotificationsEnabled = MutableStateFlow(initialNotificationsEnabled)
    val notificationsEnabled: StateFlow<Boolean> = mutableNotificationsEnabled.asStateFlow()

    init {
        require((initialTargetId == null) == (initialModelBinding == null)) {
            "assistant target and model binding must be supplied together"
        }
        loadCatalog()
    }

    private fun loadCatalog() {
        if (loadJob?.isActive == true) return
        catalog = null
        cameraRequested = false
        mutableState.value = mutableState.value.copy(
            selected = null,
            suggestions = emptyList(),
            loading = true,
            error = null,
            catalogUnavailable = false,
        )
        loadJob = viewModelScope.launch {
            try {
                val loaded = ObjectClassCatalog(catalogProvider.load())
                catalog = loaded
                val currentQuery = mutableState.value.query
                when {
                    initialSelectionPending && currentQuery.isBlank() -> {
                        val definition = loaded.definitions.singleOrNull {
                            it.targetId == initialTargetId
                        } ?: throw IllegalStateException(
                            "assistant target is not in current signed object metadata",
                        )
                        mutableState.value = ObjectDetectionCreationState(
                            query = initialTargetQuery ?: definition.localizedLabel(languageTag),
                            selected = definition,
                            suggestions = listOf(definition),
                            loading = false,
                        )
                        initialSelectionPending = false
                    }
                    else -> applyQuery(currentQuery, loaded)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Throwable) {
                catalog = null
                modelBinding = null
                mutableState.value = mutableState.value.copy(
                    selected = null,
                    suggestions = emptyList(),
                    loading = false,
                    error = ObjectCreationError.CATALOG_UNAVAILABLE,
                    catalogUnavailable = true,
                )
            }
        }
    }

    fun retryCatalogLoad() {
        if (mutableState.value.loading || !mutableState.value.catalogUnavailable) return
        loadCatalog()
    }

    fun onQueryChanged(value: String) {
        cameraRequested = false
        initialSelectionPending = false
        val loaded = catalog
        if (loaded == null) {
            modelBinding = null
            mutableState.value = mutableState.value.copy(
                query = value,
                selected = null,
                suggestions = emptyList(),
                error = ObjectCreationError.CATALOG_UNAVAILABLE.takeIf {
                    mutableState.value.catalogUnavailable
                },
            )
            return
        }
        applyQuery(value, loaded)
    }

    private fun applyQuery(value: String, loaded: ObjectClassCatalog) {
        if (ObjectClassCatalog.normalize(value).isEmpty()) {
            modelBinding = null
            mutableState.value = ObjectDetectionCreationState(
                query = value,
                suggestions = loaded.definitions.take(3),
                loading = false,
            )
            return
        }
        val lookup = loaded.lookup(value)
        if ((lookup as? ObjectClassLookup.Matched)?.definition?.targetId !=
            mutableState.value.selected?.targetId
        ) {
            modelBinding = null
        }
        mutableState.value = when (lookup) {
            is ObjectClassLookup.Matched -> ObjectDetectionCreationState(
                query = value,
                selected = lookup.definition,
                suggestions = listOf(lookup.definition),
                loading = false,
            )
            is ObjectClassLookup.NotFound -> {
                val suggestions = matchingSuggestions(value, loaded)
                ObjectDetectionCreationState(
                    query = value,
                    suggestions = suggestions,
                    loading = false,
                    error = if (suggestions.isEmpty()) ObjectCreationError.NO_MATCH else null,
                )
            }
        }
    }

    private fun matchingSuggestions(
        value: String,
        loaded: ObjectClassCatalog,
    ): List<ObjectClassDefinition> {
        val query = ObjectClassCatalog.normalize(value)
        if (query.isEmpty()) return emptyList()
        return loaded.definitions.filter { definition ->
            (definition.aliases + definition.labelZhCn + definition.labelEn + definition.labels.values).any { label ->
                ObjectClassCatalog.normalize(label).contains(query)
            }
        }.take(MAX_SUGGESTIONS)
    }

    fun select(definition: ObjectClassDefinition) {
        val signedDefinition = catalog?.definitions?.singleOrNull {
            it.targetId == definition.targetId && it == definition
        } ?: return
        cameraRequested = false
        if (signedDefinition.targetId != mutableState.value.selected?.targetId) modelBinding = null
        mutableState.value = mutableState.value.copy(
            query = signedDefinition.localizedLabel(languageTag),
            selected = signedDefinition,
            suggestions = listOf(signedDefinition),
            error = null,
        )
    }

    fun continueToCamera(): Boolean {
        if (mutableState.value.loading || mutableState.value.selected == null || cameraRequested) return false
        cameraRequested = true
        return true
    }

    /** 配置完整即可创建并启动：不经过相机，目标不需要在场。 */
    fun continueToStart(): Boolean {
        if (mutableState.value.loading || mutableState.value.selected == null || cameraRequested) return false
        cameraRequested = true
        return true
    }

    fun resetCameraRequest() {
        cameraRequested = false
    }

    fun setPresenceRule(value: MonitorRule.TargetPresence) {
        if (cameraRequested) return
        mutableRule.value = value
    }

    fun setNotificationsEnabled(enabled: Boolean) {
        mutableNotificationsEnabled.value = enabled
    }

    fun modelBinding(): ObjectDetectionModelBinding? = modelBinding

    fun presenceRule(): MonitorRule.TargetPresence = mutableRule.value

    fun notificationsEnabled(): Boolean = mutableNotificationsEnabled.value

    fun target(): MonitorTarget.ObjectClass? = mutableState.value.selected?.let { definition ->
        MonitorTarget.ObjectClass(
            targetId = definition.targetId,
            labelZhCn = definition.labelZhCn,
            labelEn = definition.labelEn,
            labels = definition.labels,
        )
    }

    private companion object {
        const val MAX_SUGGESTIONS = 20
    }
}
