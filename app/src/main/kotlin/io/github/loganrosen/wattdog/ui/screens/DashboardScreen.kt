package io.github.loganrosen.wattdog.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import io.github.loganrosen.wattdog.batterystats.analysis.Anomaly
import io.github.loganrosen.wattdog.batterystats.analysis.Diagnosis
import io.github.loganrosen.wattdog.batterystats.analysis.DozeSummary
import io.github.loganrosen.wattdog.batterystats.analysis.DrainCost
import io.github.loganrosen.wattdog.batterystats.analysis.DrainCoverage
import io.github.loganrosen.wattdog.batterystats.analysis.DrainMetrics
import io.github.loganrosen.wattdog.batterystats.analysis.Severity
import io.github.loganrosen.wattdog.batterystats.analysis.SuggestedAction
import io.github.loganrosen.wattdog.batterystats.analysis.formatBatteryShare
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
    val diagnosis = analysis.diagnosis
    var showDetails by rememberSaveable { mutableStateOf(false) }

    LazyColumn(
        modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (analysis.power?.isPowered == true) item { ChargingNote() }
        item { VerdictCard(diagnosis) }
        item { CausesCard(diagnosis, analysis, onAction) }
        item { BreakdownCard(diagnosis) }
        if (diagnosis.topApps.isNotEmpty()) item { TopAppsCard(diagnosis, analysis) }
        item {
            TextButton(onClick = { showDetails = !showDetails }, Modifier.fillMaxWidth()) {
                Icon(if (showDetails) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown, null)
                Text(if (showDetails) "Hide details" else "Show all findings and numbers")
            }
        }
        if (showDetails) {
            item { AnomaliesCard(analysis, onAction) }
            item { DrainCard(drain, analysis) }
            item { TopDrainersCard(analysis) }
            item { DozeCard(doze) }
        }
    }
}

@Composable
private fun Diagnosis.Level.color(): Color = when (this) {
    Diagnosis.Level.GOOD -> MaterialTheme.colorScheme.primary
    Diagnosis.Level.FAIR -> Severity.WARNING.color()
    Diagnosis.Level.POOR -> MaterialTheme.colorScheme.error
    Diagnosis.Level.TOO_EARLY -> MaterialTheme.colorScheme.onSurfaceVariant
}

@Composable
private fun VerdictCard(diagnosis: Diagnosis) {
    val verdict = diagnosis.verdict
    Card {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Icon(
                    when (verdict.level) {
                        Diagnosis.Level.GOOD -> Icons.Default.CheckCircle
                        Diagnosis.Level.TOO_EARLY -> Icons.Default.Info
                        else -> Icons.Default.Warning
                    },
                    null,
                    Modifier.size(32.dp),
                    tint = verdict.level.color(),
                )
                Text(verdict.headline, style = MaterialTheme.typography.titleLarge)
            }
            Text(verdict.explanation, style = MaterialTheme.typography.bodyMedium)
            if (diagnosis.inUse != null || diagnosis.idle != null) {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    diagnosis.inUse?.let { PeriodTile("Using the phone", it, Modifier.weight(1f)) }
                    diagnosis.idle?.let { PeriodTile("Screen off", it, Modifier.weight(1f)) }
                }
            }
        }
    }
}

@Composable
private fun PeriodTile(label: String, period: Diagnosis.Period, modifier: Modifier = Modifier) {
    Surface(modifier, shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surfaceContainerHighest) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("${formatBatteryShare(period.batteryShare)} of battery", style = MaterialTheme.typography.titleMedium)
            Text(
                "${period.duration.formatShort()} · ${"%.1f".format(period.percentPerHour)}%/h",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun CausesCard(diagnosis: Diagnosis, analysis: Analysis, onAction: (SuggestedAction) -> Unit) {
    SectionCard("What's using your battery") {
        if (diagnosis.causes.isEmpty()) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(Icons.Default.CheckCircle, null, tint = MaterialTheme.colorScheme.primary)
                EmptyNote(
                    if (diagnosis.verdict.level == Diagnosis.Level.TOO_EARLY) {
                        "Check back after the phone has been idle for a while."
                    } else {
                        "Nothing stands out. Your battery use looks normal."
                    },
                )
            }
        }
        diagnosis.causes.forEachIndexed { i, cause ->
            if (i > 0) HorizontalDivider()
            CauseRow(cause, analysis, onAction)
        }
    }
}

@Composable
private fun CauseRow(cause: Diagnosis.Cause, analysis: Analysis, onAction: (SuggestedAction) -> Unit) {
    var expanded by rememberSaveable(cause.title) { mutableStateOf(false) }
    val action = remember(cause, analysis) { cause.action?.takeIf { it.isAvailable(analysis) } }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(cause.title, style = MaterialTheme.typography.titleMedium)
        cause.cost?.ofBattery?.let {
            Text(
                "About ${formatBatteryShare(it)} of a full charge",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
            )
        }
        Text(cause.summary, style = MaterialTheme.typography.bodyMedium)
        cause.advice?.let {
            Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            itemVerticalAlignment = Alignment.CenterVertically,
        ) {
            action?.let {
                FilledTonalButton(onClick = { onAction(it) }) {
                    if (it.opensSettings()) {
                        Icon(Icons.Default.Settings, null, Modifier.padding(end = 6.dp).size(ButtonDefaults.IconSize))
                    }
                    Text(cause.actionLabel ?: it.label())
                }
            }
            cause.details?.let { TextButton(onClick = { onAction(it) }) { Text(it.label()) } }
            if (cause.findings.isNotEmpty()) {
                TextButton(onClick = { expanded = !expanded }) { Text(if (expanded) "Hide findings" else "Findings") }
            }
        }
        if (expanded) {
            cause.findings.forEach { f ->
                Column(Modifier.padding(start = 8.dp, top = 4.dp)) {
                    Text(
                        f.title + (f.uid?.let { " · ${analysis.label(it).label}" } ?: ""),
                        style = MaterialTheme.typography.labelLarge,
                    )
                    Text(f.detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun BreakdownCard(diagnosis: Diagnosis) {
    val periods = listOfNotNull(
        diagnosis.inUse?.takeIf { diagnosis.inUseBreakdown.isNotEmpty() }?.let { Triple("Using the phone", it, diagnosis.inUseBreakdown) },
        diagnosis.idle?.takeIf { diagnosis.idleBreakdown.isNotEmpty() }?.let { Triple("Screen off", it, diagnosis.idleBreakdown) },
    )
    if (periods.isEmpty()) return
    var selected by rememberSaveable { mutableStateOf(periods.last().first) }
    val (_, period, shares) = periods.firstOrNull { it.first == selected } ?: periods.last()
    SectionCard("Where the power went") {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            periods.forEach { (label, _, _) ->
                FilterChip(selected = label == selected, onClick = { selected = label }, label = { Text(label) })
            }
        }
        Text(
            "Split of the ${formatBatteryShare(period.batteryShare)} of battery used " +
                if (period === diagnosis.idle) "with the screen off" else "while using the phone",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        shares.forEach { share ->
            BarRow(
                title = share.area.label,
                value = formatPercent(share.fraction),
                fraction = share.fraction.toFloat(),
                subtitle = if (share.area == Diagnosis.Area.UNMEASURED) {
                    "The battery measured it, but Android couldn't trace it to any part of the phone"
                } else {
                    null
                },
            )
        }
    }
}

@Composable
private fun TopAppsCard(diagnosis: Diagnosis, analysis: Analysis) {
    SectionCard("Apps that used the most") {
        val max = diagnosis.topApps.maxOf { it.ofBattery }
        diagnosis.topApps.forEach { app ->
            BarRow(
                title = analysis.label(app.uid).label,
                value = formatBatteryShare(app.ofBattery),
                fraction = if (max > 0) (app.ofBattery / max).toFloat() else 0f,
                subtitle = "In use ${formatBatteryShare(app.inUseOfBattery)} · screen off ${formatBatteryShare(app.idleOfBattery)}",
            )
        }
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
    SectionCard("All findings") {
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
    val coverage = remember(analysis) { DrainCoverage.from(analysis.report) }
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
            coverage?.takeIf { it.unexplainedMah >= 1 }?.let { UnexplainedRow(it, maxC) }
        }
    }
}

@Composable
private fun UnexplainedRow(coverage: DrainCoverage, maxComponentMah: Double) {
    val split = listOfNotNull(
        coverage.screenOn?.let { "screen on ${formatMah(it.unexplainedMah)}" },
        coverage.screenOff?.let { "screen off ${formatMah(it.unexplainedMah)}" },
    ).joinToString(" · ")
    BarRow(
        title = "Not explained by these estimates",
        value = formatMah(coverage.unexplainedMah) + (coverage.unexplainedShare?.let { " (${formatPercent(it)})" } ?: ""),
        fraction = if (maxComponentMah > 0) (coverage.unexplainedMah / maxComponentMah).coerceAtMost(1.0).toFloat() else 0f,
        subtitle = "Measured drain minus Android's component estimates" + if (split.isNotEmpty()) ". $split" else "",
    )
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
        StatRow("Drain during AOD", "${doze.screenDozeMah} mAh" + (doze.screenDozeMahPerHour?.let { " · ${rate(it)}" } ?: ""))
        doze.screenDozeShareOfScreenOffMah?.let { StatRow("Share of screen-off drain", formatPercent(it)) }
        if (doze.ambientDisplayMah > 0) StatRow("AOD display itself (estimate)", formatMah(doze.ambientDisplayMah))
    }
}

private fun rate(mahPerHour: Double) = "%.0f mA".format(mahPerHour)
