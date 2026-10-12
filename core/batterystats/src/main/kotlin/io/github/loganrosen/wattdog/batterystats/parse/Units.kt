package io.github.loganrosen.wattdog.batterystats.parse

import kotlin.math.roundToLong
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

internal object Units {
    private val durationToken = Regex("""([+-]?)(\d+)(ms|d|h|m|s)""")

    /** Matches a batterystats-style duration such as `3h 53m 55s 314ms`, `-2s -308ms`, or `+5m25s19ms`. */
    const val DURATION_PATTERN = """[+-]?\d+(?:ms|d|h|m|s)(?:\s?[+-]?\d+(?:ms|d|h|m|s))*"""

    /**
     * Parses both spaced (`1h 2m 3s 4ms`) and compact (`1h2m3s4ms`) forms. Android prints negative
     * durations either with every token signed (`-2s -308ms`) or only the first (`-3h53m55s`); both
     * mean the whole value is negative.
     */
    fun duration(text: String): Duration? {
        val trimmed = text.trim()
        var total = Duration.ZERO
        var matched = false
        var negative = false
        for (m in durationToken.findAll(trimmed)) {
            if (!matched && m.groupValues[1] == "-") negative = true
            matched = true
            val n = m.groupValues[2].toLong()
            total += when (m.groupValues[3]) {
                "d" -> n.days
                "h" -> n.hours
                "m" -> n.minutes
                "s" -> n.seconds
                else -> n.milliseconds
            }
        }
        if (!matched) return null
        return if (negative) -total else total
    }

    private val sizePattern = Regex("""^([\d.]+)\s*(B|KB|MB|GB|TB)$""")

    /** BatteryStats formats sizes with 1024 multipliers (`formatBytesLocked`). */
    fun bytes(text: String): Long? {
        val m = sizePattern.matchEntire(text.trim()) ?: return null
        val value = m.groupValues[1].toDoubleOrNull() ?: return null
        val multiplier = when (m.groupValues[2]) {
            "B" -> 1.0
            "KB" -> 1024.0
            "MB" -> 1024.0 * 1024
            "GB" -> 1024.0 * 1024 * 1024
            else -> 1024.0 * 1024 * 1024 * 1024
        }
        return (value * multiplier).roundToLong()
    }

    fun mah(text: String): Double? = text.trim().removeSuffix("mAh").trim().toDoubleOrNull()
}
