package io.github.loganrosen.wattdog.batterystats.analysis

import io.github.loganrosen.wattdog.batterystats.model.BatteryStatsReport
import kotlin.time.Duration
import kotlin.time.DurationUnit

/** Headline numbers derived from a report (or a delta between two reports). */
data class DrainMetrics(
    val onBattery: Duration,
    val screenOn: Duration,
    val screenOff: Duration,
    val screenOffAwake: Duration,
    /** Fraction of screen-off time the CPU was awake (uptime / realtime). */
    val screenOffAwakeRatio: Double?,
    val overallMahPerHour: Double?,
    val screenOnMahPerHour: Double?,
    val screenOffMahPerHour: Double?,
    /** Average percent of capacity lost per hour while the screen was off. */
    val screenOffPercentPerHour: Double?,
    val dischargeMah: Int,
    val screenOnMah: Int,
    val screenOffMah: Int,
) {
    companion object {
        fun from(report: BatteryStatsReport): DrainMetrics {
            val onBattery = report.onBattery.realtime
            val screenOff = report.screenOff.realtime
            // "Time on battery screen off" includes doze; screen-on is the remainder.
            val screenOn = (onBattery - screenOff).coerceAtLeast(Duration.ZERO)
            val d = report.discharge
            val screenOffRate = rate(d.screenOffMah, screenOff)
            return DrainMetrics(
                onBattery = onBattery,
                screenOn = screenOn,
                screenOff = screenOff,
                screenOffAwake = report.screenOff.uptime,
                screenOffAwakeRatio = ratio(report.screenOff.uptime, screenOff),
                overallMahPerHour = rate(d.totalMah, onBattery),
                screenOnMahPerHour = rate(d.screenOnMah, screenOn),
                screenOffMahPerHour = screenOffRate,
                screenOffPercentPerHour = report.estimatedCapacityMah?.takeIf { it > 0 }?.let { cap ->
                    screenOffRate?.let { it / cap * 100 }
                },
                dischargeMah = d.totalMah,
                screenOnMah = d.screenOnMah,
                screenOffMah = d.screenOffMah,
            )
        }
    }
}

/** Separates modem standby cost from cost the power model attributes to app traffic. */
data class RadioAttribution(
    val cellularDrainMah: Double,
    val cellularAppsMah: Double,
    /** Modem cost not blamed on any UID: registration, paging, signal hunting. */
    val cellularStandbyMah: Double,
    val cellularBytes: Long,
    val mahPerMb: Double?,
    val poorSignalRatio: Double?,
    val outOfServiceRatio: Double?,
    val radioAccessTech: Map<String, Duration>,
    val wifiDrainMah: Double,
    val wifiBytes: Long,
    val bluetoothScan: Duration,
    val bluetoothScanRatio: Double?,
) {
    companion object {
        private val poorBuckets = setOf("none", "very poor", "poor")

        fun from(report: BatteryStatsReport): RadioAttribution {
            val component = report.globalComponent("mobile_radio")
            val drain = report.cellular.drainMah ?: component?.totalMah ?: 0.0
            val appsMah = component?.appsMah ?: 0.0
            val bytes = report.cellular.totalBytes
            val signalTotal = report.cellular.signalStrength.values.fold(Duration.ZERO, Duration::plus)
            val poor = report.cellular.signalStrength.filterKeys { it in poorBuckets }.values.fold(Duration.ZERO, Duration::plus)
            val ratTotal = report.cellular.radioAccessTech.values.fold(Duration.ZERO, Duration::plus)
            return RadioAttribution(
                cellularDrainMah = drain,
                cellularAppsMah = appsMah,
                // Use the power model's own total so apps + standby add up; the connectivity
                // summary's "Cellular Battery drain" comes from a different estimator.
                cellularStandbyMah = component?.unattributedMah ?: drain,
                cellularBytes = bytes,
                mahPerMb = if (bytes > 0) drain / (bytes / BYTES_PER_MB) else null,
                poorSignalRatio = ratio(poor, signalTotal),
                outOfServiceRatio = ratio(report.cellular.radioAccessTech["oos"] ?: Duration.ZERO, ratTotal),
                radioAccessTech = report.cellular.radioAccessTech,
                wifiDrainMah = report.wifi.drainMah ?: report.globalComponent("wifi")?.totalMah ?: 0.0,
                wifiBytes = report.wifi.totalBytes,
                bluetoothScan = report.bluetoothScan,
                bluetoothScanRatio = ratio(report.bluetoothScan, report.onBattery.realtime),
            )
        }
    }
}

/** Ambient display (screen doze) versus deep Doze idle. */
data class DozeSummary(
    val screenDoze: Duration,
    val screenDozeMah: Int,
    val screenDozeMahPerHour: Double?,
    val ambientDisplayMah: Double,
    val deepIdle: Duration,
    val deepIdleCount: Int,
    val deepIdleMah: Int,
    val lightIdle: Duration,
    val longestDeepIdle: Duration?,
    /** Share of screen-off time spent in deep Doze. */
    val deepIdleRatio: Double?,
    /** Share of screen-off discharge that happened while the always-on display was showing. */
    val screenDozeShareOfScreenOffMah: Double?,
) {
    companion object {
        fun from(report: BatteryStatsReport): DozeSummary = DozeSummary(
            screenDoze = report.screenDoze,
            screenDozeMah = report.discharge.screenDozeMah,
            screenDozeMahPerHour = rate(report.discharge.screenDozeMah, report.screenDoze),
            ambientDisplayMah = report.globalComponent("ambient_display")?.totalMah ?: 0.0,
            deepIdle = report.doze.deepIdling,
            deepIdleCount = report.doze.deepIdlingCount,
            deepIdleMah = report.discharge.deepDozeMah,
            lightIdle = report.doze.lightIdling,
            longestDeepIdle = report.doze.longestDeepIdle,
            deepIdleRatio = ratio(report.doze.deepIdling, report.screenOff.realtime),
            screenDozeShareOfScreenOffMah = report.discharge.screenOffMah.takeIf { it > 0 }
                ?.let { report.discharge.screenDozeMah.toDouble() / it },
        )
    }
}

internal const val BYTES_PER_MB = 1024.0 * 1024.0

internal fun rate(mah: Int, over: Duration): Double? {
    val hours = over.toDouble(DurationUnit.HOURS)
    return if (hours > 0.0) mah / hours else null
}

internal fun ratio(part: Duration, whole: Duration): Double? = if (whole.isPositive()) part / whole else null
