package io.github.loganrosen.wattdog.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.loganrosen.wattdog.batterystats.analysis.RadioAttribution
import io.github.loganrosen.wattdog.batterystats.analysis.formatBytes
import io.github.loganrosen.wattdog.batterystats.analysis.formatMah
import io.github.loganrosen.wattdog.batterystats.analysis.formatPercent
import io.github.loganrosen.wattdog.batterystats.analysis.formatShort
import io.github.loganrosen.wattdog.batterystats.model.BatteryStatsReport
import io.github.loganrosen.wattdog.data.Analysis
import io.github.loganrosen.wattdog.ui.components.BarRow
import io.github.loganrosen.wattdog.ui.components.EmptyNote
import io.github.loganrosen.wattdog.ui.components.SectionCard
import io.github.loganrosen.wattdog.ui.components.StatRow
import kotlin.time.Duration
import kotlin.time.DurationUnit

@Composable
fun RadiosScreen(analysis: Analysis, modifier: Modifier = Modifier) {
    val report = analysis.report
    val radio = remember(report) { RadioAttribution.from(report) }
    LazyColumn(
        modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item { CellularCard(radio, report) }
        item { MobileAppsCard(analysis) }
        item { WifiCard(radio, report) }
        item { OtherRadiosCard(radio, report) }
    }
}

@Composable
private fun CellularCard(radio: RadioAttribution, report: BatteryStatsReport) {
    SectionCard("Cellular") {
        StatRow("Modem drain", formatMah(radio.cellularDrainMah))
        StatRow("Used by apps' traffic", formatMah(radio.cellularAppsMah))
        StatRow(
            "Standby (signal, paging)",
            formatMah(radio.cellularStandbyMah),
            emphasis = if (radio.cellularDrainMah > 0 && radio.cellularStandbyMah / radio.cellularDrainMah > 0.6) {
                MaterialTheme.colorScheme.error
            } else {
                null
            },
        )
        StatRow("Data transferred", formatBytes(radio.cellularBytes))
        radio.mahPerMb?.let { StatRow("Cost per MB", "%.2f mAh".format(it)) }
        StatRow("Radio active", report.cellular.kernelActive.formatShort())
        radio.poorSignalRatio?.let { StatRow("Poor or no signal", formatPercent(it)) }
        radio.outOfServiceRatio?.let { StatRow("Out of service", formatPercent(it)) }
        if (report.cellular.signalStrength.isNotEmpty()) {
            HorizontalDivider()
            Text("Signal strength", style = MaterialTheme.typography.titleSmall)
            DurationBars(report.cellular.signalStrength)
        }
        if (radio.radioAccessTech.isNotEmpty()) {
            HorizontalDivider()
            Text("Network type", style = MaterialTheme.typography.titleSmall)
            DurationBars(radio.radioAccessTech)
        }
    }
}

@Composable
private fun MobileAppsCard(analysis: Analysis) {
    val top = remember(analysis) {
        analysis.report.uids.values.filter { it.mobileBytes > 0 || it.mobileRadioActive > Duration.ZERO }
            .sortedByDescending { it.mobileRadioActive }.take(10)
    }
    SectionCard("Apps keeping the modem awake") {
        if (top.isEmpty()) EmptyNote("No per-app cellular activity recorded.")
        val max = top.firstOrNull()?.mobileRadioActive?.toDouble(DurationUnit.MILLISECONDS) ?: 0.0
        top.forEach { u ->
            BarRow(
                title = analysis.label(u.uid).label,
                value = u.mobileRadioActive.formatShort(),
                fraction = if (max > 0) (u.mobileRadioActive.toDouble(DurationUnit.MILLISECONDS) / max).toFloat() else 0f,
                subtitle = "${formatBytes(u.mobileBytes)} · ${u.mobileRadioActiveCount} radio wakeups",
            )
        }
    }
}

@Composable
private fun WifiCard(radio: RadioAttribution, report: BatteryStatsReport) {
    SectionCard("Wi-Fi") {
        StatRow("Drain", formatMah(radio.wifiDrainMah))
        StatRow("Data transferred", formatBytes(radio.wifiBytes))
        StatRow("Active", report.wifi.kernelActive.formatShort())
        StatRow("Scanning", report.wifi.scan.formatShort())
        StatRow("Multicast wakelock", report.wifiMulticastWakelock.formatShort())
    }
}

@Composable
private fun OtherRadiosCard(radio: RadioAttribution, report: BatteryStatsReport) {
    SectionCard("Bluetooth & GPS") {
        StatRow(
            "Bluetooth scanning",
            radio.bluetoothScan.formatShort() + (radio.bluetoothScanRatio?.let { " (${formatPercent(it)})" } ?: ""),
        )
        report.gpsDrainMah?.let { StatRow("GPS drain", formatMah(it)) }
    }
}

@Composable
private fun DurationBars(values: Map<String, Duration>) {
    val total = values.values.fold(Duration.ZERO, Duration::plus).toDouble(DurationUnit.MILLISECONDS)
    values.entries.sortedByDescending { it.value }.filter { it.value > Duration.ZERO }.forEach { (name, d) ->
        val ratio = if (total > 0) d.toDouble(DurationUnit.MILLISECONDS) / total else 0.0
        BarRow(title = name, value = "${d.formatShort()} (${formatPercent(ratio)})", fraction = ratio.toFloat())
    }
}
