package io.github.loganrosen.wattdog.batterystats.analysis

import io.github.loganrosen.wattdog.batterystats.model.AlarmStat
import io.github.loganrosen.wattdog.batterystats.model.AwakeTime
import io.github.loganrosen.wattdog.batterystats.model.BatteryStatsReport
import io.github.loganrosen.wattdog.batterystats.model.CellularStats
import io.github.loganrosen.wattdog.batterystats.model.Discharge
import io.github.loganrosen.wattdog.batterystats.model.DozeStats
import io.github.loganrosen.wattdog.batterystats.model.JobStat
import io.github.loganrosen.wattdog.batterystats.model.KernelWakelock
import io.github.loganrosen.wattdog.batterystats.model.PartialWakelock
import io.github.loganrosen.wattdog.batterystats.model.PowerComponent
import io.github.loganrosen.wattdog.batterystats.model.UidDetail
import io.github.loganrosen.wattdog.batterystats.model.UidPower
import io.github.loganrosen.wattdog.batterystats.model.WifiStats
import kotlin.time.Duration

sealed interface DeltaResult {
    data class Ok(val delta: BatteryStatsReport) : DeltaResult

    /** The stats were reset (device charged or `--reset`) between the two snapshots. */
    data class StatsReset(val reason: String) : DeltaResult
}

/**
 * Subtracts an earlier snapshot from a later one taken within the same stats session,
 * e.g. "what drained overnight". Entries that did not grow are dropped.
 */
object ReportDelta {

    fun compute(earlier: BatteryStatsReport, later: BatteryStatsReport): DeltaResult {
        if (earlier.startClockTime != null && later.startClockTime != null &&
            earlier.startClockTime != later.startClockTime
        ) {
            return DeltaResult.StatsReset("Stats were reset between snapshots (${earlier.startClockTime} → ${later.startClockTime})")
        }
        if (later.onBattery.realtime < earlier.onBattery.realtime) {
            return DeltaResult.StatsReset("Time on battery went backwards; the device was probably charged")
        }

        val l = later
        val e = earlier
        val delta = BatteryStatsReport(
            startClockTime = l.startClockTime,
            currentlyOnBattery = l.currentlyOnBattery,
            estimatedCapacityMah = l.estimatedCapacityMah,
            onBattery = l.onBattery - e.onBattery,
            screenOff = l.screenOff - e.screenOff,
            screenDoze = l.screenDoze.minusClamped(e.screenDoze),
            screenOn = l.screenOn.minusClamped(e.screenOn),
            screenOnCount = (l.screenOnCount - e.screenOnCount).coerceAtLeast(0),
            discharge = l.discharge - e.discharge,
            // Percent bounds are coarse integers; they don't subtract meaningfully.
            dischargePercent = null,
            doze = l.doze - e.doze,
            totalPartialWakelock = l.totalPartialWakelock.minusClamped(e.totalPartialWakelock),
            totalFullWakelock = l.totalFullWakelock.minusClamped(e.totalFullWakelock),
            wifiMulticastWakelock = l.wifiMulticastWakelock.minusClamped(e.wifiMulticastWakelock),
            cellular = l.cellular - e.cellular,
            wifi = l.wifi - e.wifi,
            gpsDrainMah = l.gpsDrainMah?.minus(e.gpsDrainMah ?: 0.0)?.coerceAtLeast(0.0),
            bluetoothScan = l.bluetoothScan.minusClamped(e.bluetoothScan),
            globalPower = diffComponents(e.globalPower, l.globalPower),
            globalPowerScreenOff = diffComponents(e.globalPowerScreenOff, l.globalPowerScreenOff),
            uidPower = diffUidPower(e.uidPower, l.uidPower),
            partialWakelocks = diffPartial(e.partialWakelocks, l.partialWakelocks),
            kernelWakelocks = diffKernel(e.kernelWakelocks, l.kernelWakelocks),
            wakeupReasons = diffKernel(e.wakeupReasons, l.wakeupReasons),
            uids = l.uids.mapNotNull { (uid, detail) ->
                (detail - e.uids[uid])?.let { uid to it }
            }.toMap(),
        )
        return DeltaResult.Ok(delta)
    }

    private fun Duration.minusClamped(other: Duration) = (this - other).coerceAtLeast(Duration.ZERO)
    private fun Double.minusClamped(other: Double) = (this - other).coerceAtLeast(0.0)
    private fun Long.minusClamped(other: Long) = (this - other).coerceAtLeast(0)
    private fun Int.minusClamped(other: Int) = (this - other).coerceAtLeast(0)

    private operator fun AwakeTime.minus(o: AwakeTime) = AwakeTime(realtime.minusClamped(o.realtime), uptime.minusClamped(o.uptime))

    private operator fun Discharge.minus(o: Discharge) = Discharge(
        totalMah = totalMah.minusClamped(o.totalMah),
        screenOnMah = screenOnMah.minusClamped(o.screenOnMah),
        screenOffMah = screenOffMah.minusClamped(o.screenOffMah),
        screenDozeMah = screenDozeMah.minusClamped(o.screenDozeMah),
        lightDozeMah = lightDozeMah.minusClamped(o.lightDozeMah),
        deepDozeMah = deepDozeMah.minusClamped(o.deepDozeMah),
    )

    private operator fun DozeStats.minus(o: DozeStats) = DozeStats(
        deepIdling = deepIdling.minusClamped(o.deepIdling),
        deepIdlingCount = deepIdlingCount.minusClamped(o.deepIdlingCount),
        lightIdling = lightIdling.minusClamped(o.lightIdling),
        lightIdlingCount = lightIdlingCount.minusClamped(o.lightIdlingCount),
        // A max can't be differenced; keep the later value only if it changed.
        longestDeepIdle = longestDeepIdle?.takeIf { it != o.longestDeepIdle },
    )

    private fun diffDurations(e: Map<String, Duration>, l: Map<String, Duration>) =
        l.mapValues { (k, v) -> v.minusClamped(e[k] ?: Duration.ZERO) }.filterValues { it.isPositive() }

    private operator fun CellularStats.minus(o: CellularStats) = CellularStats(
        kernelActive = kernelActive.minusClamped(o.kernelActive),
        sleep = sleep.minusClamped(o.sleep),
        idle = idle.minusClamped(o.idle),
        rx = rx.minusClamped(o.rx),
        tx = tx.minusClamped(o.tx),
        drainMah = drainMah?.minusClamped(o.drainMah ?: 0.0),
        rxBytes = rxBytes.minusClamped(o.rxBytes),
        txBytes = txBytes.minusClamped(o.txBytes),
        rxPackets = rxPackets.minusClamped(o.rxPackets),
        txPackets = txPackets.minusClamped(o.txPackets),
        radioAccessTech = diffDurations(o.radioAccessTech, radioAccessTech),
        signalStrength = diffDurations(o.signalStrength, signalStrength),
    )

    private operator fun WifiStats.minus(o: WifiStats) = WifiStats(
        kernelActive = kernelActive.minusClamped(o.kernelActive),
        scan = scan.minusClamped(o.scan),
        rx = rx.minusClamped(o.rx),
        tx = tx.minusClamped(o.tx),
        drainMah = drainMah?.minusClamped(o.drainMah ?: 0.0),
        rxBytes = rxBytes.minusClamped(o.rxBytes),
        txBytes = txBytes.minusClamped(o.txBytes),
    )

    private fun diffComponents(e: List<PowerComponent>, l: List<PowerComponent>): List<PowerComponent> {
        val before = e.associateBy { it.name }
        return l.mapNotNull { c ->
            val b = before[c.name]
            val total = c.totalMah.minusClamped(b?.totalMah ?: 0.0)
            if (total <= 0.0) return@mapNotNull null
            PowerComponent(
                name = c.name,
                totalMah = total,
                appsMah = c.appsMah?.minusClamped(b?.appsMah ?: 0.0),
                duration = c.duration?.minusClamped(b?.duration ?: Duration.ZERO),
            )
        }
    }

    private fun diffUidPower(e: List<UidPower>, l: List<UidPower>): List<UidPower> {
        val before = e.associateBy { it.uid }
        return l.mapNotNull { u ->
            val b = before[u.uid]
            val total = u.totalMah.minusClamped(b?.totalMah ?: 0.0)
            if (total <= 0.0) return@mapNotNull null
            UidPower(
                uid = u.uid,
                totalMah = total,
                foregroundMah = u.foregroundMah.minusClamped(b?.foregroundMah ?: 0.0),
                backgroundMah = u.backgroundMah.minusClamped(b?.backgroundMah ?: 0.0),
                foregroundServiceMah = u.foregroundServiceMah.minusClamped(b?.foregroundServiceMah ?: 0.0),
                cachedMah = u.cachedMah.minusClamped(b?.cachedMah ?: 0.0),
                components = u.components
                    .mapValues { (k, v) -> v.minusClamped(b?.components?.get(k) ?: 0.0) }
                    .filterValues { it > 0.0 },
            )
        }.sortedByDescending { it.totalMah }
    }

    private fun diffPartial(e: List<PartialWakelock>, l: List<PartialWakelock>): List<PartialWakelock> {
        val before = e.associateBy { it.uid to it.tag }
        return l.mapNotNull { w ->
            val b = before[w.uid to w.tag]
            val total = w.total.minusClamped(b?.total ?: Duration.ZERO)
            if (!total.isPositive()) return@mapNotNull null
            w.copy(
                total = total,
                count = w.count.minusClamped(b?.count ?: 0),
                actualMs = w.actualMs?.minusClamped(b?.actualMs ?: 0),
            )
        }.sortedByDescending { it.total }
    }

    private fun diffKernel(e: List<KernelWakelock>, l: List<KernelWakelock>): List<KernelWakelock> {
        val before = e.associateBy { it.name }
        return l.mapNotNull { k ->
            val b = before[k.name]
            val total = k.total.minusClamped(b?.total ?: Duration.ZERO)
            val count = k.count.minusClamped(b?.count ?: 0)
            if (!total.isPositive() && count == 0) null else KernelWakelock(k.name, total, count)
        }.sortedByDescending { it.total }
    }

    private operator fun UidDetail.minus(o: UidDetail?): UidDetail? {
        if (o == null) return this
        val jobsBefore = o.jobs.associateBy { it.name }
        val jobs = jobs.mapNotNull { j ->
            val b = jobsBefore[j.name]
            val total = j.total.minusClamped(b?.total ?: Duration.ZERO)
            val count = j.count.minusClamped(b?.count ?: 0)
            if (!total.isPositive() && count == 0) {
                null
            } else {
                JobStat(
                    j.name,
                    total,
                    count,
                    j.background.minusClamped(b?.background ?: Duration.ZERO),
                    j.backgroundCount.minusClamped(b?.backgroundCount ?: 0),
                )
            }
        }
        val alarmsBefore = o.wakeupAlarms.associateBy { it.packageName to it.tag }
        val alarms = wakeupAlarms.mapNotNull { a ->
            val count = a.count.minusClamped(alarmsBefore[a.packageName to a.tag]?.count ?: 0)
            if (count == 0) null else AlarmStat(a.packageName, a.tag, count)
        }
        val d = UidDetail(
            uid = uid,
            mobileRxBytes = mobileRxBytes.minusClamped(o.mobileRxBytes),
            mobileTxBytes = mobileTxBytes.minusClamped(o.mobileTxBytes),
            wifiRxBytes = wifiRxBytes.minusClamped(o.wifiRxBytes),
            wifiTxBytes = wifiTxBytes.minusClamped(o.wifiTxBytes),
            mobileRadioActive = mobileRadioActive.minusClamped(o.mobileRadioActive),
            mobileRadioActiveCount = mobileRadioActiveCount.minusClamped(o.mobileRadioActiveCount),
            bluetoothScanActual = bluetoothScanActual.minusClamped(o.bluetoothScanActual),
            bluetoothScanBackground = bluetoothScanBackground.minusClamped(o.bluetoothScanBackground),
            jobs = jobs,
            wakeupAlarms = alarms,
            packages = packages,
        )
        val empty = d.copy(packages = emptyList()) == UidDetail(uid)
        return if (empty) null else d
    }
}
