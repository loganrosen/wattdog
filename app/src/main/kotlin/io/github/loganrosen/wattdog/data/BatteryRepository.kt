package io.github.loganrosen.wattdog.data

import io.github.loganrosen.wattdog.batterystats.analysis.Anomaly
import io.github.loganrosen.wattdog.batterystats.analysis.AnomalyDetector
import io.github.loganrosen.wattdog.batterystats.analysis.DeltaResult
import io.github.loganrosen.wattdog.batterystats.analysis.Diagnosis
import io.github.loganrosen.wattdog.batterystats.analysis.PhantomWakelocks
import io.github.loganrosen.wattdog.batterystats.analysis.ReportDelta
import io.github.loganrosen.wattdog.batterystats.analysis.Typical
import io.github.loganrosen.wattdog.batterystats.analysis.UsageInterval
import io.github.loganrosen.wattdog.batterystats.analysis.UsageLog
import io.github.loganrosen.wattdog.batterystats.analysis.WakelockCosts
import io.github.loganrosen.wattdog.batterystats.model.AppUid
import io.github.loganrosen.wattdog.batterystats.model.BatteryHistory
import io.github.loganrosen.wattdog.batterystats.model.BatteryStatsReport
import io.github.loganrosen.wattdog.batterystats.model.PowerState
import io.github.loganrosen.wattdog.batterystats.parse.BatteryStatsParser
import io.github.loganrosen.wattdog.batterystats.parse.HistoryParser
import io.github.loganrosen.wattdog.batterystats.parse.PowerDumpParser
import io.github.loganrosen.wattdog.privileged.DumpSource
import io.github.loganrosen.wattdog.privileged.DumpTarget
import java.time.ZoneId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext

sealed interface Baseline {
    data object SinceCharge : Baseline
    data class Snapshot(val meta: SnapshotMeta) : Baseline
}

data class Analysis(
    val baseline: Baseline,
    /** Since-charge report, or the delta from the baseline snapshot to now. */
    val report: BatteryStatsReport,
    val power: PowerState?,
    val anomalies: List<Anomaly>,
    val capturedAtMillis: Long,
    /** The interval the live dump belongs to, for telling which snapshots can still be compared. */
    val session: StatsSession,
    /** This phone's usual drain from earlier periods, if there's enough history. */
    val typical: Typical?,
    /** Usable idle periods and days towards [typical], counted the same way. */
    val baselineProgress: Pair<Int, Int>,
    private val labels: Map<AppUid, AppLabel>,
    private val resolver: UidResolver,
) {
    fun label(uid: AppUid): AppLabel = labels[uid] ?: resolver.resolve(uid)

    val phantoms: PhantomWakelocks by lazy { PhantomWakelocks(report, power) }

    val wakelockCosts: WakelockCosts by lazy { WakelockCosts(report) }

    val diagnosis: Diagnosis by lazy { Diagnosis.from(report, anomalies, typical) { label(it).label } }
}

class StatsResetException(message: String) : IllegalStateException(message)

class BatteryRepository(
    private val source: DumpSource,
    private val snapshots: SnapshotStore,
    private val usage: UsageStore,
    private val resolver: UidResolver,
    private val detector: AnomalyDetector = AnomalyDetector(),
) {
    class Capture(val raw: String, val report: BatteryStatsReport, val power: PowerState?)

    suspend fun analyze(baseline: Baseline): Analysis {
        val now = capture()
        val report = when (baseline) {
            Baseline.SinceCharge -> now.report

            is Baseline.Snapshot -> when (val d = ReportDelta.compute(snapshots.report(baseline.meta.id), now.report)) {
                is DeltaResult.Ok -> d.delta
                is DeltaResult.StatsReset -> throw StatsResetException(d.reason)
            }
        }
        return analyze(baseline, report, now)
    }

    /** Analyzes [report], a since-charge report or a delta ending at [now]. */
    suspend fun analyze(baseline: Baseline, report: BatteryStatsReport, now: Capture): Analysis = withContext(Dispatchers.Default) {
        val anomalies = detector.detect(report, now.power)
        val uids = buildSet {
            report.uidPower.mapTo(this) { it.uid }
            report.partialWakelocks.mapTo(this) { it.uid }
            addAll(report.uids.keys)
        }
        // The delta drops unchanged per-UID blocks, so take package hints from the full dump.
        val labels = uids.associateWith { resolver.resolve(it, now.report.uids[it]?.packages.orEmpty()) }
        val nowMillis = System.currentTimeMillis()
        val intervals = UsageLog.intervals(usage.points.value)
        val zone = ZoneId.systemDefault()
        val exclude: (UsageInterval) -> Boolean = when (baseline) {
            // Stats only reset on a near-full charge, so with a charge limit one session can run for
            // days; keeping all of it out would mean never having a baseline.
            Baseline.SinceCharge -> { i -> i.session == now.report.startClockTime && nowMillis - i.endMillis < RECENT_MS }

            is Baseline.Snapshot -> { i -> i.endMillis > baseline.meta.capturedAtMillis }
        }
        val typical = UsageLog.typical(intervals, nowMillis, zone, exclude)
        val progress = UsageLog.progress(intervals, nowMillis, zone, exclude)
        Analysis(baseline, report, now.power, anomalies, nowMillis, StatsSession.of(now.report), typical, progress, labels, resolver)
    }

    /** Streams `--history`; it can be tens of megabytes, so it's fetched only when the History tab asks. */
    suspend fun history(): BatteryHistory = source.read(DumpTarget.BATTERYSTATS_HISTORY) { HistoryParser.parse(it.lineSequence()) }

    suspend fun takeSnapshot(label: String): SnapshotMeta {
        val now = capture()
        return snapshots.save(label, now.raw, now.report, now.power?.batteryLevel)
    }

    private companion object {
        const val RECENT_MS = 24 * 3_600_000L
    }

    suspend fun capture(): Capture = coroutineScope {
        val power = async { runCatching { PowerDumpParser.parse(source.dump(DumpTarget.POWER)) }.getOrNull() }
        val raw = source.dump(DumpTarget.BATTERYSTATS_CHARGED)
        val report = withContext(Dispatchers.Default) { BatteryStatsParser.parse(raw) }
        runCatching { usage.record(report) }
        Capture(raw, report, power.await())
    }
}
