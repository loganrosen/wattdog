package io.github.loganrosen.wattdog.batterystats.analysis

import io.github.loganrosen.wattdog.batterystats.model.BatteryStatsReport
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.DurationUnit

/**
 * The cumulative counters from one dump, small enough to keep for months. Consecutive points from
 * the same stats session give on-battery intervals; charging in between doesn't matter because the
 * counters only advance on battery.
 */
data class UsagePoint(
    val capturedAtMillis: Long,
    val session: String?,
    val onBatteryMs: Long,
    val screenOnMs: Long,
    val screenOnMah: Int,
    val screenOffMs: Long,
    val screenOffMah: Int,
    val capacityMah: Int,
    /** Always-on display time and charge, part of the screen-off figures. Null in points logged before these were kept. */
    val screenDozeMs: Long? = null,
    val screenDozeMah: Int? = null,
) {
    companion object {
        fun of(report: BatteryStatsReport, capturedAtMillis: Long): UsagePoint? {
            val capacity = report.estimatedCapacityMah?.takeIf { it > 0 } ?: return null
            val metrics = DrainMetrics.from(report)
            return UsagePoint(
                capturedAtMillis = capturedAtMillis,
                session = report.startClockTime,
                onBatteryMs = report.onBattery.realtime.inWholeMilliseconds,
                screenOnMs = metrics.screenOn.inWholeMilliseconds,
                screenOnMah = report.discharge.screenOnMah,
                screenOffMs = metrics.screenOff.inWholeMilliseconds,
                screenOffMah = report.discharge.screenOffMah,
                capacityMah = capacity,
                screenDozeMs = report.screenDoze.inWholeMilliseconds,
                screenDozeMah = report.discharge.screenDozeMah,
            )
        }
    }
}

data class UsageInterval(
    val session: String?,
    val startMillis: Long,
    val endMillis: Long,
    val screenOn: Duration,
    val screenOnShare: Double,
    val screenOff: Duration,
    val screenOffShare: Double,
)

data class DayUsage(
    val date: LocalDate,
    val screenOn: Duration,
    val screenOnShare: Double,
    val screenOff: Duration,
    val screenOffShare: Double,
) {
    val idlePercentPerHour: Double? get() = rate(screenOffShare, screenOff)
}

/** Median and quartiles, in percent of battery per hour. */
data class Spread(val median: Double, val low: Double, val high: Double) {
    /** Clearly above the usual range, not just at the top of it. */
    fun isAbove(value: Double) = value > maxOf(high + (high - low), median * 1.3, median + 0.3)
}

/**
 * This phone's recent range, from past intervals. It spots days that stand out; it can't say whether
 * the range itself is healthy, so it never changes a verdict.
 */
data class Typical(val idle: Spread, val inUse: Spread?, val periods: Int, val days: Int)

object UsageLog {
    val KEEP = 90.days
    private val WINDOW = 14.days
    private const val BLOCK_HOURS = 6
    private val MIN_BLOCK_IDLE = 2.hours
    private val MIN_BLOCK_USE = 30.minutes
    const val MIN_PERIODS = 5
    const val MIN_DAYS = 3

    fun intervals(points: List<UsagePoint>): List<UsageInterval> = points.sortedBy { it.capturedAtMillis }
        .zipWithNext()
        .filter { (a, b) -> a.session != null && a.session == b.session && b.onBatteryMs > a.onBatteryMs }
        .mapNotNull { (a, b) ->
            val on = b.screenOnMs - a.screenOnMs
            val off = b.screenOffMs - a.screenOffMs
            val onMah = b.screenOnMah - a.screenOnMah
            val offMah = b.screenOffMah - a.screenOffMah
            if (on < 0 || off < 0 || onMah < 0 || offMah < 0) return@mapNotNull null
            UsageInterval(
                session = b.session,
                startMillis = a.capturedAtMillis,
                endMillis = b.capturedAtMillis,
                screenOn = on.milliseconds,
                screenOnShare = onMah.toDouble() / b.capacityMah,
                screenOff = off.milliseconds,
                screenOffShare = offMah.toDouble() / b.capacityMah,
            )
        }

    /** Newest first. Each interval counts towards the day it ended on. */
    fun days(intervals: List<UsageInterval>, zone: ZoneId): List<DayUsage> = intervals
        .groupBy { Instant.ofEpochMilli(it.endMillis).atZone(zone).toLocalDate() }
        .map { (date, list) ->
            DayUsage(
                date,
                list.fold(Duration.ZERO) { acc, i -> acc + i.screenOn },
                list.sumOf { it.screenOnShare },
                list.fold(Duration.ZERO) { acc, i -> acc + i.screenOff },
                list.sumOf { it.screenOffShare },
            )
        }
        .sortedByDescending { it.date }

    /**
     * Groups intervals into six-hour blocks and takes the median rate over the last two weeks.
     * Blocks rather than days so a few nights are enough; null until there are [MIN_PERIODS] usable
     * blocks over [MIN_DAYS] days. [exclude] keeps the period being judged out of its own baseline.
     */
    fun typical(intervals: List<UsageInterval>, nowMillis: Long, zone: ZoneId, exclude: (UsageInterval) -> Boolean): Typical? {
        val blocks = blocks(intervals, nowMillis, zone, exclude)
        val idle = idleRates(blocks)
        if (idle.size < MIN_PERIODS || idle.map { it.first }.distinct().size < MIN_DAYS) return null
        val use = blocks.values.mapNotNull { list ->
            val on = list.fold(Duration.ZERO) { acc, i -> acc + i.screenOn }
            if (on < MIN_BLOCK_USE) null else rate(list.sumOf { it.screenOnShare }, on)
        }
        return Typical(
            idle = spread(idle.map { it.second }),
            inUse = use.takeIf { it.size >= MIN_PERIODS }?.let { spread(it) },
            periods = idle.size,
            days = idle.map { it.first }.distinct().size,
        )
    }

    /** How many usable idle periods, and over how many days, the baseline has so far. */
    fun progress(intervals: List<UsageInterval>, nowMillis: Long, zone: ZoneId, exclude: (UsageInterval) -> Boolean): Pair<Int, Int> {
        val idle = idleRates(blocks(intervals, nowMillis, zone, exclude))
        return idle.size to idle.map { it.first }.distinct().size
    }

    private fun blocks(intervals: List<UsageInterval>, nowMillis: Long, zone: ZoneId, exclude: (UsageInterval) -> Boolean) = intervals
        .filter { nowMillis - it.endMillis <= WINDOW.inWholeMilliseconds && !exclude(it) }
        .groupBy {
            val at = Instant.ofEpochMilli(it.endMillis).atZone(zone)
            at.toLocalDate() to at.hour / BLOCK_HOURS
        }

    private fun idleRates(blocks: Map<Pair<LocalDate, Int>, List<UsageInterval>>) = blocks.mapNotNull { (key, list) ->
        val off = list.fold(Duration.ZERO) { acc, i -> acc + i.screenOff }
        if (off < MIN_BLOCK_IDLE) null else key.first to rate(list.sumOf { it.screenOffShare }, off)!!
    }

    private fun spread(values: List<Double>): Spread {
        val sorted = values.sorted()
        return Spread(quantile(sorted, 0.5), quantile(sorted, 0.25), quantile(sorted, 0.75))
    }

    private fun quantile(sorted: List<Double>, q: Double): Double {
        val pos = q * (sorted.size - 1)
        val lo = pos.toInt()
        val hi = minOf(lo + 1, sorted.size - 1)
        return sorted[lo] + (sorted[hi] - sorted[lo]) * (pos - lo)
    }
}

private fun rate(share: Double, time: Duration): Double? {
    val hours = time.toDouble(DurationUnit.HOURS)
    return if (hours > 0) share * 100 / hours else null
}
