package io.github.loganrosen.wattdog.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.loganrosen.wattdog.batterystats.analysis.DrainCost
import io.github.loganrosen.wattdog.batterystats.analysis.formatBatteryShare
import io.github.loganrosen.wattdog.batterystats.analysis.formatShort
import io.github.loganrosen.wattdog.batterystats.model.KernelWakelock
import io.github.loganrosen.wattdog.batterystats.model.PartialWakelock
import io.github.loganrosen.wattdog.batterystats.model.WakelockKind
import io.github.loganrosen.wattdog.data.Analysis
import io.github.loganrosen.wattdog.ui.Focus
import io.github.loganrosen.wattdog.ui.components.Badge
import io.github.loganrosen.wattdog.ui.components.BarRow
import io.github.loganrosen.wattdog.ui.components.EmptyNote
import kotlin.time.DurationUnit

private enum class WakelockView(val label: String) {
    PARTIAL("App wakelocks"),
    KERNEL("Kernel"),
    WAKEUP("Wakeup reasons"),
}

@Composable
fun WakelocksScreen(analysis: Analysis, modifier: Modifier = Modifier, focus: Focus.Wakelocks? = null) {
    var view by rememberSaveable { mutableStateOf(WakelockView.PARTIAL) }
    var kind by rememberSaveable { mutableStateOf<WakelockKind?>(null) }
    val report = analysis.report

    LaunchedEffect(focus) {
        if (focus != null) {
            view = WakelockView.PARTIAL
            kind = focus.kind
        }
    }

    Column(modifier.fillMaxSize()) {
        Row(
            Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            WakelockView.entries.forEach { v ->
                FilterChip(selected = view == v, onClick = { view = v }, label = { Text(v.label) })
            }
        }
        if (view == WakelockView.PARTIAL) {
            Row(
                Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                FilterChip(selected = kind == null, onClick = { kind = null }, label = { Text("All") })
                WakelockKind.entries.forEach { k ->
                    FilterChip(
                        selected = kind == k,
                        onClick = { kind = if (kind == k) null else k },
                        label = { Text(k.label()) },
                    )
                }
            }
        }

        when (view) {
            WakelockView.PARTIAL -> {
                val items = remember(report, kind) {
                    report.partialWakelocks.filter { kind == null || it.kind == kind }.sortedByDescending { it.total }
                }
                PartialList(items, analysis, focus)
            }

            WakelockView.KERNEL -> KernelList(report.kernelWakelocks, "No kernel wakelocks recorded.")

            WakelockView.WAKEUP -> KernelList(report.wakeupReasons, "No wakeup reasons recorded.")
        }
    }
}

@Composable
private fun PartialList(items: List<PartialWakelock>, analysis: Analysis, focus: Focus.Wakelocks?) {
    val max = items.firstOrNull()?.total?.toDouble(DurationUnit.MILLISECONDS) ?: 0.0
    fun PartialWakelock.focused() = focus?.tag != null && tag == focus.tag && (focus.uid == null || uid == focus.uid)
    val listState = rememberLazyListState()
    LaunchedEffect(focus, items) {
        val index = items.indexOfFirst { it.focused() }
        if (index >= 0) listState.animateScrollToItem(index)
    }
    LazyColumn(
        Modifier.fillMaxSize(),
        state = listState,
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        if (items.isEmpty()) item { EmptyNote("No partial wakelocks in this period.") }
        items(items, key = { "${it.uid.raw}/${it.tag}" }) { wl ->
            val heldFor = wl.runningFor
            val phantom = analysis.phantoms.isPhantom(wl)
            BarRow(
                modifier = if (wl.focused()) {
                    Modifier.background(MaterialTheme.colorScheme.secondaryContainer, RoundedCornerShape(8.dp)).padding(8.dp)
                } else {
                    Modifier
                },
                title = wl.tag,
                value = wl.total.formatShort(),
                fraction = if (max > 0) (wl.total.toDouble(DurationUnit.MILLISECONDS) / max).toFloat() else 0f,
                subtitle = buildString {
                    append(analysis.label(wl.uid).label)
                    if (wl.count > 0) append(" · ${wl.count}×")
                    val cost = analysis.wakelockCosts.of(wl, DrainCost.Basis.ESTIMATED)?.ofBattery
                    if (cost != null && !phantom) append(" · ≈${formatBatteryShare(cost)}")
                    if (phantom) {
                        append(" · stuck stats timer, not real drain")
                    } else if (heldFor != null) {
                        append(" · held for ${heldFor.formatShort()}")
                    }
                },
                badge = {
                    when {
                        phantom -> Badge("PHANTOM", MaterialTheme.colorScheme.outline)
                        wl.isHeld -> Badge("HELD", MaterialTheme.colorScheme.error)
                        wl.kind != WakelockKind.OTHER -> Badge(wl.kind.label().uppercase(), MaterialTheme.colorScheme.secondary)
                    }
                },
            )
        }
    }
}

@Composable
private fun KernelList(items: List<KernelWakelock>, emptyText: String) {
    val sorted = remember(items) { items.sortedByDescending { it.total } }
    val max = sorted.firstOrNull()?.total?.toDouble(DurationUnit.MILLISECONDS) ?: 0.0
    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        if (sorted.isEmpty()) item { EmptyNote(emptyText) }
        items(sorted, key = { it.name }) { wl ->
            BarRow(
                title = wl.name,
                value = wl.total.formatShort(),
                fraction = if (max > 0) (wl.total.toDouble(DurationUnit.MILLISECONDS) / max).toFloat() else 0f,
                subtitle = "${wl.count}×",
            )
        }
    }
}

private fun WakelockKind.label() = when (this) {
    WakelockKind.AUDIO -> "Audio"
    WakelockKind.JOB -> "Jobs"
    WakelockKind.SYNC -> "Sync"
    WakelockKind.ALARM -> "Alarms"
    WakelockKind.LOCATION -> "Location"
    WakelockKind.OTHER -> "Other"
}
