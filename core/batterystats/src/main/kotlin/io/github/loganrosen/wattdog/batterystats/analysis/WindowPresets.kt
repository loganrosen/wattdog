package io.github.loganrosen.wattdog.batterystats.analysis

import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import kotlin.math.abs
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes

enum class WindowKind(val label: String) {
    LAST_HOUR("Last hour"),
    LAST_3H("Last 3 hours"),
    LAST_6H("Last 6 hours"),
    OVERNIGHT("Overnight"),
}

data class WindowPreset<T>(val kind: WindowKind, val point: T, val startMillis: Long)

/**
 * Maps "last hour", "overnight", and so on onto the automatic snapshots that exist. Captures are
 * inexact (WorkManager, Doze), so each preset takes the closest snapshot within a tolerance and is
 * left out when none is close enough rather than showing a misleading window.
 */
object WindowPresets {
    private val recent = listOf(WindowKind.LAST_HOUR to 1.hours, WindowKind.LAST_3H to 3.hours, WindowKind.LAST_6H to 6.hours)
    private val MIN_AGE = 30.minutes
    private val OVERNIGHT_START: LocalTime = LocalTime.of(23, 0)
    private val OVERNIGHT_TOLERANCE = 2.hours

    /** [points] must all belong to the current stats session. */
    fun <T> pick(points: List<T>, capturedAt: (T) -> Long, nowMillis: Long, zone: ZoneId): List<WindowPreset<T>> {
        val usable = points.filter { nowMillis - capturedAt(it) >= MIN_AGE.inWholeMilliseconds }
        val presets = mutableListOf<WindowPreset<T>>()

        overnightTarget(nowMillis, zone)?.let { target ->
            closest(usable, capturedAt, target, OVERNIGHT_TOLERANCE)?.let {
                presets += WindowPreset(WindowKind.OVERNIGHT, it, capturedAt(it))
            }
        }
        for ((kind, ago) in recent) {
            val tolerance = maxOf(20.minutes, ago * 0.35)
            val point = closest(usable, capturedAt, nowMillis - ago.inWholeMilliseconds, tolerance) ?: continue
            if (presets.none { it.point == point }) presets += WindowPreset(kind, point, capturedAt(point))
        }
        return presets.sortedBy { it.kind.ordinal }
    }

    /** 11 PM last night, but only in the morning, when "overnight" is what someone wants to see. */
    private fun overnightTarget(nowMillis: Long, zone: ZoneId): Long? {
        val now = Instant.ofEpochMilli(nowMillis).atZone(zone)
        if (now.hour !in 4..13) return null
        return now.toLocalDate().minusDays(1).atTime(OVERNIGHT_START).atZone(zone).toInstant().toEpochMilli()
    }

    private fun <T> closest(points: List<T>, capturedAt: (T) -> Long, target: Long, tolerance: Duration): T? = points
        .filter { abs(capturedAt(it) - target) <= tolerance.inWholeMilliseconds }
        .minByOrNull { abs(capturedAt(it) - target) }
}
