package io.github.loganrosen.wattdog.ui.screens

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
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
import io.github.loganrosen.wattdog.batterystats.analysis.formatBytes
import io.github.loganrosen.wattdog.batterystats.analysis.formatMah
import io.github.loganrosen.wattdog.batterystats.analysis.formatShort
import io.github.loganrosen.wattdog.batterystats.model.AppUid
import io.github.loganrosen.wattdog.batterystats.model.PartialWakelock
import io.github.loganrosen.wattdog.batterystats.model.UidDetail
import io.github.loganrosen.wattdog.batterystats.model.UidPower
import io.github.loganrosen.wattdog.data.Analysis
import io.github.loganrosen.wattdog.ui.components.BarRow
import io.github.loganrosen.wattdog.ui.components.EmptyNote
import io.github.loganrosen.wattdog.ui.components.StatRow

@Composable
fun AppsScreen(analysis: Analysis, focusUid: AppUid? = null) {
    val report = analysis.report
    val apps = remember(report) { report.uidPower.sortedByDescending { it.totalMah } }
    val wakelocksByUid = remember(report) { report.partialWakelocks.groupBy { it.uid } }
    var expanded by rememberSaveable { mutableStateOf<Int?>(null) }
    val max = apps.firstOrNull()?.totalMah ?: 0.0
    val listState = rememberLazyListState()

    LaunchedEffect(focusUid, apps) {
        val index = apps.indexOfFirst { it.uid == focusUid }
        if (index >= 0) {
            expanded = focusUid?.raw
            listState.animateScrollToItem(index)
        }
    }

    LazyColumn(
        Modifier.fillMaxSize(),
        state = listState,
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (apps.isEmpty()) item { EmptyNote("No per-app power estimates in this period.") }
        items(apps, key = { it.uid.raw }) { app ->
            val open = expanded == app.uid.raw
            AppCard(
                app = app,
                analysis = analysis,
                detail = report.uids[app.uid],
                wakelocks = wakelocksByUid[app.uid].orEmpty(),
                fraction = if (max > 0) (app.totalMah / max).toFloat() else 0f,
                expanded = open,
                onClick = { expanded = if (open) null else app.uid.raw },
            )
        }
    }
}

@Composable
private fun AppCard(
    app: UidPower,
    analysis: Analysis,
    detail: UidDetail?,
    wakelocks: List<PartialWakelock>,
    fraction: Float,
    expanded: Boolean,
    onClick: () -> Unit,
) {
    val label = analysis.label(app.uid)
    Card(Modifier.fillMaxWidth().clickable(onClick = onClick).animateContentSize()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            BarRow(
                title = label.label,
                value = formatMah(app.totalMah),
                fraction = fraction,
                subtitle = (label.primaryPackage ?: app.uid.format()).let { if (label.isSystem) "$it · system" else it },
            )
            if (expanded) Details(app, detail, wakelocks)
        }
    }
}

@Composable
private fun Details(app: UidPower, detail: UidDetail?, wakelocks: List<PartialWakelock>) {
    HorizontalDivider(Modifier.padding(vertical = 4.dp))
    StatRow("UID", app.uid.format())
    StatRow("Foreground", formatMah(app.foregroundMah))
    StatRow("Background", formatMah(app.backgroundMah))
    StatRow("Foreground service", formatMah(app.foregroundServiceMah))
    StatRow("Cached", formatMah(app.cachedMah))
    app.components.entries.sortedByDescending { it.value }.filter { it.value > 0 }.forEach { (name, mah) ->
        StatRow("  ${name.replace('_', ' ')}", formatMah(mah))
    }

    if (detail != null) {
        if (detail.mobileBytes > 0 || detail.wifiRxBytes + detail.wifiTxBytes > 0) {
            Subhead("Network")
            StatRow("Mobile data", formatBytes(detail.mobileBytes))
            StatRow("Wi-Fi data", formatBytes(detail.wifiRxBytes + detail.wifiTxBytes))
            StatRow("Mobile radio active", "${detail.mobileRadioActive.formatShort()} (${detail.mobileRadioActiveCount}×)")
        }
        if (detail.bluetoothScanActual.isPositive()) {
            StatRow("Bluetooth scan", detail.bluetoothScanActual.formatShort())
        }
        if (detail.jobs.isNotEmpty()) {
            Subhead("Jobs")
            detail.jobs.sortedByDescending { it.total }.take(10).forEach {
                StatRow(it.displayName, "${it.total.formatShort()} · ${it.count}×")
            }
        }
        if (detail.wakeupAlarms.isNotEmpty()) {
            Subhead("Wakeup alarms")
            detail.wakeupAlarms.sortedByDescending { it.count }.take(10).forEach {
                StatRow(it.tag, "${it.count}×")
            }
        }
    }
    if (wakelocks.isNotEmpty()) {
        Subhead("Wakelocks")
        wakelocks.sortedByDescending { it.total }.take(10).forEach {
            StatRow(
                it.tag,
                "${it.total.formatShort()} · ${it.count}×" + if (it.isHeld) " · held" else "",
                emphasis = if (it.isHeld) MaterialTheme.colorScheme.error else null,
            )
        }
    }
}

@Composable
private fun Subhead(text: String) {
    Text(text, Modifier.padding(top = 8.dp), style = MaterialTheme.typography.titleSmall)
}

