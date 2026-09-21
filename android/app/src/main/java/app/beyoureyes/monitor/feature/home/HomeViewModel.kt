package app.beyoureyes.monitor.feature.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.beyoureyes.core.data.MonitorRepository
import app.beyoureyes.core.data.MonitorRepositoryState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** 首页「继续设置」卡片的草稿摘要；sessionId 是不透明标识，不暴露文件路径。 */
internal data class ReferenceDraftSummary(
    val sessionId: String,
    val name: String,
    val materialCount: Int,
)

internal class HomeViewModel(
    private val repository: MonitorRepository,
) : ViewModel() {
    val monitors: StateFlow<MonitorRepositoryState> = repository.state
    private val mutableReferenceDraft = MutableStateFlow<ReferenceDraftSummary?>(null)
    val referenceDraft: StateFlow<ReferenceDraftSummary?> = mutableReferenceDraft.asStateFlow()

    init {
        refreshReferenceDraft()
    }

    /** 回到首页时刷新；空草稿会被仓库层顺手清理。 */
    fun refreshReferenceDraft() {
        viewModelScope.launch(Dispatchers.IO) {
            mutableReferenceDraft.value = runCatching { repository.latestReferenceDraft() }
                .getOrNull()
                ?.let { (sessionId, name, materials) ->
                    ReferenceDraftSummary(sessionId, name, materials.size)
                }
        }
    }
}
