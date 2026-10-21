package io.github.loganrosen.wattdog.batterystats.analysis

import io.github.loganrosen.wattdog.batterystats.model.AppUid
import io.github.loganrosen.wattdog.batterystats.model.BatteryStatsReport
import kotlin.math.roundToInt
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.DurationUnit

/** A plain-language summary of a report: how the battery did, what drove it, and what to do. */
data class Diagnosis(
    val verdict: Verdict,
    val inUse: Period?,
    val idle: Period?,
    /** Where power went while the screen was off, largest first. */
    val idleBreakdown: List<Share>,
    /** The few things worth acting on, most expensive first. */
    val causes: List<Cause>,
    val topApps: List<AppUse>,
) {
    enum class Level { GOOD, FAIR, POOR, TOO_EARLY }

    data class Verdict(val level: Level, val headline: String, val explanation: String)

    data class Period(val duration: Duration, val batteryShare: Double, val percentPerHour: Double)

    enum class Area(val label: String, val components: Set<String>) {
        PROCESSING("Background activity", setOf("cpu", "wakelock", "GPU")),
        MOBILE("Mobile network", setOf("mobile_radio", "phone")),
        WIFI("Wi-Fi", setOf("wifi")),
        AOD("Always-on display", setOf("ambient_display")),
        LOCATION("Location", setOf("gnss")),
        BLUETOOTH("Bluetooth", setOf("bluetooth")),
        OTHER_HARDWARE("Other hardware", emptySet()),
        UNMEASURED("Not measured by Android", emptySet()),
    }

    data class Share(val area: Area, val mah: Double, val fraction: Double)

    data class Cause(
        val title: String,
        val summary: String,
        val advice: String?,
        val cost: DrainCost?,
        val action: SuggestedAction?,
        val findings: List<Anomaly>,
        val uid: AppUid? = null,
    )

    data class AppUse(val uid: AppUid, val mah: Double, val idleMah: Double, private val capacityMah: Double) {
        val ofBattery: Double get() = mah / capacityMah
        val inUseOfBattery: Double get() = (mah - idleMah) / capacityMah
        val idleOfBattery: Double get() = idleMah / capacityMah
    }

    companion object {
        /** Typical standby loss for a modern phone with always-on display. */
        const val HEALTHY_IDLE_PERCENT_PER_HOUR = 1.0
        const val HIGH_IDLE_PERCENT_PER_HOUR = 2.0

        /** Screen-on drain above this is heavy even for games or navigation. */
        const val HEAVY_USE_PERCENT_PER_HOUR = 15.0
        private val minIdle = 1.hours
        private const val MAX_CAUSES = 3
        private const val MIN_CAUSE_SHARE = 0.03
        private const val MIN_AREA_SHARE = 0.15

        fun from(report: BatteryStatsReport, anomalies: List<Anomaly>, appName: (AppUid) -> String): Diagnosis {
            val capacity = report.estimatedCapacityMah?.takeIf { it > 0 }?.toDouble()
            val metrics = DrainMetrics.from(report)
            val inUse = period(metrics.screenOn, report.discharge.screenOnMah, capacity)
            val idle = period(metrics.screenOff, report.discharge.screenOffMah, capacity)
            val verdict = verdict(idle, inUse)
            val breakdown = idleBreakdown(report)
            return Diagnosis(
                verdict = verdict,
                inUse = inUse,
                idle = idle,
                idleBreakdown = breakdown,
                causes = Causes(report, anomalies, appName).build(
                    breakdown.takeIf { verdict.level == Level.FAIR || verdict.level == Level.POOR }.orEmpty(),
                ),
                topApps = topApps(report),
            )
        }

        private fun period(duration: Duration, mah: Int, capacity: Double?): Period? {
            val hours = duration.toDouble(DurationUnit.HOURS)
            if (capacity == null || hours <= 0) return null
            val share = mah / capacity
            return Period(duration, share, share * 100 / hours)
        }

        private fun verdict(idle: Period?, inUse: Period?): Verdict {
            val heavyUse = inUse?.takeIf { it.percentPerHour >= HEAVY_USE_PERCENT_PER_HOUR && it.duration >= 0.5.hours }
                ?.let { " While you were using it, it lost ${pct(it.percentPerHour)} an hour, which is heavy." } ?: ""
            if (idle == null || idle.duration < minIdle) {
                return Verdict(
                    Level.TOO_EARLY,
                    "Not enough idle time yet",
                    "Leave the phone unplugged with the screen off for an hour or more, then check again.$heavyUse",
                )
            }
            val rate = idle.percentPerHour
            val compared = "With the screen off it lost ${pct(
                rate,
            )} an hour; a healthy phone loses about ${pct(HEALTHY_IDLE_PERCENT_PER_HOUR)}."
            return when {
                rate >= HIGH_IDLE_PERCENT_PER_HOUR -> Verdict(Level.POOR, "Battery drains fast while idle", compared + heavyUse)

                rate >= HEALTHY_IDLE_PERCENT_PER_HOUR * 1.25 -> Verdict(Level.FAIR, "Idle drain is a bit high", compared + heavyUse)

                heavyUse.isNotEmpty() -> Verdict(
                    Level.FAIR,
                    "Heavy drain while in use",
                    "Idle drain is fine (${pct(rate)} an hour).$heavyUse",
                )

                else -> Verdict(Level.GOOD, "Battery looks healthy", "With the screen off it lost only ${pct(rate)} an hour.")
            }
        }

        internal fun pct(v: Double) = if (v < 9.95) "%.1f%%".format(v) else "${v.roundToInt()}%"

        private fun idleBreakdown(report: BatteryStatsReport): List<Share> {
            val measured = report.discharge.screenOffMah.toDouble().takeIf { it > 0 } ?: return emptyList()
            val byName = report.globalPowerScreenOff.associate { it.name to it.totalMah }
            val grouped = Area.entries.filter { it.components.isNotEmpty() }
                .map { area -> area to area.components.sumOf { byName[it] ?: 0.0 } }
            val other = byName.filterKeys { name -> Area.entries.none { name in it.components } }.values.sum()
            val unmeasured = (measured - grouped.sumOf { it.second } - other).coerceAtLeast(0.0)
            return (grouped + (Area.OTHER_HARDWARE to other) + (Area.UNMEASURED to unmeasured))
                .filter { it.second / measured >= 0.02 }
                .map { (area, mah) -> Share(area, mah, mah / measured) }
                .sortedByDescending { it.mah }
        }

        private fun topApps(report: BatteryStatsReport): List<AppUse> {
            val capacity = report.estimatedCapacityMah?.takeIf { it > 0 }?.toDouble() ?: return emptyList()
            return report.uidPower.sortedByDescending { it.totalMah }.take(5).filter { it.totalMah > 0 }.map {
                AppUse(it.uid, it.totalMah, it.screenOffMah.coerceAtMost(it.totalMah), capacity)
            }
        }
    }
}

/** Overlapping findings (screen-off awake, alarms, multicast...) tell one story, so they're folded into areas. */
private class Causes(private val report: BatteryStatsReport, anomalies: List<Anomaly>, private val appName: (AppUid) -> String) {
    private val findings = anomalies.filter {
        it.kind != AnomalyKind.PHANTOM_WAKELOCK && it.kind != AnomalyKind.HIGH_SCREEN_OFF_DRAIN
    }
    private val used = mutableSetOf<Anomaly>()

    private companion object {
        const val ANDROID = "Android itself"
    }

    fun build(idleAreas: List<Diagnosis.Share>): List<Diagnosis.Cause> {
        val fromAreas = idleAreas.filter { it.fraction >= 0.15 }.mapNotNull { area(it) }
        val fromApps = findings.filter { it !in used && it.uid?.isApplication == true }
            .groupBy { it.uid!! }
            .filterValues { group -> group.any { it.severity > Severity.INFO || (it.cost?.ofBattery ?: 0.0) >= 0.03 } }
            .map { (uid, group) -> app(uid, group) }
        val leftovers = findings.filter { it !in used && it.severity > Severity.INFO && it.uid?.isApplication != true }
            .map { Diagnosis.Cause(it.title, it.detail, it.advice, it.cost, it.actions.firstOrNull(), listOf(it), it.uid) }
        return (fromAreas + fromApps + leftovers).sortedByDescending { it.cost?.mah ?: 0.0 }.take(3)
    }

    private fun take(vararg kinds: AnomalyKind, systemOnly: Boolean = false): List<Anomaly> = findings
        .filter { it.kind in kinds && (!systemOnly || it.uid?.isApplication != true) }
        .also { used += it }

    private fun cost(mah: Double) = DrainCost.of(report, mah, DrainCost.Basis.ESTIMATED)

    /** Platform UIDs (kernel, system server, radio...) mean nothing to most people, so they collapse into one entry. */
    private fun topIdleApps(components: Set<String>, minMah: Double = 5.0, appsOnly: Boolean = false): List<String> = report.uidPower
        .filter { !appsOnly || it.uid.isApplication }
        .groupBy { if (it.uid.isApplication) appName(it.uid) else ANDROID }
        .mapValues { (_, uids) -> uids.sumOf { u -> components.sumOf { c -> u.screenOffComponents[c] ?: 0.0 } } }
        .filterValues { it >= minMah }
        .entries.sortedByDescending { it.value }
        .take(3)
        .map { it.key }

    private fun share(s: Diagnosis.Share) = "${Diagnosis.pct(s.fraction * 100)} of idle drain"

    private fun area(s: Diagnosis.Share): Diagnosis.Cause? = when (s.area) {
        Diagnosis.Area.PROCESSING -> {
            val related = take(
                AnomalyKind.SCREEN_OFF_AWAKE,
                AnomalyKind.POOR_DEEP_DOZE,
                AnomalyKind.FREQUENT_ALARM,
                AnomalyKind.HEAVY_JOBS,
                AnomalyKind.WIFI_MULTICAST,
                systemOnly = true,
            )
            val sources = wakeSourceTotals(report.wakeupReasons)
                .filter { it.source != WakeSource.OTHER && it.source != WakeSource.APPS }
                .sortedByDescending { it.awake }
                .take(2)
            val who = topIdleApps(Diagnosis.Area.PROCESSING.components)
            Diagnosis.Cause(
                title = "Your phone kept waking up while idle",
                summary = buildString {
                    append("Background work took ${share(s)}.")
                    if (who.isNotEmpty()) append(" Mostly ${who.joinToString(", ")}.")
                    if (sources.isNotEmpty()) append(" Most wake-ups came from ${sources.joinToString(" and ") { it.source.label }}.")
                },
                advice = sources.firstOrNull()?.source?.explanation,
                cost = cost(s.mah),
                action = SuggestedAction.ShowWakelocks(),
                findings = related,
            )
        }

        Diagnosis.Area.MOBILE -> {
            val related = take(AnomalyKind.CELLULAR_STANDBY)
            val appsMah = report.uidPower.filter { it.uid.isApplication }.sumOf { it.screenOffComponents["mobile_radio"] ?: 0.0 }
            val who = topIdleApps(setOf("mobile_radio"), appsOnly = true)
            val appsDriven = appsMah > s.mah / 2 && who.isNotEmpty()
            Diagnosis.Cause(
                title = "Mobile network used a lot while idle",
                summary = if (appsDriven) {
                    "It took ${share(s)}, mostly apps syncing over mobile data: ${who.joinToString(", ")}."
                } else {
                    "It took ${share(s)}, mostly just staying connected to the network."
                },
                advice = if (appsDriven) {
                    "Each sync wakes the modem, and it stays powered for a while afterwards. Wi-Fi costs less."
                } else {
                    "Weak signal makes the modem work harder. If 5G coverage is patchy, try LTE for a day and compare."
                },
                cost = cost(s.mah),
                action = if (appsDriven) SuggestedAction.ShowRadios else SuggestedAction.OpenMobileNetworkSettings,
                findings = related,
            )
        }

        Diagnosis.Area.AOD -> {
            val related = take(AnomalyKind.AMBIENT_DISPLAY)
            Diagnosis.Cause(
                title = "Always-on display",
                summary = "Keeping the clock and notifications on screen took ${share(s)}.",
                advice = "Turn it off, or set it to show only when you tap the screen, if you don't need it.",
                cost = cost(s.mah),
                action = SuggestedAction.OpenLockScreenSettings,
                findings = related,
            )
        }

        Diagnosis.Area.WIFI -> {
            val related = take(AnomalyKind.WIFI_MULTICAST, systemOnly = true)
            val who = topIdleApps(setOf("wifi"))
            Diagnosis.Cause(
                title = "Wi-Fi was busy while idle",
                summary = "It took ${share(s)}." + if (who.isNotEmpty()) " Mostly ${who.joinToString(", ")}." else "",
                advice = null,
                cost = cost(s.mah),
                action = SuggestedAction.ShowRadios,
                findings = related,
            )
        }

        Diagnosis.Area.LOCATION -> {
            val who = topIdleApps(setOf("gnss"), minMah = 1.0)
            Diagnosis.Cause(
                title = "Location was used while idle",
                summary = "GPS took ${share(s)}." + if (who.isNotEmpty()) " Mostly ${who.joinToString(", ")}." else "",
                advice = "Check which apps are allowed to use location all the time.",
                cost = cost(s.mah),
                action = SuggestedAction.ShowApps,
                findings = emptyList(),
            )
        }

        Diagnosis.Area.BLUETOOTH -> {
            val related = take(AnomalyKind.BLUETOOTH_SCAN)
            Diagnosis.Cause(
                title = "Bluetooth was busy while idle",
                summary = "It took ${share(s)}.",
                advice = related.firstOrNull()?.advice,
                cost = cost(s.mah),
                action = SuggestedAction.OpenBluetoothSettings,
                findings = related,
            )
        }

        Diagnosis.Area.OTHER_HARDWARE, Diagnosis.Area.UNMEASURED -> null
    }

    private fun app(uid: AppUid, group: List<Anomaly>): Diagnosis.Cause {
        used += group
        val sorted = group.sortedByDescending { it.cost?.mah ?: 0.0 }
        val cost = sorted.mapNotNull { it.cost }.filter { it.basis != DrainCost.Basis.NOT_REAL }.maxByOrNull { it.mah }
        return Diagnosis.Cause(
            title = "${appName(uid)} was busy in the background",
            summary = sorted.map { sentence(it) }.distinct().joinToString(" "),
            advice = sorted.first().advice,
            cost = cost,
            action = sorted.flatMap { it.actions }.firstOrNull { it !is SuggestedAction.ShowApp }
                ?: sorted.flatMap { it.actions }.firstOrNull(),
            findings = sorted,
            uid = uid,
        )
    }

    private fun sentence(a: Anomaly): String = when (a.kind) {
        AnomalyKind.STUCK_AUDIO_WAKELOCK -> "It kept an audio session open and stopped the phone from sleeping."
        AnomalyKind.LONG_WAKELOCK -> "It kept the phone awake for long stretches."
        AnomalyKind.HEAVY_JOBS -> "It ran a lot of background work."
        AnomalyKind.FREQUENT_ALARM -> "It woke the phone up very often."
        AnomalyKind.BLUETOOTH_SCAN -> "It scanned for Bluetooth devices in the background."
        AnomalyKind.WIFI_MULTICAST -> "It listened for devices on your Wi-Fi network."
        else -> a.title + "."
    }
}
