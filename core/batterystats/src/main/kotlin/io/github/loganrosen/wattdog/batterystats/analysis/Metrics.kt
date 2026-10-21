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
    /** The connectivity summary's "Cellular Battery drain", a separate estimate from modem activity counters. */
    val cellularControllerMah: Double?,
    /** The modem's activity counters add up to more time than elapsed, so [cellularControllerMah] is meaningless. */
    val cellularCountersBroken: Boolean,
    val cellularBytes: Long,
    val mahPerMb: Double?,
    val poorSignalRatio: Double?,
    val outOfServiceRatio: Double?,
    val radioAccessTech: Map<String, Duration>,
    val wifiDrainMah: Double,
    val wifiAppsMah: Double?,
    val wifiBytes: Long,
    val bluetoothScan: Duration,
    val bluetoothScanRatio: Double?,
) {
    companion object {
        private val poorBuckets = setOf("none", "very poor", "poor")

        /** Slack for rounding between the counters and the on-battery clock. */
        private const val COUNTER_TOLERANCE = 1.05

        fun from(report: BatteryStatsReport): RadioAttribution {
            val cell = report.cellular
            val component = report.globalComponent("mobile_radio")
            // Prefer the power model's total so it matches the apps/standby split and the
            // component list. The connectivity summary's figure comes from a different estimator.
            val drain = component?.totalMah ?: cell.drainMah ?: 0.0
            val bytes = cell.totalBytes
            val signalTotal = cell.signalStrength.values.fold(Duration.ZERO, Duration::plus)
            val poor = cell.signalStrength.filterKeys { it in poorBuckets }.values.fold(Duration.ZERO, Duration::plus)
            val ratTotal = cell.radioAccessTech.values.fold(Duration.ZERO, Duration::plus)
            val elapsed = report.onBattery.realtime
            val modemStates = cell.sleep + cell.idle + cell.rx
            val wifi = report.globalComponent("wifi")
            return RadioAttribution(
                cellularDrainMah = drain,
                cellularAppsMah = component?.appsMah ?: 0.0,
                cellularStandbyMah = component?.unattributedMah ?: drain,
                cellularControllerMah = cell.drainMah,
                cellularCountersBroken = elapsed.isPositive() &&
                    (cell.rx > elapsed * COUNTER_TOLERANCE || modemStates > elapsed * COUNTER_TOLERANCE),
                cellularBytes = bytes,
                mahPerMb = if (bytes > 0) drain / (bytes / BYTES_PER_MB) else null,
                poorSignalRatio = ratio(poor, signalTotal),
                outOfServiceRatio = ratio(cell.radioAccessTech["oos"] ?: Duration.ZERO, ratTotal),
                radioAccessTech = cell.radioAccessTech,
                wifiDrainMah = wifi?.totalMah ?: report.wifi.drainMah ?: 0.0,
                wifiAppsMah = wifi?.appsMah,
                wifiBytes = report.wifi.totalBytes,
                bluetoothScan = report.bluetoothScan,
                bluetoothScanRatio = ratio(report.bluetoothScan, report.onBattery.realtime),
            )
        }
    }
}

/**
 * Measured discharge versus what Android's power model accounts for. The gap is drain that no
 * exposed component explains: hardware the model doesn't cover, or components it underestimates.
 */
data class DrainCoverage(val measuredMah: Int, val modeledMah: Double, val screenOn: Part?, val screenOff: Part?) {
    data class Part(val measuredMah: Int, val modeledMah: Double) {
        val unexplainedMah: Double get() = (measuredMah - modeledMah).coerceAtLeast(0.0)
    }

    val unexplainedMah: Double get() = (measuredMah - modeledMah).coerceAtLeast(0.0)
    val unexplainedShare: Double? get() = measuredMah.takeIf { it > 0 }?.let { unexplainedMah / it }

    companion object {
        fun from(report: BatteryStatsReport): DrainCoverage? {
            if (report.globalPower.isEmpty() || report.discharge.totalMah <= 0) return null
            val modeled = report.globalPower.sumOf { it.totalMah }
            val off = report.globalPowerScreenOff.takeIf { it.isNotEmpty() }?.sumOf { it.totalMah }
            return DrainCoverage(
                measuredMah = report.discharge.totalMah,
                modeledMah = modeled,
                screenOn = off?.let { Part(report.discharge.screenOnMah, (modeled - it).coerceAtLeast(0.0)) },
                screenOff = off?.let { Part(report.discharge.screenOffMah, it) },
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
