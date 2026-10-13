package io.github.loganrosen.wattdog.batterystats.analysis

import kotlin.time.Duration
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours

/** The last time a finding was notified, keyed by [Anomaly.key]. */
data class AlertRecord(val severity: Severity, val notifiedAtMillis: Long)

data class AlertDecision(val notify: List<Anomaly>, val records: Map<String, AlertRecord>)

/**
 * Decides which findings from a background check are worth a notification. A finding is notified
 * when it hasn't been seen within [cooldown], or when its severity went up since it was last
 * notified. Records older than [forgetAfter] are dropped so the stored state stays small.
 */
class AlertPolicy(
    private val minSeverity: Severity = Severity.WARNING,
    private val cooldown: Duration = 12.hours,
    private val forgetAfter: Duration = 3.days,
    private val maxPerRun: Int = 3,
) {
    fun decide(anomalies: List<Anomaly>, previous: Map<String, AlertRecord>, nowMillis: Long): AlertDecision {
        val records = previous.filterValues { nowMillis - it.notifiedAtMillis < forgetAfter.inWholeMilliseconds }.toMutableMap()
        val notify = anomalies
            .filter { it.severity >= minSeverity }
            .distinctBy { it.key }
            .filter { a ->
                val last = records[a.key]
                last == null || a.severity > last.severity || nowMillis - last.notifiedAtMillis >= cooldown.inWholeMilliseconds
            }
            .sortedWith(compareByDescending<Anomaly> { it.severity }.thenByDescending { it.activeNow })
            .take(maxPerRun)
        notify.forEach { records[it.key] = AlertRecord(it.severity, nowMillis) }
        return AlertDecision(notify, records)
    }
}
