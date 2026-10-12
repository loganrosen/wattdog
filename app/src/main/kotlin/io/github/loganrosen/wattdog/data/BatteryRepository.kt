package io.github.loganrosen.wattdog.data

import io.github.loganrosen.wattdog.batterystats.analysis.Anomaly
import io.github.loganrosen.wattdog.batterystats.analysis.AnomalyDetector
import io.github.loganrosen.wattdog.batterystats.analysis.DeltaResult
import io.github.loganrosen.wattdog.batterystats.analysis.ReportDelta
import io.github.loganrosen.wattdog.batterystats.model.AppUid
import io.github.loganrosen.wattdog.batterystats.model.BatteryHistory
import io.github.loganrosen.wattdog.batterystats.model.BatteryStatsReport
import io.github.loganrosen.wattdog.batterystats.model.PowerState
import io.github.loganrosen.wattdog.batterystats.parse.BatteryStatsParser
import io.github.loganrosen.wattdog.batterystats.parse.HistoryParser
import io.github.loganrosen.wattdog.batterystats.parse.PowerDumpParser
import io.github.loganrosen.wattdog.privileged.DumpSource
import io.github.loganrosen.wattdog.privileged.DumpTarget
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
    private val labels: Map<AppUid, AppLabel>,
    private val resolver: UidResolver,
) {
    fun label(uid: AppUid): AppLabel = labels[uid] ?: resolver.resolve(uid)
}

class StatsResetException(message: String) : IllegalStateException(message)

class BatteryRepository(
    private val source: DumpSource,
    private val snapshots: SnapshotStore,
    private val resolver: UidResolver,
    private val detector: AnomalyDetector = AnomalyDetector(),
) {
    private class Capture(val raw: String, val report: BatteryStatsReport, val power: PowerState?)

    suspend fun analyze(baseline: Baseline): Analysis {
        val now = capture()
        val report = when (baseline) {
            Baseline.SinceCharge -> now.report

            is Baseline.Snapshot -> when (val d = ReportDelta.compute(snapshots.report(baseline.meta.id), now.report)) {
                is DeltaResult.Ok -> d.delta
                is DeltaResult.StatsReset -> throw StatsResetException(d.reason)
            }
        }
        return withContext(Dispatchers.Default) {
            val anomalies = detector.detect(report, now.power)
            val uids = buildSet {
                report.uidPower.mapTo(this) { it.uid }
                report.partialWakelocks.mapTo(this) { it.uid }
                addAll(report.uids.keys)
            }
            // The delta drops unchanged per-UID blocks, so take package hints from the full dump.
            val labels = uids.associateWith { resolver.resolve(it, now.report.uids[it]?.packages.orEmpty()) }
            Analysis(baseline, report, now.power, anomalies, System.currentTimeMillis(), labels, resolver)
        }
    }

    /** Streams `--history`; it can be tens of megabytes, so it's fetched only when the History tab asks. */
    suspend fun history(): BatteryHistory = source.read(DumpTarget.BATTERYSTATS_HISTORY) { HistoryParser.parse(it.lineSequence()) }

    suspend fun takeSnapshot(label: String): SnapshotMeta {
        val now = capture()
        return snapshots.save(label, now.raw, now.report, now.power?.batteryLevel)
    }

    private suspend fun capture(): Capture = coroutineScope {
        val power = async { runCatching { PowerDumpParser.parse(source.dump(DumpTarget.POWER)) }.getOrNull() }
        val raw = source.dump(DumpTarget.BATTERYSTATS_CHARGED)
        val report = withContext(Dispatchers.Default) { BatteryStatsParser.parse(raw) }
        Capture(raw, report, power.await())
    }
}
