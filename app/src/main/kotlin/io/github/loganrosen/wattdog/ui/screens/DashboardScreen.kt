package io.github.loganrosen.wattdog.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.loganrosen.wattdog.batterystats.analysis.Anomaly
import io.github.loganrosen.wattdog.batterystats.analysis.DozeSummary
import io.github.loganrosen.wattdog.batterystats.analysis.DrainCost
import io.github.loganrosen.wattdog.batterystats.analysis.DrainMetrics
import io.github.loganrosen.wattdog.batterystats.analysis.SuggestedAction
import io.github.loganrosen.wattdog.batterystats.analysis.formatMah
import io.github.loganrosen.wattdog.batterystats.analysis.formatPercent
import io.github.loganrosen.wattdog.batterystats.analysis.formatShort
import io.github.loganrosen.wattdog.data.Analysis
import io.github.loganrosen.wattdog.ui.components.Badge
import io.github.loganrosen.wattdog.ui.components.BarRow
import io.github.loganrosen.wattdog.ui.components.EmptyNote
import io.github.loganrosen.wattdog.ui.components.SectionCard
import io.github.loganrosen.wattdog.ui.components.StatRow
import io.github.loganrosen.wattdog.ui.isAvailable
import io.github.loganrosen.wattdog.ui.label
import io.github.loganrosen.wattdog.ui.opensSettings
import io.github.loganrosen.wattdog.ui.theme.color

@Composable
fun DashboardScreen(analysis: Analysis, onAction: (SuggestedAction) -> Unit, modifier: Modifier = Modifier) {
    val report = analysis.report
    val drain = remember(report) { DrainMetrics.from(report) }
    val doze = remember(report) { DozeSummary.from(report) }

    LazyColumn(
        modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (analysis.power?.isPowered == true) item { ChargingNote() }
        item { AnomaliesCard(analysis, onAction) }
        item { DrainCard(drain, analysis) }
        item { TopDrainersCard(analysis) }
        item { DozeCard(doze) }
    }
}

@Composable
private fun ChargingNote() {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)) {
        Row(Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Icon(Icons.Default.Info, null)
            Text(
                "Plugged in. Battery stats only count time on battery, so nothing new is recorded until you " +
                    "unplug. A full charge followed by unplugging resets them.",
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}

@Composable
private fun AnomaliesCard(analysis: Analysis, onAction: (SuggestedAction) -> Unit) {
    SectionCard("Anomalies & rogue wakelocks") {
        if (analysis.anomalies.isEmpty()) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(Icons.Default.CheckCircle, null, tint = MaterialTheme.colorScheme.primary)
                EmptyNote("Nothing unusual in this period.")
            }
        }
        analysis.anomalies.forEachIndexed { i, anomaly ->
            if (i > 0) HorizontalDivider()
            val actions = remember(anomaly, analysis) { anomaly.actions.filter { it.isAvailable(analysis) } }
            AnomalyRow(anomaly, anomaly.uid?.let { analysis.label(it).label }, actions, onAction)
        }
    }
}

@Composable
private fun AnomalyRow(anomaly: Anomaly, appLabel: String?, actions: List<SuggestedAction>, onAction: (SuggestedAction) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Icon(Icons.Default.Warning, null, tint = anomaly.severity.color())
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(anomaly.title, Modifier.weight(1f, fill = false), style = MaterialTheme.typography.bodyLarge)
                if (anomaly.activeNow) Badge("ACTIVE NOW", MaterialTheme.colorScheme.error)
            }
            if (appLabel != null) {
                Text(appLabel, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
            }
            anomaly.cost?.let { cost ->
                Text(
                    cost.summary(),
                    style = MaterialTheme.typography.labelLarge,
                    color = if (cost.basis == DrainCost.Basis.NOT_REAL) {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    } else {
                        anomaly.severity.color()
                    },
                )
            }
            Text(anomaly.detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(
                anomaly.advice,
                Modifier.padding(top = 4.dp),
                style = MaterialTheme.typography.bodyMedium,
            )
            if (actions.isNotEmpty()) ActionButtons(actions, onAction)
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ActionButtons(actions: List<SuggestedAction>, onAction: (SuggestedAction) -> Unit) {
    FlowRow(
        Modifier.padding(top = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        actions.forEachIndexed { i, action ->
            val padding = if (action.opensSettings()) ButtonDefaults.ButtonWithIconContentPadding else ButtonDefaults.ContentPadding
            val content: @Composable () -> Unit = {
                if (action.opensSettings()) {
                    Icon(
                        Icons.Default.Settings,
                        null,
                        Modifier.padding(end = 6.dp).size(ButtonDefaults.IconSize),
                    )
                }
                Text(action.label())
            }
            if (i == 0) {
                FilledTonalButton(onClick = {
                    onAction(action)
                }, contentPadding = padding) { content() }
            } else {
                OutlinedButton(onClick = { onAction(action) }, contentPadding = padding) { content() }
            }
        }
    }
}

@Composable
private fun DrainCard(drain: DrainMetrics, analysis: Analysis) {
    SectionCard("Discharge") {
        StatRow("On battery", drain.onBattery.formatShort())
        StatRow("Total drain", "${drain.dischargeMah} mAh" + (drain.overallMahPerHour?.let { " · ${rate(it)}" } ?: ""))
        analysis.report.dischargePercent?.let { StatRow("Battery used", "${it.lowerBound}–${it.upperBound}%") }
        HorizontalDivider()
        StatRow("Screen on", drain.screenOn.formatShort())
        StatRow("Screen-on drain", "${drain.screenOnMah} mAh" + (drain.screenOnMahPerHour?.let { " · ${rate(it)}" } ?: ""))
        HorizontalDivider()
        StatRow("Screen off", drain.screenOff.formatShort())
        StatRow(
            "Screen-off drain",
            "${drain.screenOffMah} mAh" + (drain.screenOffMahPerHour?.let { " · ${rate(it)}" } ?: ""),
        )
        drain.screenOffPercentPerHour?.let { StatRow("Screen-off rate", "%.1f%%/h".format(it)) }
        StatRow(
            "Awake while screen off",
            drain.screenOffAwake.formatShort() + (drain.screenOffAwakeRatio?.let { " (${formatPercent(it)})" } ?: ""),
        )
        analysis.power?.let { p ->
            HorizontalDivider()
            p.batteryLevel?.let { StatRow("Battery now", "$it%") }
            p.wakefulness?.let { StatRow("Wakefulness now", it) }
            StatRow("Wakelocks held now", p.wakeLocks.size.toString())
        }
    }
}

@Composable
private fun TopDrainersCard(analysis: Analysis) {
    val top = remember(analysis) { analysis.report.uidPower.sortedByDescending { it.totalMah }.take(8) }
    val components = remember(analysis) { analysis.report.globalPower.sortedByDescending { it.totalMah }.take(6) }
    SectionCard("Top drain contributors") {
        if (top.isEmpty()) EmptyNote("No per-app power estimates in this period.")
        val max = top.firstOrNull()?.totalMah ?: 0.0
        top.forEach { app ->
            BarRow(
                title = analysis.label(app.uid).label,
                value = formatMah(app.totalMah),
                fraction = if (max > 0) (app.totalMah / max).toFloat() else 0f,
                subtitle = app.components.entries.filter { it.value >= 0.05 }.sortedByDescending { it.value }.take(3)
                    .joinToString(" · ") { "${it.key} ${formatMah(it.value)}" },
            )
        }
        if (components.isNotEmpty()) {
            HorizontalDivider()
            Text("By component", style = MaterialTheme.typography.titleSmall)
            val maxC = components.first().totalMah
            components.forEach { c ->
                BarRow(
                    title = c.name.replace('_', ' '),
                    value = formatMah(c.totalMah),
                    fraction = if (maxC > 0) (c.totalMah / maxC).toFloat() else 0f,
                    subtitle = c.appsMah?.let { "${formatMah(it)} attributed to apps" },
                )
            }
        }
    }
}

@Composable
private fun DozeCard(doze: DozeSummary) {
    SectionCard("Doze & always-on display") {
        StatRow("Deep doze", doze.deepIdle.formatShort() + (doze.deepIdleRatio?.let { " (${formatPercent(it)} of screen off)" } ?: ""))
        StatRow("Deep doze entries", doze.deepIdleCount.toString())
        doze.longestDeepIdle?.let { StatRow("Longest deep doze", it.formatShort()) }
        StatRow("Deep doze drain", "${doze.deepIdleMah} mAh")
        StatRow("Light doze", doze.lightIdle.formatShort())
        HorizontalDivider()
        StatRow("Screen doze (AOD)", doze.screenDoze.formatShort())
        StatRow("AOD drain", "${doze.screenDozeMah} mAh" + (doze.screenDozeMahPerHour?.let { " · ${rate(it)}" } ?: ""))
        doze.screenDozeShareOfScreenOffMah?.let { StatRow("AOD share of screen-off drain", formatPercent(it)) }
    }
}

private fun rate(mahPerHour: Double) = "%.0f mA".format(mahPerHour)
