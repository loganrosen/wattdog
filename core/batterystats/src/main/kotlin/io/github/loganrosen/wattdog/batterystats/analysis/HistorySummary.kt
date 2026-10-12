package io.github.loganrosen.wattdog.batterystats.analysis

import io.github.loganrosen.wattdog.batterystats.model.AppUid
import io.github.loganrosen.wattdog.batterystats.model.BatteryHistory
import io.github.loganrosen.wattdog.batterystats.model.TaggedSpan
import io.github.loganrosen.wattdog.batterystats.model.TimeSpan
import io.github.loganrosen.wattdog.batterystats.model.coalesce
import io.github.loganrosen.wattdog.batterystats.model.coveredMillis
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/** Headline numbers for a [BatteryHistory] window. */
data class HistorySummary(
    val levelStart: Int?,
    val levelEnd: Int?,
    val screenOnMillis: Long,
    val screenOffMillis: Long,
    /** CPU awake (`running`) while the screen was off. */
    val screenOffAwakeMillis: Long,
    val screenDozeMillis: Long,
    val deepIdleMillis: Long,
    val pluggedMillis: Long,
    /** Battery percent lost while unplugged with the screen off, per hour of that state. */
    val screenOffDropPerHour: Double?,
    val screenOnDropPerHour: Double?,
    val topApps: List<UidTime>,
    val wakelocks: List<TagTime>,
    val jobs: List<UidTime>,
) {
    data class UidTime(val uid: AppUid, val millis: Long, val count: Int)

    data class TagTime(val uid: AppUid?, val tag: String, val millis: Long, val count: Int)

    val screenOffAwakeRatio: Double? get() = screenOffMillis.takeIf { it > 0 }?.let { screenOffAwakeMillis.toDouble() / it }

    companion object {
        fun from(h: BatteryHistory, limit: Int = 10): HistorySummary {
            val screen = h.flag(BatteryHistory.SCREEN).coalesce(0)
            val plugged = h.flag(BatteryHistory.PLUGGED).coalesce(0)
            val running = h.flag(BatteryHistory.RUNNING).coalesce(0)
            val screenOn = screen.coveredMillis()
            val screenOff = h.durationMillis - screenOn
            var offDrop = 0
            var onDrop = 0
            for ((a, b) in h.levels.zipWithNext()) {
                val drop = a.level - b.level
                if (drop <= 0 || covers(plugged, b.millis)) continue
                if (covers(screen, b.millis)) onDrop += drop else offDrop += drop
            }
            val unpluggedOff = screenOff - (plugged.coveredMillis() - intersect(plugged, screen))
            val unpluggedOn = screenOn - intersect(plugged, screen)
            return HistorySummary(
                levelStart = h.levels.firstOrNull()?.level,
                levelEnd = h.levels.lastOrNull()?.level,
                screenOnMillis = screenOn,
                screenOffMillis = screenOff,
                screenOffAwakeMillis = running.coveredMillis() - intersect(running, screen),
                screenDozeMillis = h.flag(BatteryHistory.SCREEN_DOZE).coveredMillis(),
                deepIdleMillis = h.state(BatteryHistory.DEVICE_IDLE).filter { it.value == "full" }.coveredMillis(),
                pluggedMillis = plugged.coveredMillis(),
                screenOffDropPerHour = perHour(offDrop, unpluggedOff),
                screenOnDropPerHour = perHour(onDrop, unpluggedOn),
                topApps = byUid(h.tagged(BatteryHistory.TOP), limit),
                wakelocks = h.tagged(BatteryHistory.WAKE_LOCK)
                    .groupBy { it.uid to it.tag }
                    .map { (k, v) -> TagTime(k.first, k.second, v.sumOf { it.lengthMillis }, v.size) }
                    .sortedByDescending { it.millis }
                    .take(limit),
                jobs = byUid(h.tagged(BatteryHistory.JOB), limit),
            )
        }

        /** Converts a batterystats `Start clock time` (`2026-10-12-19-11-16`) to history millis. */
        fun clockMillis(startClockTime: String): Long? = runCatching {
            LocalDateTime.parse(startClockTime, startClockFormat).toInstant(ZoneOffset.UTC).toEpochMilli()
        }.getOrNull()

        private val startClockFormat = DateTimeFormatter.ofPattern("yyyy-MM-dd-HH-mm-ss")

        private fun byUid(spans: List<TaggedSpan>, limit: Int) = spans.filter { it.uid != null }
            .groupBy { it.uid!! }
            .map { (uid, v) -> UidTime(uid, v.coalesce(0).coveredMillis(), v.size) }
            .sortedByDescending { it.millis }
            .take(limit)

        private fun perHour(drop: Int, millis: Long): Double? = if (millis < MIN_RATE_WINDOW_MS) null else drop * 3_600_000.0 / millis

        private fun covers(spans: List<TimeSpan>, t: Long): Boolean {
            var lo = 0
            var hi = spans.lastIndex
            while (lo <= hi) {
                val mid = (lo + hi) ushr 1
                val s = spans[mid]
                when {
                    // Half-open (start, end]: a level step logged at a transition belongs to the state it ends.
                    t <= s.startMillis -> hi = mid - 1

                    t > s.endMillis -> lo = mid + 1

                    else -> return true
                }
            }
            return false
        }

        /** Overlap of two sorted, non-overlapping span lists. */
        private fun intersect(a: List<TimeSpan>, b: List<TimeSpan>): Long {
            var i = 0
            var j = 0
            var total = 0L
            while (i < a.size && j < b.size) {
                val s = maxOf(a[i].startMillis, b[j].startMillis)
                val e = minOf(a[i].endMillis, b[j].endMillis)
                if (e > s) total += e - s
                if (a[i].endMillis < b[j].endMillis) i++ else j++
            }
            return total
        }

        // Rates over a few minutes are dominated by 1% rounding.
        private const val MIN_RATE_WINDOW_MS = 15 * 60_000L
    }
}
