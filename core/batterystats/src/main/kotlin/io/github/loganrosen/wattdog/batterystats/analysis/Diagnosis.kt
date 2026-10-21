package io.github.loganrosen.wattdog.batterystats.analysis

import io.github.loganrosen.wattdog.batterystats.model.AppUid
import io.github.loganrosen.wattdog.batterystats.model.BatteryStatsReport
import io.github.loganrosen.wattdog.batterystats.model.PowerComponent
import io.github.loganrosen.wattdog.batterystats.model.UidPower
import kotlin.math.roundToInt
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.DurationUnit

/** A plain-language summary of a report: how the battery did, what drove it, and what to do. */
data class Diagnosis(
    val verdict: Verdict,
    val inUse: Period?,
    val idle: Period?,
    /** Where power went while the screen was on, largest first. */
    val inUseBreakdown: List<Share>,
    /** Where power went while the screen was off, largest first. */
    val idleBreakdown: List<Share>,
    /** The few things worth knowing about, most expensive first. */
    val causes: List<Cause>,
    /** Installed apps only; Android's own UIDs are covered by the breakdowns. */
    val topApps: List<AppUse>,
) {
    enum class Level { GOOD, FAIR, POOR, TOO_EARLY }

    data class Verdict(val level: Level, val headline: String, val explanation: String)

    data class Period(val duration: Duration, val batteryShare: Double, val percentPerHour: Double) {
        val elevatedIdle get() = percentPerHour >= REFERENCE_IDLE_PERCENT_PER_HOUR * 1.25
        val heavyUse get() = percentPerHour >= HEAVY_USE_PERCENT_PER_HOUR && duration >= 0.5.hours
    }

    enum class Area(val label: String, val components: Set<String>) {
        DISPLAY("Screen", setOf("screen")),
        PROCESSING("Apps & system processing", setOf("cpu", "wakelock", "GPU")),
        MOBILE("Mobile network", setOf("mobile_radio", "phone")),
        WIFI("Wi-Fi", setOf("wifi")),
        AOD("Always-on display", setOf("ambient_display")),
        CAMERA("Camera", setOf("camera")),
        MEDIA("Audio & video", setOf("audio", "video")),
        LOCATION("Location", setOf("gnss")),
        BLUETOOTH("Bluetooth", setOf("bluetooth")),
        OTHER_HARDWARE("Everything else", emptySet()),
        UNMEASURED("Not traced by Android", emptySet()),
    }

    data class Share(val area: Area, val mah: Double, val fraction: Double)

    data class Cause(
        val title: String,
        val summary: String,
        val advice: String?,
        val cost: DrainCost?,
        /** Something the user can change or try. Null when there's nothing worth doing. */
        val action: SuggestedAction?,
        val actionLabel: String? = null,
        /** The technical screen behind the claim. */
        val details: SuggestedAction?,
        val findings: List<Anomaly> = emptyList(),
        val uid: AppUid? = null,
    )

    data class AppUse(val uid: AppUid, val mah: Double, val idleMah: Double, private val capacityMah: Double) {
        val ofBattery: Double get() = mah / capacityMah
        val inUseOfBattery: Double get() = (mah - idleMah) / capacityMah
        val idleOfBattery: Double get() = idleMah / capacityMah
    }

    companion object {
        /**
         * A rough reference for a phone at rest with always-on display, not a device-specific norm.
         * Idle loss below this isn't worth chasing.
         */
        const val REFERENCE_IDLE_PERCENT_PER_HOUR = 1.0
        const val HIGH_IDLE_PERCENT_PER_HOUR = 2.0

        /** Screen-on loss above this is heavy even for most games or navigation. */
        const val HEAVY_USE_PERCENT_PER_HOUR = 15.0
        private val minIdle = 1.hours

        fun from(report: BatteryStatsReport, anomalies: List<Anomaly>, appName: (AppUid) -> String): Diagnosis {
            val capacity = report.estimatedCapacityMah?.takeIf { it > 0 }?.toDouble()
            val metrics = DrainMetrics.from(report)
            val inUse = period(metrics.screenOn, report.discharge.screenOnMah, capacity)
            val idle = period(metrics.screenOff, report.discharge.screenOffMah, capacity)
            val inUseBreakdown = breakdown(report.discharge.screenOnMah, screenOnComponents(report))
            val idleBreakdown = breakdown(report.discharge.screenOffMah, report.globalPowerScreenOff)
            val idleCounts = idle != null && idle.duration >= minIdle
            return Diagnosis(
                verdict = verdict(idle?.takeIf { idleCounts }, inUse),
                inUse = inUse,
                idle = idle,
                inUseBreakdown = inUseBreakdown,
                idleBreakdown = idleBreakdown,
                causes = Causes(report, anomalies, appName).build(
                    inUse = inUse?.takeIf { it.heavyUse }?.let { it to inUseBreakdown },
                    idle = idleBreakdown.takeIf { idleCounts && idle!!.elevatedIdle }.orEmpty(),
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
            val heavy = inUse?.takeIf { it.heavyUse }
            val heavyNote = heavy?.let { " While you were using it, it lost ${pct(it.percentPerHour)} an hour, which is a lot." } ?: ""
            if (idle == null) {
                return Verdict(
                    Level.TOO_EARLY,
                    heavy?.let { "Heavy drain while in use" } ?: "Not enough idle time yet",
                    "Leave the phone unplugged with the screen off for an hour or more to judge idle drain.$heavyNote",
                )
            }
            val rate = idle.percentPerHour
            val compared = "With the screen off it lost ${pct(rate)} an hour. A phone at rest usually loses around " +
                "${pct(REFERENCE_IDLE_PERCENT_PER_HOUR)}, though this varies with signal and always-on display."
            return when {
                rate >= HIGH_IDLE_PERCENT_PER_HOUR -> Verdict(Level.POOR, "Battery drains fast while idle", compared + heavyNote)
                idle.elevatedIdle -> Verdict(Level.FAIR, "Idle drain is a bit high", compared + heavyNote)
                heavy != null -> Verdict(Level.FAIR, "Heavy drain while in use", "Idle drain is fine (${pct(rate)} an hour).$heavyNote")
                else -> Verdict(Level.GOOD, "Battery looks healthy", "With the screen off it lost only ${pct(rate)} an hour.")
            }
        }

        internal fun pct(v: Double) = if (v < 9.95) "%.1f%%".format(v) else "${v.roundToInt()}%"

        /** The global power block only splits out screen-off, so screen-on is everything else. */
        private fun screenOnComponents(report: BatteryStatsReport): List<PowerComponent> {
            val off = report.globalPowerScreenOff.associate { it.name to it.totalMah }
            return report.globalPower.map { it.copy(totalMah = (it.totalMah - (off[it.name] ?: 0.0)).coerceAtLeast(0.0), appsMah = null) }
        }

        private fun breakdown(measuredMah: Int, components: List<PowerComponent>): List<Share> {
            val measured = measuredMah.toDouble().takeIf { it > 0 } ?: return emptyList()
            val byName = components.associate { it.name to it.totalMah }
            val grouped = Area.entries.filter { it.components.isNotEmpty() }
                .map { area -> area to area.components.sumOf { byName[it] ?: 0.0 } }
            val other = byName.filterKeys { name -> Area.entries.none { name in it.components } }.values.sum()
            val unmeasured = (measured - grouped.sumOf { it.second } - other).coerceAtLeast(0.0)
            val (shown, small) = (grouped + (Area.UNMEASURED to unmeasured)).partition { it.second / measured >= 0.02 }
            val rest = other + small.sumOf { it.second }
            return (shown + (Area.OTHER_HARDWARE to rest))
                .filter { it.second > 0 }
                .map { (area, mah) -> Share(area, mah, mah / measured) }
                .sortedByDescending { it.mah }
        }

        private fun topApps(report: BatteryStatsReport): List<AppUse> {
            val capacity = report.estimatedCapacityMah?.takeIf { it > 0 }?.toDouble() ?: return emptyList()
            return report.uidPower.filter { it.uid.isApplication && it.totalMah > 0 }
                .sortedByDescending { it.totalMah }
                .take(5)
                .map { AppUse(it.uid, it.totalMah, it.screenOffMah.coerceAtMost(it.totalMah), capacity) }
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
        const val ANDROID = "Android system"
        const val MIN_AREA_SHARE = 0.15
    }

    fun build(inUse: Pair<Diagnosis.Period, List<Diagnosis.Share>>?, idle: List<Diagnosis.Share>): List<Diagnosis.Cause> {
        val fromUse = inUse?.let { (period, shares) -> inUse(period, shares) }
        val fromIdle = idle.filter { it.fraction >= MIN_AREA_SHARE }.mapNotNull { area(it) }
        val fromApps = findings.filter { it !in used && it.uid?.isApplication == true }
            .groupBy { it.uid!! }
            .filterValues { group -> group.any { it.severity > Severity.INFO || (it.cost?.ofBattery ?: 0.0) >= 0.03 } }
            .map { (uid, group) -> app(uid, group) }
        val leftovers = findings.filter { it !in used && it.severity > Severity.INFO && it.uid?.isApplication != true }
            .map {
                val action = it.actions.firstOrNull { a -> a.isUserAction() }
                val details = it.actions.firstOrNull { a -> !a.isUserAction() }
                Diagnosis.Cause(it.title, it.detail, it.advice, it.cost, action, details = details, findings = listOf(it), uid = it.uid)
            }
        return (listOfNotNull(fromUse) + fromIdle + fromApps + leftovers).sortedByDescending { it.cost?.mah ?: 0.0 }.take(3)
    }

    private fun SuggestedAction.isUserAction() = when (this) {
        is SuggestedAction.OpenAppBatteryUsage, is SuggestedAction.OpenAppInfo, SuggestedAction.OpenMobileNetworkSettings,
        SuggestedAction.OpenLockScreenSettings, SuggestedAction.OpenBluetoothSettings, is SuggestedAction.TakeSnapshot,
        -> true

        else -> false
    }

    private fun take(vararg kinds: AnomalyKind, systemOnly: Boolean = false): List<Anomaly> = findings
        .filter { it.kind in kinds && (!systemOnly || it.uid?.isApplication != true) }
        .also { used += it }

    private fun cost(mah: Double, basis: DrainCost.Basis = DrainCost.Basis.ESTIMATED) = DrainCost.of(report, mah, basis)

    /** Platform UIDs (kernel, system server, radio...) mean nothing to most people, so they collapse into one entry. */
    private fun allUsers(mah: (UidPower) -> Double): List<Pair<String, Double>> = report.uidPower
        .groupBy { if (it.uid.isApplication) appName(it.uid) else ANDROID }
        .mapValues { (_, uids) -> uids.sumOf(mah) }
        .entries.sortedByDescending { it.value }
        .map { it.key to it.value }

    private fun topUsers(mah: (UidPower) -> Double, minMah: Double = 5.0) = allUsers(mah).filter { it.second >= minMah }.take(3)

    private fun names(users: List<Pair<String, Double>>) = users.joinToString(", ") { it.first }

    private fun screenOff(components: Set<String>) = { u: UidPower ->
        components.sumOf { c -> u.screenOffComponents[c] ?: 0.0 }
    }

    private fun share(s: Diagnosis.Share) = "${Diagnosis.pct(s.fraction * 100)} of what the phone used with the screen off"

    private fun inUse(period: Diagnosis.Period, shares: List<Diagnosis.Share>): Diagnosis.Cause {
        val traced = shares.filter { it.area != Diagnosis.Area.UNMEASURED }.take(2)
        val untraced = shares.firstOrNull { it.area == Diagnosis.Area.UNMEASURED }
        val apps = topUsers({ u -> if (u.uid.isApplication) u.totalMah - u.screenOffMah else 0.0 })
        val measuredMah = report.discharge.screenOnMah.toDouble()
        return Diagnosis.Cause(
            title = "Using the phone drained it quickly",
            summary = buildString {
                append("It lost ${Diagnosis.pct(period.percentPerHour)} an hour over ${period.duration.formatShort()} of screen time.")
                if (traced.isNotEmpty()) {
                    append(" The biggest costs Android could trace were ")
                    append(traced.joinToString(" and ") { "${it.area.label.lowercase()} (${Diagnosis.pct(it.fraction * 100)})" })
                    append(".")
                }
                if (apps.isNotEmpty()) append(" Apps that used the most: ${names(apps)}.")
                if (untraced != null && untraced.fraction >= 0.3) {
                    append(" Android couldn't trace ${Diagnosis.pct(untraced.fraction * 100)} of it to any part of the phone.")
                }
            },
            advice = "Camera, video, games, navigation and hotspot use drain quickly. If this was an ordinary day for you, " +
                "it's worth comparing with a lighter one.",
            cost = cost(measuredMah, DrainCost.Basis.MEASURED),
            action = null,
            details = SuggestedAction.ShowApps,
        )
    }

    private fun area(s: Diagnosis.Share): Diagnosis.Cause? = when (s.area) {
        Diagnosis.Area.PROCESSING -> processing(s)

        Diagnosis.Area.MOBILE -> mobile(s)

        Diagnosis.Area.AOD -> Diagnosis.Cause(
            title = "Always-on display",
            summary = "Keeping the clock and notifications on screen took ${share(s)}.",
            advice = "Turn it off, or set it to show only when you tap the screen, if you don't need it.",
            cost = cost(s.mah),
            action = SuggestedAction.OpenLockScreenSettings,
            details = null,
            findings = take(AnomalyKind.AMBIENT_DISPLAY),
        )

        Diagnosis.Area.WIFI -> Diagnosis.Cause(
            title = "Wi-Fi was busy while idle",
            summary = "It took ${share(s)}." +
                topUsers(screenOff(setOf("wifi"))).takeIf { it.isNotEmpty() }?.let { " Mostly ${names(it)}." }.orEmpty(),
            advice = null,
            cost = cost(s.mah),
            action = null,
            details = SuggestedAction.ShowRadios,
            findings = take(AnomalyKind.WIFI_MULTICAST, systemOnly = true),
        )

        Diagnosis.Area.LOCATION -> Diagnosis.Cause(
            title = "Location was used while idle",
            summary = "GPS took ${share(s)}." +
                topUsers(screenOff(setOf("gnss")), minMah = 1.0).takeIf { it.isNotEmpty() }?.let { " Mostly ${names(it)}." }.orEmpty(),
            advice = "Check which apps are allowed to use location all the time.",
            cost = cost(s.mah),
            action = null,
            details = SuggestedAction.ShowApps,
        )

        Diagnosis.Area.BLUETOOTH -> {
            val related = take(AnomalyKind.BLUETOOTH_SCAN)
            Diagnosis.Cause(
                title = "Bluetooth was busy while idle",
                summary = "It took ${share(s)}.",
                advice = related.firstOrNull()?.advice,
                cost = cost(s.mah),
                action = SuggestedAction.OpenBluetoothSettings,
                details = SuggestedAction.ShowRadios,
                findings = related,
            )
        }

        else -> null
    }

    private fun processing(s: Diagnosis.Share): Diagnosis.Cause {
        val related = take(
            AnomalyKind.SCREEN_OFF_AWAKE,
            AnomalyKind.POOR_DEEP_DOZE,
            AnomalyKind.FREQUENT_ALARM,
            AnomalyKind.HEAVY_JOBS,
            AnomalyKind.WIFI_MULTICAST,
            systemOnly = true,
        )
        val all = allUsers(screenOff(Diagnosis.Area.PROCESSING.components))
        val androidShare = (all.firstOrNull { it.first == ANDROID }?.second ?: 0.0) / all.sumOf { it.second }.coerceAtLeast(1e-9)
        val busiestApps = all.filter { it.first != ANDROID && it.second >= 5.0 }.take(3)
        val wakeEvidence = related.any { it.severity > Severity.INFO && it.kind != AnomalyKind.HEAVY_JOBS }
        // Ranked by time awake afterwards; wakeup reasons say what triggered a wake, not who used the power after it.
        val sources = wakeSourceTotals(report.wakeupReasons)
            .filter { it.source != WakeSource.OTHER && it.source != WakeSource.APPS }
            .sortedByDescending { it.awake }
            .take(2)
        val topApp = report.uidPower.filter { it.uid.isApplication }
            .maxByOrNull { screenOff(Diagnosis.Area.PROCESSING.components)(it) }
            ?.takeIf { screenOff(Diagnosis.Area.PROCESSING.components)(it) >= s.mah * 0.2 }
        return Diagnosis.Cause(
            title = if (wakeEvidence) "Your phone kept waking up while idle" else "Background work used power while idle",
            summary = buildString {
                append("Processing in the background took ${share(s)}.")
                if (androidShare >= 0.5) {
                    append(" Most of it (${Diagnosis.pct(androidShare * 100)}) was Android's own services rather than apps")
                } else {
                    append(" Android's own services were about ${Diagnosis.pct(androidShare * 100)} of it; apps were the rest")
                }
                if (busiestApps.isNotEmpty()) append(". The busiest apps were ${names(busiestApps)}")
                append(".")
                if (sources.isNotEmpty()) {
                    append(" It stayed awake longest after wake-ups from ${sources.joinToString(" and ") { it.source.label }}.")
                }
            },
            advice = sources.firstOrNull()?.source?.explanation,
            cost = cost(s.mah),
            action = topApp?.let { SuggestedAction.OpenAppBatteryUsage(it.uid) },
            actionLabel = topApp?.let { "Limit ${appName(it.uid)} in the background" },
            details = SuggestedAction.ShowWakelocks(wakeupReasons = true),
            findings = related,
        )
    }

    private fun mobile(s: Diagnosis.Share): Diagnosis.Cause {
        val related = take(AnomalyKind.CELLULAR_STANDBY)
        val radio = RadioAttribution.from(report)
        val radioOff = screenOff(setOf("mobile_radio"))
        // Android charges radio-active time to whoever sent packets; what's left over is the modem idling on the network.
        val radioTotal = report.globalPowerScreenOff.firstOrNull { it.name == "mobile_radio" }
        val attributed = (radioTotal?.appsMah ?: report.uidPower.sumOf(radioOff)) / (radioTotal?.totalMah ?: s.mah).coerceAtLeast(1e-9)
        val users = topUsers(radioOff)
        val topApp = report.uidPower.filter { it.uid.isApplication }.maxByOrNull(radioOff)?.takeIf { radioOff(it) >= s.mah * 0.2 }
        val ratTotal = radio.radioAccessTech.values.fold(Duration.ZERO) { a, b -> a + b }
        val fiveG = radio.radioAccessTech.filterKeys { it.startsWith("nr") }.values.fold(Duration.ZERO) { a, b -> a + b }
        val fiveGShare = if (ratTotal.isPositive()) fiveG / ratTotal else 0.0
        val poor = radio.poorSignalRatio ?: 0.0
        val pct = { v: Double -> Diagnosis.pct(v * 100) }

        val summary = buildString {
            append("The mobile network took ${share(s)}.")
            if (attributed >= 0.5) {
                append(" Android tied about ${pct(attributed)} of it to apps and services sending or receiving mobile data")
                if (users.isNotEmpty()) append(", mostly ${names(users)}")
                append("; the rest was the modem staying connected.")
            } else {
                append(" Only about ${pct(attributed)} was tied to mobile data; most was the modem staying connected, ")
                append("which it does even on Wi-Fi.")
            }
            if (poor >= 0.25) append(" Signal was weak ${pct(poor)} of the time.")
            if (fiveGShare >= 0.5) append(" Since unplugging, the phone was on 5G ${pct(fiveGShare)} of the time.")
        }
        val (action, label, advice) = when {
            topApp != null -> Triple(
                SuggestedAction.OpenAppBatteryUsage(topApp.uid),
                "Limit ${appName(topApp.uid)} in the background",
                "Each burst of data keeps the modem powered for several seconds afterwards, so frequent small syncs add up.",
            )

            fiveGShare >= 0.5 -> Triple(
                SuggestedAction.OpenMobileNetworkSettings,
                "Try LTE for a day",
                "5G can cost more than LTE, but this data can't show how much. Switching to LTE for a day and comparing is " +
                    "an easy, reversible way to find out.",
            )

            poor >= 0.25 -> Triple(null, null, "Not much to change if you're often in weak coverage. Wi-Fi calling can help indoors.")

            else -> Triple(null, null, null)
        }
        return Diagnosis.Cause(
            title = when {
                poor >= 0.25 -> "Weak signal made the modem work harder"
                attributed >= 0.5 -> "Mobile data kept the modem busy while idle"
                else -> "Mobile network standby used power while idle"
            },
            summary = summary,
            advice = advice,
            cost = cost(s.mah),
            action = action,
            actionLabel = label,
            details = SuggestedAction.ShowRadios,
            findings = related,
        )
    }

    private fun app(uid: AppUid, group: List<Anomaly>): Diagnosis.Cause {
        used += group
        val sorted = group.sortedByDescending { it.cost?.mah ?: 0.0 }
        val cost = sorted.mapNotNull { it.cost }.filter { it.basis != DrainCost.Basis.NOT_REAL }.maxByOrNull { it.mah }
        val actions = sorted.flatMap { it.actions }
        return Diagnosis.Cause(
            title = "${appName(uid)} was busy in the background",
            summary = sorted.map { sentence(it) }.distinct().joinToString(" "),
            advice = sorted.first().advice,
            cost = cost,
            action = actions.firstOrNull { it.isUserAction() },
            details = actions.firstOrNull { !it.isUserAction() },
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
