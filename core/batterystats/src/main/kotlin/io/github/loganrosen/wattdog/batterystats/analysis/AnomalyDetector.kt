package io.github.loganrosen.wattdog.batterystats.analysis

import io.github.loganrosen.wattdog.batterystats.model.AppUid
import io.github.loganrosen.wattdog.batterystats.model.BatteryStatsReport
import io.github.loganrosen.wattdog.batterystats.model.PartialWakelock
import io.github.loganrosen.wattdog.batterystats.model.PowerState
import io.github.loganrosen.wattdog.batterystats.model.WakelockKind
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.DurationUnit

enum class Severity { INFO, WARNING, CRITICAL }

enum class AnomalyKind {
    STUCK_AUDIO_WAKELOCK,
    LONG_WAKELOCK,
    HEAVY_JOBS,
    FREQUENT_ALARM,
    CELLULAR_STANDBY,
    SCREEN_OFF_AWAKE,
    HIGH_SCREEN_OFF_DRAIN,
    POOR_DEEP_DOZE,
    AMBIENT_DISPLAY,
    BLUETOOTH_SCAN,
    WIFI_MULTICAST,
}

data class Anomaly(
    val kind: AnomalyKind,
    val severity: Severity,
    val title: String,
    val detail: String,
    val uid: AppUid? = null,
    /** True when `dumpsys power` shows the culprit is still active right now. */
    val activeNow: Boolean = false,
)

data class AnomalyThresholds(
    val audioWakelockMin: Duration = 30.minutes,
    /** Audio wakelock time beyond actual playback that is treated as leaked. */
    val audioUnexplainedMin: Duration = 20.minutes,
    val longWakelockMin: Duration = 30.minutes,
    val longWakelockScreenOffShare: Double = 0.10,
    val heavyJobsMin: Duration = 30.minutes,
    val heavyJobCount: Int = 200,
    /** Lots of tiny jobs are cheap; only flag a high run count when it also adds up to real time. */
    val heavyJobCountMinTotal: Duration = 5.minutes,
    val alarmsPerHour: Double = 12.0,
    val alarmMinCount: Int = 50,
    val cellularMinMah: Double = 100.0,
    val cellularLowDataMb: Double = 50.0,
    val cellularMahPerMb: Double = 5.0,
    val screenOffAwakeRatio: Double = 0.30,
    val screenOffPercentPerHour: Double = 2.0,
    val deepDozeMinRatio: Double = 0.20,
    val ambientDisplayShare: Double = 0.30,
    val ambientDisplayMinMah: Int = 100,
    val bluetoothScanRatio: Double = 0.90,
    val wifiMulticastMin: Duration = 1.hours,
    val minScreenOff: Duration = 1.hours,
)

class AnomalyDetector(private val t: AnomalyThresholds = AnomalyThresholds()) {

    fun detect(report: BatteryStatsReport, power: PowerState? = null): List<Anomaly> = buildList {
        val metrics = DrainMetrics.from(report)
        val heldNow = power?.wakeLocks
            ?.filter { it.type == "PARTIAL_WAKE_LOCK" }
            ?.flatMap { lock -> lock.blamedUids.map { it to lock.tag } }
            ?.toSet()
            .orEmpty()
        fun PartialWakelock.activeNow() = (uid to tag) in heldNow

        val audioPlayback = report.globalComponent("audio")?.duration ?: Duration.ZERO
        val (audio, other) = report.partialWakelocks.partition { it.kind == WakelockKind.AUDIO }

        audio.filter { maxOf(it.total, it.runningFor ?: Duration.ZERO) >= t.audioWakelockMin }
            .filter { it.total - audioPlayback >= t.audioUnexplainedMin }
            .forEach { wl ->
                val held = if (wl.isHeld) " and is still held" else ""
                add(
                    Anomaly(
                        kind = AnomalyKind.STUCK_AUDIO_WAKELOCK,
                        severity = Severity.CRITICAL,
                        title = "Stuck ${wl.tag} wakelock",
                        detail = "${wl.tag} kept the CPU awake for ${wl.total.formatShort()}$held, but the device " +
                            "only played audio for ${audioPlayback.formatShort()}. An app likely left an audio " +
                            "stream or session open (paused player, VoIP, or a stuck notification sound).",
                        uid = wl.uid,
                        activeNow = wl.activeNow() || wl.isHeld,
                    ),
                )
            }

        val longThreshold = maxOf(t.longWakelockMin, report.screenOff.realtime * t.longWakelockScreenOffShare)
        other.filter { it.kind != WakelockKind.JOB && it.total >= longThreshold }.forEach { wl ->
            add(
                Anomaly(
                    kind = AnomalyKind.LONG_WAKELOCK,
                    severity = if (wl.total >= 1.hours || (wl.runningFor ?: Duration.ZERO) >= 1.hours) Severity.CRITICAL else Severity.WARNING,
                    title = "Long wakelock: ${wl.tag.take(60)}",
                    detail = "Held ${wl.total.formatShort()} across ${wl.count} acquisitions" +
                        (wl.runningFor?.let { ", currently held for ${it.formatShort()}" } ?: "") + ".",
                    uid = wl.uid,
                    activeNow = wl.activeNow() || wl.isHeld,
                ),
            )
        }

        for (detail in report.uids.values) {
            val total = detail.jobs.fold(Duration.ZERO) { acc, j -> acc + j.total }
            val count = detail.jobs.sumOf { it.count }
            if (total >= t.heavyJobsMin || (count >= t.heavyJobCount && total >= t.heavyJobCountMinTotal)) {
                val top = detail.jobs.maxByOrNull { it.total }
                add(
                    Anomaly(
                        kind = AnomalyKind.HEAVY_JOBS,
                        severity = if (total >= 1.hours) Severity.WARNING else Severity.INFO,
                        title = "Heavy background jobs",
                        detail = "$count job runs totalling ${total.formatShort()}" +
                            (top?.let { ". Longest: ${it.displayName} (${it.total.formatShort()}, ${it.count}x)" } ?: "") + ".",
                        uid = detail.uid,
                    ),
                )
            }
        }

        val hoursOnBattery = report.onBattery.realtime.toDouble(DurationUnit.HOURS)
        if (hoursOnBattery > 0) {
            for (detail in report.uids.values) {
                for (alarm in detail.wakeupAlarms) {
                    val perHour = alarm.count / hoursOnBattery
                    if (alarm.count >= t.alarmMinCount && perHour >= t.alarmsPerHour) {
                        add(
                            Anomaly(
                                kind = AnomalyKind.FREQUENT_ALARM,
                                severity = Severity.WARNING,
                                title = "Frequent wakeup alarm",
                                detail = "${alarm.tag} fired ${alarm.count} times (%.1f/h).".format(perHour),
                                uid = detail.uid,
                            ),
                        )
                    }
                }
            }
        }

        val radio = RadioAttribution.from(report)
        val dataMb = radio.cellularBytes / BYTES_PER_MB
        if (radio.cellularDrainMah >= t.cellularMinMah &&
            (dataMb < t.cellularLowDataMb || (radio.mahPerMb ?: 0.0) >= t.cellularMahPerMb)
        ) {
            val share = report.discharge.totalMah.takeIf { it > 0 }?.let { radio.cellularDrainMah / it }
            val rat = radio.radioAccessTech.entries.sortedByDescending { it.value }
                .take(3).joinToString { "${it.key} ${it.value.formatShort()}" }
            add(
                Anomaly(
                    kind = AnomalyKind.CELLULAR_STANDBY,
                    severity = if ((share ?: 0.0) >= 0.15) Severity.CRITICAL else Severity.WARNING,
                    title = "Cellular modem cost without data",
                    detail = "Cellular used ${formatMah(radio.cellularDrainMah)} but moved only " +
                        "${formatBytes(radio.cellularBytes)}; ${formatMah(radio.cellularStandbyMah)} is not attributed to any app. " +
                        (radio.poorSignalRatio?.let { "Signal was poor ${formatPercent(it)} of the time. " } ?: "") +
                        (if (rat.isNotEmpty()) "RAT: $rat." else ""),
                ),
            )
        }

        val awake = metrics.screenOffAwakeRatio
        if (awake != null && metrics.screenOff >= t.minScreenOff && awake >= t.screenOffAwakeRatio) {
            add(
                Anomaly(
                    kind = AnomalyKind.SCREEN_OFF_AWAKE,
                    severity = if (awake >= 0.6) Severity.CRITICAL else Severity.WARNING,
                    title = "CPU awake ${formatPercent(awake)} of screen-off time",
                    detail = "Awake ${metrics.screenOffAwake.formatShort()} out of ${metrics.screenOff.formatShort()} " +
                        "with the screen off. Partial wakelocks total ${report.totalPartialWakelock.formatShort()}.",
                ),
            )
        }

        val pctPerHour = metrics.screenOffPercentPerHour
        if (pctPerHour != null && metrics.screenOff >= t.minScreenOff && pctPerHour >= t.screenOffPercentPerHour) {
            add(
                Anomaly(
                    kind = AnomalyKind.HIGH_SCREEN_OFF_DRAIN,
                    severity = if (pctPerHour >= 2 * t.screenOffPercentPerHour) Severity.CRITICAL else Severity.WARNING,
                    title = "High screen-off drain",
                    detail = "%.1f%%/h (%.0f mAh/h) while the screen was off for %s.".format(
                        pctPerHour, metrics.screenOffMahPerHour ?: 0.0, metrics.screenOff.formatShort(),
                    ),
                ),
            )
        }

        val doze = DozeSummary.from(report)
        if (report.screenOff.realtime >= t.minScreenOff * 2 &&
            (doze.deepIdleRatio ?: 1.0) < t.deepDozeMinRatio
        ) {
            add(
                Anomaly(
                    kind = AnomalyKind.POOR_DEEP_DOZE,
                    severity = Severity.WARNING,
                    title = "Rarely reached deep Doze",
                    detail = "Deep idle for ${doze.deepIdle.formatShort()} of ${report.screenOff.realtime.formatShort()} screen-off. " +
                        "Motion, an active wakelock, or a foreground service may be keeping the device out of Doze.",
                ),
            )
        }

        val aodShare = doze.screenDozeShareOfScreenOffMah
        if (aodShare != null && aodShare >= t.ambientDisplayShare && doze.screenDozeMah >= t.ambientDisplayMinMah) {
            add(
                Anomaly(
                    kind = AnomalyKind.AMBIENT_DISPLAY,
                    severity = Severity.INFO,
                    title = "Always-on display is a large share of standby",
                    detail = "${doze.screenDozeMah} mAh (${formatPercent(aodShare)} of screen-off drain) over " +
                        "${doze.screenDoze.formatShort()} of ambient display.",
                ),
            )
        }

        val btRatio = radio.bluetoothScanRatio
        if (btRatio != null && btRatio >= t.bluetoothScanRatio && radio.bluetoothScan >= t.minScreenOff) {
            val scanner = report.uids.values.maxByOrNull { it.bluetoothScanActual }?.takeIf { it.bluetoothScanActual.isPositive() }
            add(
                Anomaly(
                    kind = AnomalyKind.BLUETOOTH_SCAN,
                    severity = Severity.INFO,
                    title = "Bluetooth scanning the whole time",
                    detail = "BLE scan ran ${radio.bluetoothScan.formatShort()} (${formatPercent(btRatio)} of time on battery)." +
                        (scanner?.let { " Longest scanner: ${it.uid} (${it.bluetoothScanActual.formatShort()})." } ?: ""),
                    uid = scanner?.uid,
                ),
            )
        }

        if (report.wifiMulticastWakelock >= t.wifiMulticastMin) {
            add(
                Anomaly(
                    kind = AnomalyKind.WIFI_MULTICAST,
                    severity = Severity.INFO,
                    title = "Wi-Fi multicast lock held",
                    detail = "Multicast reception was enabled for ${report.wifiMulticastWakelock.formatShort()}, " +
                        "usually from casting, smart-home, or device discovery apps.",
                ),
            )
        }
    }.sortedWith(compareByDescending<Anomaly> { it.severity }.thenByDescending { it.activeNow })
}
