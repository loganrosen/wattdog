package io.github.loganrosen.wattdog.alerts

import io.github.loganrosen.wattdog.batterystats.analysis.AlertPolicy
import io.github.loganrosen.wattdog.batterystats.analysis.DeltaResult
import io.github.loganrosen.wattdog.batterystats.analysis.ReportDelta
import io.github.loganrosen.wattdog.batterystats.analysis.formatShort
import io.github.loganrosen.wattdog.data.Baseline
import io.github.loganrosen.wattdog.data.BatteryRepository
import io.github.loganrosen.wattdog.privileged.AccessMode
import io.github.loganrosen.wattdog.privileged.DumpPermissionDeniedException
import io.github.loganrosen.wattdog.privileged.NoPrivilegedAccessException
import io.github.loganrosen.wattdog.privileged.PrivilegedAccess
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * One background pass: diff against the previous check's dump so findings describe the last few
 * hours instead of the whole since-charge interval, then notify what [AlertPolicy] lets through.
 */
class BackgroundCheck(
    private val access: PrivilegedAccess,
    private val repository: BatteryRepository,
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
        val previous = store.checkpoint()
        if (previous == null) {
            store.saveCheckpoint(capture.raw)
            finish(now, "Started watching")
            return
        }
        val window = when (val d = ReportDelta.compute(previous, capture.report)) {
            is DeltaResult.Ok -> d.delta

            // Stats reset (charged or rebooted); the since-charge interval is the only window left.
            is DeltaResult.StatsReset -> capture.report
        }
        val length = window.onBattery.realtime
        if (length < MIN_WINDOW) {
            // Keep the older checkpoint so the window keeps growing.
            finish(now, "Only ${length.formatShort()} on battery since last check")
            return
        }
        val analysis = repository.analyze(Baseline.SinceCharge, window, capture)
        store.saveCheckpoint(capture.raw)
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
        private val BINDER_WAIT = 10.seconds
        private const val FAILURES_BEFORE_NOTICE = 3
    }
}
