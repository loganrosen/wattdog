package io.github.loganrosen.wattdog.batterystats.analysis

import kotlin.math.roundToLong
import kotlin.time.Duration

/** Compact human duration, e.g. `3h 53m`, `12m 4s`, `850ms`. */
fun Duration.formatShort(): String {
    val negative = isNegative()
    val abs = absoluteValue
    val text = abs.toComponents { days, hours, minutes, seconds, nanos ->
        when {
            days > 0 -> "${days}d ${hours}h"
            hours > 0 -> "${hours}h ${minutes}m"
            minutes > 0 -> "${minutes}m ${seconds}s"
            seconds > 0 -> "${seconds}s"
            else -> "${nanos / 1_000_000}ms"
        }
    }
    return if (negative) "-$text" else text
}

fun formatBytes(bytes: Long): String = when {
    bytes >= 1L shl 30 -> "%.2f GB".format(bytes / (1L shl 30).toDouble())
    bytes >= 1L shl 20 -> "%.1f MB".format(bytes / (1L shl 20).toDouble())
    bytes >= 1L shl 10 -> "%.0f KB".format(bytes / (1L shl 10).toDouble())
    else -> "$bytes B"
}

fun formatPercent(ratio: Double): String = "${(ratio * 100).roundToLong()}%"

fun formatMah(mah: Double): String = if (mah >= 10) "${mah.roundToLong()} mAh" else "%.1f mAh".format(mah)
