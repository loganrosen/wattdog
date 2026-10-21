package io.github.loganrosen.wattdog.batterystats.analysis

import io.github.loganrosen.wattdog.batterystats.model.AppUid
import io.github.loganrosen.wattdog.batterystats.model.BatteryStatsReport
import io.github.loganrosen.wattdog.batterystats.model.PartialWakelock
import io.github.loganrosen.wattdog.batterystats.model.PowerState
import io.github.loganrosen.wattdog.batterystats.model.UidDetail
import io.github.loganrosen.wattdog.batterystats.model.WakelockKind
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.DurationUnit

enum class Severity { INFO, WARNING, CRITICAL }

enum class AnomalyKind {
    STUCK_AUDIO_WAKELOCK,
    LONG_WAKELOCK,
    PHANTOM_WAKELOCK,
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
    /** Plain-language next step. */
    val advice: String,
    val actions: List<SuggestedAction>,
    val uid: AppUid? = null,
    /** True when `dumpsys power` shows the holder is still active right now. */
    val activeNow: Boolean = false,
    /** What the finding is about within its UID (wakelock or alarm tag), for telling findings apart. */
    val subject: String? = null,
    /** Battery the finding accounts for, when it can be estimated. */
    val cost: DrainCost? = null,
) {
    /** Identifies the same finding across reports; independent of the wording of [title] and [detail]. */
    val key: String get() = listOfNotNull(kind.name, uid?.raw?.toString(), subject).joinToString(":")
}

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
    val bluetoothAppBackgroundMin: Duration = 30.minutes,
    val wifiMulticastMin: Duration = 1.hours,
    val minScreenOff: Duration = 1.hours,
)

class AnomalyDetector(private val t: AnomalyThresholds = AnomalyThresholds()) {

    fun detect(report: BatteryStatsReport, power: PowerState? = null): List<Anomaly> = buildList {
        val metrics = DrainMetrics.from(report)
        val phantoms = PhantomWakelocks(report, power)
        fun PartialWakelock.activeNow() = phantoms.isHeldNow(this)
        val uidPower = report.uidPower.associateBy { it.uid }
        val wakelockCosts = WakelockCosts(report)
        fun PartialWakelock.cost(basis: DrainCost.Basis) = wakelockCosts.of(this, basis)

        val (phantom, real) = report.partialWakelocks.partition { phantoms.isPhantom(it) }
        phantom.filter { it.total >= t.audioWakelockMin }.forEach { wl ->
            val why = when (phantoms.reason(wl)) {
                PhantomWakelocks.Reason.EXCEEDS_AWAKE_TIME ->
                    "longer than the CPU was even awake with the screen off (${report.screenOff.uptime.formatShort()})"

                else -> "but Android's power manager isn't holding it"
            }
            add(
                Anomaly(
                    kind = AnomalyKind.PHANTOM_WAKELOCK,
                    severity = Severity.INFO,
                    title = "Phantom ${wl.tag.take(60)} wakelock",
                    detail = "Battery stats say this has been held for ${wl.total.formatShort()}, $why. " +
                        "The timer is stuck in Android's bookkeeping; nothing is actually keeping the phone awake.",
                    advice = "No real drain, so nothing to fix. Restarting the phone clears it. Until then, " +
                        "battery screens (including Android's own) may overstate this app's usage.",
                    actions = listOf(SuggestedAction.ShowWakelocks(wl.uid, wl.tag, wl.kind)),
                    uid = wl.uid,
                    subject = wl.tag,
                    cost = wl.cost(DrainCost.Basis.NOT_REAL),
                ),
            )
        }

        val audioPlayback = report.globalComponent("audio")?.duration ?: Duration.ZERO
        val (audio, other) = real.partition { it.kind == WakelockKind.AUDIO }

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
                        advice = if (wl.uid.isApplication) {
                            "Close whatever this app was playing, recording, or calling with. If the lock " +
                                "stays held, force stop the app from App info."
                        } else {
                            "A system audio service holds this on behalf of another app. Close media, call, " +
                                "and recording apps you used recently, then refresh."
                        },
                        actions = listOf(
                            SuggestedAction.ShowWakelocks(wl.uid, wl.tag, WakelockKind.AUDIO),
                            SuggestedAction.OpenAppInfo(wl.uid),
                        ),
                        uid = wl.uid,
                        activeNow = wl.activeNow() || wl.isHeld,
                        subject = wl.tag,
                        cost = wl.cost(DrainCost.Basis.ESTIMATED),
                    ),
                )
            }

        val longThreshold = maxOf(t.longWakelockMin, report.screenOff.realtime * t.longWakelockScreenOffShare)
        other.filter { it.kind != WakelockKind.JOB && it.total >= longThreshold }.forEach { wl ->
            add(
                Anomaly(
                    kind = AnomalyKind.LONG_WAKELOCK,
                    severity = if (wl.total >= 1.hours ||
                        (wl.runningFor ?: Duration.ZERO) >= 1.hours
                    ) {
                        Severity.CRITICAL
                    } else {
                        Severity.WARNING
                    },
                    title = "Long wakelock: ${wl.tag.take(60)}",
                    detail = "Held ${wl.total.formatShort()} across ${wl.count} acquisitions" +
                        (wl.runningFor?.let { ", currently held for ${it.formatShort()}" } ?: "") + ".",
                    advice = if (wl.uid.isApplication) {
                        "If you weren't using this app, set its battery usage to Restricted."
                    } else {
                        "A system component holds this. The tag usually names the feature (location, sync, " +
                            "media); check the apps that use it."
                    },
                    actions = listOf(
                        SuggestedAction.ShowWakelocks(wl.uid, wl.tag),
                        SuggestedAction.OpenAppBatteryUsage(wl.uid),
                    ),
                    uid = wl.uid,
                    activeNow = wl.activeNow() || wl.isHeld,
                    subject = wl.tag,
                    cost = wl.cost(DrainCost.Basis.ESTIMATED),
                ),
            )
        }

        for (detail in report.uids.values) {
            val total = detail.jobs.fold(Duration.ZERO) { acc, j -> acc + j.total }
            val count = detail.jobs.sumOf { it.count }
            if (total >= t.heavyJobsMin || (count >= t.heavyJobCount && total >= t.heavyJobCountMinTotal)) {
                val top = detail.jobs.maxByOrNull { it.total }
                val isApp = detail.uid.isApplication
                add(
                    Anomaly(
                        kind = AnomalyKind.HEAVY_JOBS,
                        severity = if (total >= 1.hours) Severity.WARNING else Severity.INFO,
                        title = "Heavy background jobs",
                        detail = "$count job runs totalling ${total.formatShort()}" +
                            (top?.let { ". Longest: ${it.displayName} (${it.total.formatShort()}, ${it.count}x)" } ?: "") + ".",
                        advice = if (isApp) {
                            "Backups, photo sync, and indexing can be legitimate. If this app shouldn't be " +
                                "working in the background, restrict its battery usage."
                        } else {
                            "These jobs belong to Android itself and can't be restricted. Many short runs " +
                                "usually means a system check is rescheduling too often; it costs little unless " +
                                "the total time is long."
                        },
                        actions = if (isApp) {
                            listOf(
                                SuggestedAction.ShowApp(detail.uid),
                                SuggestedAction.OpenAppBatteryUsage(detail.uid),
                            )
                        } else {
                            listOf(SuggestedAction.ShowApp(detail.uid))
                        },
                        uid = detail.uid,
                        // A system UID's background total covers everything that UID does, not just its jobs.
                        cost = if (!isApp) {
                            null
                        } else {
                            uidPower[detail.uid]?.let {
                                DrainCost.of(report, it.totalMah - it.foregroundMah, DrainCost.Basis.UPPER_BOUND)
                            }
                        },
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
                        val known = KnownAlarms.lookup(alarm.tag)
                        val rate = "${alarm.tag} fired ${alarm.count} times (%.1f/h).".format(perHour)
                        add(
                            Anomaly(
                                kind = AnomalyKind.FREQUENT_ALARM,
                                severity = if (known != null) Severity.INFO else Severity.WARNING,
                                title = "Frequent wakeup alarm",
                                detail = if (known != null) "$rate This is ${known.what}." else rate,
                                advice = known?.advice ?: if (detail.uid.isApplication) {
                                    "Usually polling sync or reminders. Lower the app's sync " +
                                        "frequency or restrict its battery usage if you don't need it that often."
                                } else {
                                    "This alarm belongs to Android itself and can't be restricted. The tag usually " +
                                        "names the feature that set it."
                                },
                                actions = if (known != null) {
                                    known.actions.ifEmpty { listOf(SuggestedAction.OpenAppInfo(detail.uid)) } +
                                        SuggestedAction.ShowApp(detail.uid)
                                } else if (!detail.uid.isApplication) {
                                    listOf(SuggestedAction.ShowApp(detail.uid))
                                } else {
                                    listOf(SuggestedAction.ShowApp(detail.uid), SuggestedAction.OpenAppBatteryUsage(detail.uid))
                                },
                                uid = detail.uid,
                                subject = alarm.tag,
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
                    advice = "This is the modem staying connected, not app data. Use Wi-Fi where you can. To " +
                        "measure 5G's cost, take a snapshot, set Preferred network type to LTE for a day, and compare.",
                    actions = listOf(
                        SuggestedAction.OpenMobileNetworkSettings,
                        SuggestedAction.TakeSnapshot("Before LTE test"),
                        SuggestedAction.ShowRadios,
                    ),
                    cost = DrainCost.of(report, radio.cellularStandbyMah, DrainCost.Basis.ESTIMATED),
                ),
            )
        }

        val awake = metrics.screenOffAwakeRatio
        if (awake != null && metrics.screenOff >= t.minScreenOff && awake >= t.screenOffAwakeRatio) {
            val realWakelocks = real.fold(Duration.ZERO) { acc, wl -> acc + wl.total }
            val sources = wakeSourceTotals(report.wakeupReasons).filter { it.source != WakeSource.OTHER }
            val top = sources.firstOrNull()
            val sourceText = sources.take(3).joinToString { s ->
                val counts = listOfNotNull(
                    s.wakeups.takeIf { it > 0 }?.let { "$it wakeups" },
                    s.aborts.takeIf { it > 0 }?.let { "$it cancelled sleeps" },
                ).joinToString(", ")
                "${s.source.label} ${s.awake.formatShort()}" + if (counts.isNotEmpty()) " ($counts)" else ""
            }
            add(
                Anomaly(
                    kind = AnomalyKind.SCREEN_OFF_AWAKE,
                    severity = if (awake >= 0.6) Severity.CRITICAL else Severity.WARNING,
                    title = "CPU awake ${formatPercent(awake)} of screen-off time",
                    detail = "Awake ${metrics.screenOffAwake.formatShort()} out of ${metrics.screenOff.formatShort()} " +
                        "with the screen off. App and system wakelocks total ${realWakelocks.formatShort()}" +
                        (if (phantom.isNotEmpty()) " (not counting stuck timers)" else "") + "." +
                        (if (sourceText.isNotEmpty()) " What woke it up: $sourceText." else ""),
                    advice = if (top != null && top.awake > realWakelocks) {
                        top.source.explanation
                    } else {
                        "Something kept the CPU running. Start with the longest app wakelocks."
                    },
                    actions = listOf(SuggestedAction.ShowWakelocks()),
                    cost = DrainCost.of(
                        report,
                        report.globalPowerScreenOff.filter { it.name == "cpu" || it.name == "wakelock" }.sumOf { it.totalMah } -
                            phantom.sumOf { wakelockCosts.mah(it) },
                        DrainCost.Basis.ESTIMATED,
                    ),
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
                        pctPerHour,
                        metrics.screenOffMahPerHour ?: 0.0,
                        metrics.screenOff.formatShort(),
                    ),
                    advice = "Check the other findings first. If none explain it, compare wakelocks, radios, " +
                        "and per-app drain.",
                    actions = listOf(SuggestedAction.ShowWakelocks(), SuggestedAction.ShowRadios, SuggestedAction.ShowApps),
                    cost = DrainCost.of(report, metrics.screenOffMah.toDouble(), DrainCost.Basis.MEASURED),
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
                    advice = "Deep Doze needs the phone still, unplugged, and screen off. Take a snapshot and leave " +
                        "it on a table overnight; if it still won't idle, look for wakelocks that are held.",
                    actions = listOf(SuggestedAction.TakeSnapshot("Overnight"), SuggestedAction.ShowWakelocks()),
                ),
            )
        }

        val aodShare = doze.screenDozeShareOfScreenOffMah
        if (aodShare != null && aodShare >= t.ambientDisplayShare && doze.screenDozeMah >= t.ambientDisplayMinMah) {
            add(
                Anomaly(
                    kind = AnomalyKind.AMBIENT_DISPLAY,
                    severity = Severity.INFO,
                    title = "Much of standby drain happened during always-on display",
                    detail = "The battery dropped ${doze.screenDozeMah} mAh (${formatPercent(aodShare)} of screen-off drain) " +
                        "during ${doze.screenDoze.formatShort()} of always-on display. That covers everything running " +
                        "at the time, not just the display" +
                        (
                            if (doze.ambientDisplayMah >
                                0
                            ) {
                                "; Android estimates the display itself at ${formatMah(doze.ambientDisplayMah)}."
                            } else {
                                "."
                            }
                            ),
                    advice = "The only way to know what AOD really costs is to compare. Take a snapshot, turn off " +
                        "\u201cAlways show time and info\u201d in lock screen settings for a similar day, and compare.",
                    actions = listOf(SuggestedAction.OpenLockScreenSettings, SuggestedAction.TakeSnapshot("Before AOD off")),
                    cost = DrainCost.of(report, doze.ambientDisplayMah, DrainCost.Basis.ESTIMATED),
                ),
            )
        }

        val btRatio = radio.bluetoothScanRatio
        if (btRatio != null && btRatio >= t.bluetoothScanRatio && radio.bluetoothScan >= t.minScreenOff) {
            add(bluetoothScan(report, radio, btRatio))
        }

        if (report.wifiMulticastWakelock >= t.wifiMulticastMin) {
            add(
                Anomaly(
                    kind = AnomalyKind.WIFI_MULTICAST,
                    severity = Severity.INFO,
                    title = "Wi-Fi multicast lock held",
                    detail = "Multicast reception was enabled for ${report.wifiMulticastWakelock.formatShort()}, " +
                        "usually from casting, smart-home, or device discovery apps.",
                    advice = "Stop casting or device-discovery apps you aren't using, then refresh to see if it clears.",
                    actions = listOf(SuggestedAction.ShowApps),
                ),
            )
        }
    }.sortedWith(
        compareByDescending<Anomaly> { it.severity }
            .thenByDescending { it.activeNow }
            .thenByDescending { it.cost?.takeIf { c -> c.basis != DrainCost.Basis.NOT_REAL }?.mah ?: 0.0 },
    )

    private fun bluetoothScan(report: BatteryStatsReport, radio: RadioAttribution, ratio: Double): Anomaly {
        val scanners = report.uids.values.filter { it.bluetoothScanActual.isPositive() }
        val (platform, apps) = scanners.partition { !it.uid.isApplication || it.isPlayServices() }
        // Background time is what the user didn't ask for; foreground scans happen while they use the app.
        val app = apps.maxByOrNull { it.bluetoothScanBackground }?.takeIf { it.bluetoothScanBackground >= t.bluetoothAppBackgroundMin }
        val system = platform.maxByOrNull { it.bluetoothScanActual }?.takeIf { it.bluetoothScanActual >= radio.bluetoothScan * 0.9 }
        val cost = report.globalComponent("bluetooth")?.let { DrainCost.of(report, it.totalMah, DrainCost.Basis.ESTIMATED) }
        val detail = buildString {
            append("Bluetooth was scanning for ${radio.bluetoothScan.formatShort()} (${formatPercent(ratio)} of time on battery).")
            app?.let {
                append(" The app below scanned in the background for ${it.bluetoothScanBackground.formatShort()}")
                if (it.bluetoothScanUnoptimized.isPositive()) append(", plus ${it.bluetoothScanUnoptimized.formatShort()} unfiltered")
                append(".")
            }
            system?.let {
                append(" Android itself kept a")
                if (!it.bluetoothScanUnoptimized.isPositive()) append(" filtered")
                append(" scan running, which connected devices, Find My Device, and Quick Share rely on.")
            }
            if (cost == null) append(" Android doesn't estimate what scanning costs on this phone.")
        }
        return Anomaly(
            kind = AnomalyKind.BLUETOOTH_SCAN,
            severity = Severity.INFO,
            title = "Bluetooth scanning the whole time",
            detail = detail,
            advice = if (app != null) {
                "Usually a wearable, tracker, car, or smart-home app looking for its device. If this app doesn't " +
                    "need to do that in the background, remove its Nearby devices permission or restrict its battery usage."
            } else {
                "Android's own services are doing the scanning, so there's no app to restrict. Turning Bluetooth " +
                    "off stops it, but also disconnects your devices."
            },
            actions = if (app != null) {
                listOf(SuggestedAction.OpenAppInfo(app.uid), SuggestedAction.ShowApp(app.uid), SuggestedAction.OpenBluetoothSettings)
            } else {
                listOf(SuggestedAction.OpenBluetoothSettings, SuggestedAction.ShowApps)
            },
            uid = app?.uid,
            cost = cost,
        )
    }

    private fun UidDetail.isPlayServices() = packages.any { it == "com.google.android.gms" }
}
