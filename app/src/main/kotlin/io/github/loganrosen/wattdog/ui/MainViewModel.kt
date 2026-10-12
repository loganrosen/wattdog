package io.github.loganrosen.wattdog.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.loganrosen.wattdog.AppContainer
import io.github.loganrosen.wattdog.batterystats.parse.BatteryStatsParseException
import io.github.loganrosen.wattdog.data.Analysis
import io.github.loganrosen.wattdog.data.Baseline
import io.github.loganrosen.wattdog.data.SnapshotMeta
import io.github.loganrosen.wattdog.data.StatsResetException
import io.github.loganrosen.wattdog.privileged.AccessMode
import io.github.loganrosen.wattdog.privileged.AccessState
import io.github.loganrosen.wattdog.privileged.DumpPermissionDeniedException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class UiState(
    val access: AccessState,
    val baseline: Baseline = Baseline.SinceCharge,
    val snapshots: List<SnapshotMeta> = emptyList(),
    val loading: Boolean = false,
    val analysis: Analysis? = null,
    val error: String? = null,
    val message: String? = null,
)

class MainViewModel(private val container: AppContainer) : ViewModel() {
    private val load = MutableStateFlow(LoadState())
    private var loadJob: Job? = null

    private data class LoadState(
        val baseline: Baseline = Baseline.SinceCharge,
        val loading: Boolean = false,
        val analysis: Analysis? = null,
        val error: String? = null,
        val message: String? = null,
    )

    val state: StateFlow<UiState> = combine(container.access.state, container.snapshots.snapshots, load) { access, snaps, l ->
        UiState(access, l.baseline, snaps, l.loading, l.analysis, l.error, l.message)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), UiState(container.access.state.value))

    init {
        viewModelScope.launch { container.snapshots.load() }
        viewModelScope.launch {
            container.access.state.map { it.mode }.distinctUntilChanged().collect { mode ->
                if (mode != AccessMode.NONE) refresh()
            }
        }
    }

    fun onResume() = container.access.refresh()

    fun requestShizukuPermission() = container.access.requestShizukuPermission()

    fun shizukuLaunchIntent() = container.access.shizukuLaunchIntent()

    fun selectBaseline(baseline: Baseline) {
        load.update { it.copy(baseline = baseline) }
        refresh()
    }

    fun refresh() {
        if (container.access.state.value.mode == AccessMode.NONE) return
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            load.update { it.copy(loading = true, error = null) }
            try {
                val analysis = container.repository.analyze(load.value.baseline)
                load.update { it.copy(loading = false, analysis = analysis) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                load.update { it.copy(loading = false, error = describe(e)) }
            }
        }
    }

    fun takeSnapshot(label: String) {
        viewModelScope.launch {
            try {
                val meta = container.repository.takeSnapshot(label)
                load.update { it.copy(message = "Snapshot \"${meta.label}\" saved") }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                load.update { it.copy(error = describe(e)) }
            }
        }
    }

    fun deleteSnapshot(meta: SnapshotMeta) {
        viewModelScope.launch {
            container.snapshots.delete(meta.id)
            if ((load.value.baseline as? Baseline.Snapshot)?.meta?.id == meta.id) selectBaseline(Baseline.SinceCharge)
        }
    }

    fun messageShown() = load.update { it.copy(message = null) }

    private fun describe(e: Exception): String = when (e) {
        is StatsResetException -> "${e.message}. Pick a newer snapshot or view since charge."

        is DumpPermissionDeniedException ->
            "The system refused the dump. If you granted DUMP over adb, also grant PACKAGE_USAGE_STATS.\n${e.message}"

        is BatteryStatsParseException -> "Couldn't read batterystats output: ${e.message}"

        else -> e.message ?: e.toString()
    }
}
