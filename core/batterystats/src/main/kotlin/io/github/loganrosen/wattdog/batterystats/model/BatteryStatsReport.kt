package io.github.loganrosen.wattdog.batterystats.model

import kotlin.time.Duration

/**
 * Parsed form of `dumpsys batterystats --charged` ("Statistics since last charge").
 *
 * Every counter is cumulative since the stats were last reset, which is what makes
 * [io.github.loganrosen.wattdog.batterystats.analysis.ReportDelta] possible.
 */
data class BatteryStatsReport(
    /** `Start clock time`, e.g. `2026-10-12-11-11-46`. Identifies the stats session for delta checks. */
    val startClockTime: String? = null,
    val currentlyOnBattery: Boolean? = null,
    val estimatedCapacityMah: Int? = null,
    val onBattery: AwakeTime = AwakeTime.ZERO,
    val screenOff: AwakeTime = AwakeTime.ZERO,
    val screenDoze: Duration = Duration.ZERO,
    val screenOn: Duration = Duration.ZERO,
    val screenOnCount: Int = 0,
    val discharge: Discharge = Discharge(),
    val dischargePercent: DischargePercent? = null,
    val doze: DozeStats = DozeStats(),
    val totalPartialWakelock: Duration = Duration.ZERO,
    val totalFullWakelock: Duration = Duration.ZERO,
    val wifiMulticastWakelock: Duration = Duration.ZERO,
    val cellular: CellularStats = CellularStats(),
    val wifi: WifiStats = WifiStats(),
    val gpsDrainMah: Double? = null,
    val bluetoothScan: Duration = Duration.ZERO,
    /** The unconditional "Global" block of `Estimated power use (mAh)`. */
    val globalPower: List<PowerComponent> = emptyList(),
    /** The "(on battery, screen off/doze)" sub-block of the Global power section. */
    val globalPowerScreenOff: List<PowerComponent> = emptyList(),
    val uidPower: List<UidPower> = emptyList(),
    val partialWakelocks: List<PartialWakelock> = emptyList(),
    val kernelWakelocks: List<KernelWakelock> = emptyList(),
    val wakeupReasons: List<KernelWakelock> = emptyList(),
    val uids: Map<AppUid, UidDetail> = emptyMap(),
) {
    fun globalComponent(name: String): PowerComponent? = globalPower.firstOrNull { it.name == name }
}

data class AwakeTime(val realtime: Duration, val uptime: Duration) {
    companion object {
        val ZERO = AwakeTime(Duration.ZERO, Duration.ZERO)
    }
}

/** Coulomb-counter discharge buckets in mAh. `screenOff` includes time spent in screen doze (AOD). */
data class Discharge(
    val totalMah: Int = 0,
    val screenOnMah: Int = 0,
    val screenOffMah: Int = 0,
    val screenDozeMah: Int = 0,
    val lightDozeMah: Int = 0,
    val deepDozeMah: Int = 0,
)

/** "Device battery use since last full charge" percent bounds. */
data class DischargePercent(val lowerBound: Int, val upperBound: Int, val screenOn: Int, val screenOff: Int, val screenDoze: Int)

data class DozeStats(
    val deepIdling: Duration = Duration.ZERO,
    val deepIdlingCount: Int = 0,
    val lightIdling: Duration = Duration.ZERO,
    val lightIdlingCount: Int = 0,
    val longestDeepIdle: Duration? = null,
)

data class CellularStats(
    val kernelActive: Duration = Duration.ZERO,
    val sleep: Duration = Duration.ZERO,
    val idle: Duration = Duration.ZERO,
    val rx: Duration = Duration.ZERO,
    val tx: Duration = Duration.ZERO,
    val drainMah: Double? = null,
    val rxBytes: Long = 0,
    val txBytes: Long = 0,
    val rxPackets: Long = 0,
    val txPackets: Long = 0,
    /** Keyed by RAT name as printed, e.g. `lte`, `nr`, `oos`. */
    val radioAccessTech: Map<String, Duration> = emptyMap(),
    /** Keyed by RSRP bucket label, e.g. `very poor`, `poor`, `moderate`, `good`, `great`. */
    val signalStrength: Map<String, Duration> = emptyMap(),
) {
    val totalBytes: Long get() = rxBytes + txBytes
}

data class WifiStats(
    val kernelActive: Duration = Duration.ZERO,
    val scan: Duration = Duration.ZERO,
    val rx: Duration = Duration.ZERO,
    val tx: Duration = Duration.ZERO,
    val drainMah: Double? = null,
    val rxBytes: Long = 0,
    val txBytes: Long = 0,
) {
    val totalBytes: Long get() = rxBytes + txBytes
}

/** One row of the Global power block, e.g. `mobile_radio: 307 apps: 249`. */
data class PowerComponent(val name: String, val totalMah: Double, val appsMah: Double?, val duration: Duration? = null) {
    /** Power the model could not blame on any app, e.g. modem standby or Wi-Fi idle. */
    val unattributedMah: Double get() = appsMah?.let { (totalMah - it).coerceAtLeast(0.0) } ?: 0.0
}

/** One `UID u0a204: 268 fg: ... bg: ...` entry from `Estimated power use (mAh)`. */
data class UidPower(
    val uid: AppUid,
    val totalMah: Double,
    val foregroundMah: Double = 0.0,
    val backgroundMah: Double = 0.0,
    val foregroundServiceMah: Double = 0.0,
    val cachedMah: Double = 0.0,
    /** Component totals such as `cpu`, `mobile_radio`, `wakelock`, `gnss`. Sub-states like `cpu:bg` are omitted. */
    val components: Map<String, Double> = emptyMap(),
)

/** An entry from `All partial wake locks`. */
data class PartialWakelock(
    val uid: AppUid,
    val tag: String,
    val total: Duration,
    val count: Int,
    val maxMs: Long? = null,
    val actualMs: Long? = null,
    /** Present when the wakelock was still held when the dump was taken. */
    val runningFor: Duration? = null,
) {
    val isHeld: Boolean get() = runningFor != null
    val kind: WakelockKind get() = WakelockKind.classify(tag)
}

enum class WakelockKind {
    AUDIO,
    JOB,
    SYNC,
    ALARM,
    LOCATION,
    OTHER,
    ;

    companion object {
        private val audioTags = setOf(
            "AudioMix", "AudioOffload", "AudioDirectOut", "AudioDup", "AudioIn",
            "AudioSpatial", "AudioBitPerfect", "AudioMmap", "AudioUnknown",
        )

        fun classify(tag: String): WakelockKind = when {
            tag in audioTags -> AUDIO
            tag.startsWith("*job*") -> JOB
            tag.startsWith("*sync*") -> SYNC
            tag.startsWith("*alarm*") || tag.startsWith("*walarm*") -> ALARM
            tag.contains("Location", ignoreCase = true) || tag.contains("GCoreFlp") || tag == "*location*" -> LOCATION
            else -> OTHER
        }
    }
}

data class KernelWakelock(val name: String, val total: Duration, val count: Int)

/** Per-UID detail block (`  u0a154:` ...). */
data class UidDetail(
    val uid: AppUid,
    val mobileRxBytes: Long = 0,
    val mobileTxBytes: Long = 0,
    val wifiRxBytes: Long = 0,
    val wifiTxBytes: Long = 0,
    val mobileRadioActive: Duration = Duration.ZERO,
    val mobileRadioActiveCount: Int = 0,
    val bluetoothScanActual: Duration = Duration.ZERO,
    val bluetoothScanBackground: Duration = Duration.ZERO,
    val jobs: List<JobStat> = emptyList(),
    val wakeupAlarms: List<AlarmStat> = emptyList(),
    val packages: List<String> = emptyList(),
) {
    val mobileBytes: Long get() = mobileRxBytes + mobileTxBytes
}

data class JobStat(
    val name: String,
    val total: Duration,
    val count: Int,
    val background: Duration = Duration.ZERO,
    val backgroundCount: Int = 0,
) {
    /** Package / component portion of the job name with WorkManager framing removed. */
    val displayName: String
        get() = name.substringAfter("#").substringBefore("#@").ifEmpty { name }
}

data class AlarmStat(val packageName: String?, val tag: String, val count: Int)
