package io.github.loganrosen.wattdog.alerts

import io.github.loganrosen.wattdog.batterystats.analysis.AlertPolicy
import io.github.loganrosen.wattdog.batterystats.analysis.DeltaResult
import io.github.loganrosen.wattdog.batterystats.analysis.ReportDelta
import io.github.loganrosen.wattdog.batterystats.analysis.formatShort
import io.github.loganrosen.wattdog.data.Baseline
import io.github.loganrosen.wattdog.data.BatteryRepository
import io.github.loganrosen.wattdog.data.SnapshotStore
import io.github.loganrosen.wattdog.data.StatsSession
import io.github.loganrosen.wattdog.privileged.AccessMode
import io.github.loganrosen.wattdog.privileged.DumpPermissionDeniedException
import io.github.loganrosen.wattdog.privileged.NoPrivilegedAccessException
import io.github.loganrosen.wattdog.privileged.PrivilegedAccess
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * One background pass: save an automatic snapshot (the presets in the baseline picker come from
 * these), diff against one from a few hours ago so findings describe recent drain rather than the
 * whole since-charge interval, then notify what [AlertPolicy] lets through.
 */
class BackgroundCheck(
    private val access: PrivilegedAccess,
    private val repository: BatteryRepository,
    private val snapshots: SnapshotStore,
    private val store: AlertStore,
    private val notifier: AnomalyNotifier,
    private val policy: AlertPolicy = AlertPolicy(),
    private val clock: () -> Long = System::currentTimeMillis,
) {
    suspend fun run() {
        val now = clock()
        val capture = if (access.awaitAccess(BINDER_WAIT) == AccessMode.NONE) {
            null
        } else {
            try {
                repository.capture()
            } catch (_: NoPrivilegedAccessException) {
                null
            } catch (_: DumpPermissionDeniedException) {
                null
            }
        }
        if (capture == null) {
            accessFailed(now)
            return
        }
        store.update { s ->
            if (s.accessNoticeShown) notifier.clearAccessLost()
            s.copy(accessFailures = 0, accessNoticeShown = false)
        }

        if (capture.report.currentlyOnBattery == false) {
            finish(now, "Charging; skipped")
            return
        }
        val session = StatsSession.of(capture.report)
        snapshots.pruneAuto(session, now, AUTO_KEEP.inWholeMilliseconds)
        snapshots.load()
        val autos = snapshots.snapshots.value.filter { it.auto }
        val firstCheck = store.state.value.lastCheck == null
        if (autos.none { now - it.capturedAtMillis < MIN_AUTO_SPACING.inWholeMilliseconds }) {
            snapshots.save(AUTO_LABEL, capture.raw, capture.report, capture.power?.batteryLevel, auto = true, capturedAtMillis = now)
        }
        if (firstCheck) {
            finish(now, "Started watching")
            return
        }
        // The newest snapshot at least ALERT_WINDOW old, else the oldest there is. With none, stats
        // were reset (charged or rebooted) and the since-charge interval is the only window left.
        val start = autos.filter { now - it.capturedAtMillis >= ALERT_WINDOW.inWholeMilliseconds }.maxByOrNull { it.capturedAtMillis }
            ?: autos.minByOrNull { it.capturedAtMillis }
        val startReport = start?.let { runCatching { snapshots.report(it.id) }.getOrNull() }
        val window = when (val d = startReport?.let { ReportDelta.compute(it, capture.report) }) {
            is DeltaResult.Ok -> d.delta
            is DeltaResult.StatsReset, null -> capture.report
        }
        val length = window.onBattery.realtime
        if (length < MIN_WINDOW) {
            finish(now, "Watching; only ${length.formatShort()} on battery so far")
            return
        }
        val analysis = repository.analyze(Baseline.SinceCharge, window, capture)
        var notified = 0
        // With notifications blocked, record nothing, so findings still alert once they're allowed again.
        if (notifier.canPost()) {
            store.update { s ->
                val decision = policy.decide(analysis.anomalies, s.alertRecords(), now)
                notified = decision.notify.size
                notifier.notifyFindings(decision.notify, analysis, length)
                s.withRecords(decision.records)
            }
        }
        val found = analysis.anomalies.size
        val over = "over the last ${length.formatShort()}"
        finish(
            now,
            when {
                notified > 0 -> "Notified $notified of ${findings(found)} $over"
                found > 0 -> "${findings(found).replaceFirstChar(Char::uppercase)} $over, none new"
                else -> "Nothing found $over"
            },
        )
    }

    private suspend fun accessFailed(now: Long) = store.update { s ->
        val failures = s.accessFailures + 1
        val show = failures >= FAILURES_BEFORE_NOTICE && !s.accessNoticeShown
        if (show) notifier.notifyAccessLost()
        s.copy(
            accessFailures = failures,
            accessNoticeShown = s.accessNoticeShown || show,
            lastCheck = LastCheck(now, "No access to battery stats"),
        )
    }

    private fun findings(n: Int) = if (n == 1) "1 finding" else "$n findings"

    private suspend fun finish(now: Long, summary: String) = store.update { it.copy(lastCheck = LastCheck(now, summary)) }

    companion object {
        val MIN_WINDOW = 45.minutes
        private val ALERT_WINDOW = 3.hours
        private val AUTO_KEEP = 48.hours
        private val MIN_AUTO_SPACING = 20.minutes
        private const val AUTO_LABEL = "Auto"
        private val BINDER_WAIT = 10.seconds
        private const val FAILURES_BEFORE_NOTICE = 3
    }
}
