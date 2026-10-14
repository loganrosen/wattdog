package io.github.loganrosen.wattdog.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.loganrosen.wattdog.AppContainer
import io.github.loganrosen.wattdog.alerts.AlertState
import io.github.loganrosen.wattdog.batterystats.analysis.SuggestedAction
import io.github.loganrosen.wattdog.batterystats.model.BatteryHistory
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
import kotlinx.coroutines.flow.asStateFlow
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
    val history: HistoryLoad = HistoryLoad(),
)

data class HistoryLoad(val loading: Boolean = false, val history: BatteryHistory? = null, val error: String? = null)

class MainViewModel(private val container: AppContainer) : ViewModel() {
    private val load = MutableStateFlow(LoadState())
    private val history = MutableStateFlow(HistoryLoad())
    private var loadJob: Job? = null
    private var historyJob: Job? = null

    private data class LoadState(
        val baseline: Baseline = Baseline.SinceCharge,
        val loading: Boolean = false,
        val analysis: Analysis? = null,
        val error: String? = null,
        val message: String? = null,
    )

    val state: StateFlow<UiState> = combine(container.access.state, container.snapshots.snapshots, load, history) { access, snaps, l, h ->
        UiState(access, l.baseline, snaps, l.loading, l.analysis, l.error, l.message, h)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), UiState(container.access.state.value))

    init {
        viewModelScope.launch { container.snapshots.load() }
        viewModelScope.launch {
            // Automatic snapshots get pruned in the background; don't keep pointing at a deleted one.
            container.snapshots.snapshots.collect { snaps ->
                val selected = (load.value.baseline as? Baseline.Snapshot)?.meta ?: return@collect
                if (snaps.none { it.id == selected.id }) selectBaseline(Baseline.SinceCharge)
            }
        }
        viewModelScope.launch {
            container.access.state.map { it.mode }.distinctUntilChanged().collect { mode ->
                if (mode != AccessMode.NONE) refresh()
            }
        }
    }

    private val _pendingAction = MutableStateFlow<SuggestedAction?>(null)

    /** An action from a tapped notification, waiting for the UI to route it. */
    val pendingAction: StateFlow<SuggestedAction?> = _pendingAction.asStateFlow()

    val backgroundChecksEnabled: StateFlow<Boolean> = container.backgroundChecks.enabled
    val alertState: StateFlow<AlertState> = container.alertStore.state

    fun openAction(action: SuggestedAction) {
        _pendingAction.value = action
    }

    fun actionHandled() {
        _pendingAction.value = null
    }

    fun setBackgroundChecks(enabled: Boolean) {
        viewModelScope.launch { container.backgroundChecks.setEnabled(enabled) }
    }

    fun checkNow() = container.backgroundChecks.checkNow()

    fun recheckAccess() = container.access.refresh()

    fun requestShizukuPermission() = container.access.requestShizukuPermission()

    fun shizukuLaunchIntent() = container.access.shizukuLaunchIntent()

    fun selectBaseline(baseline: Baseline) {
        load.update { it.copy(baseline = baseline) }
        // The timeline is re-windowed in the UI; only the stats need re-reading.
        analyze()
    }

    fun refresh() {
        analyze()
        if (history.value.history != null) loadHistory(force = true)
    }

    private fun analyze() {
        if (container.access.state.value.mode == AccessMode.NONE) return
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            load.update { it.copy(loading = true, error = null) }
            try {
                val analysis = container.repository.analyze(load.value.baseline)
                load.update { it.copy(loading = false, analysis = analysis) }
            } catch (_: StatsResetException) {
                // The phone charged or rebooted since the chosen starting point; it can't come back.
                load.update {
                    it.copy(
                        baseline = Baseline.SinceCharge,
                        loading = false,
                        message = "Stats were reset since then (charged or rebooted). Showing since unplugged.",
                    )
                }
                analyze()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                load.update { it.copy(loading = false, error = describe(e)) }
            }
        }
    }

    /** Loads `--history` on first use; [force] re-reads it, e.g. on refresh. */
    fun loadHistory(force: Boolean = false) {
        if (container.access.state.value.mode == AccessMode.NONE) return
        val current = history.value
        if (!force && (current.loading || current.history != null)) return
        historyJob?.cancel()
        historyJob = viewModelScope.launch {
            history.update { it.copy(loading = true, error = null) }
            try {
                val h = container.repository.history()
                history.update { HistoryLoad(history = h) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                history.update { it.copy(loading = false, error = describe(e)) }
            }
        }
    }

    fun takeSnapshot(label: String) {
        viewModelScope.launch {
            try {
                val meta = container.repository.takeSnapshot(label)
                load.update { it.copy(message = "Marked \"${meta.label}\". Pick it from the title menu later.") }
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
        is DumpPermissionDeniedException ->
            "The system refused the dump. If you granted DUMP over adb, also grant PACKAGE_USAGE_STATS.\n${e.message}"

        is BatteryStatsParseException -> "Couldn't read batterystats output: ${e.message}"

        else -> e.message ?: e.toString()
    }
}
