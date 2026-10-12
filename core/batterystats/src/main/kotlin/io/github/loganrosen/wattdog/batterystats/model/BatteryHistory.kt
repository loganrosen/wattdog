package io.github.loganrosen.wattdog.batterystats.model

/**
 * A timeline reconstructed from `dumpsys batterystats --history`.
 *
 * Timestamps are wall-clock milliseconds as the device printed them, interpreted as UTC so they
 * stay independent of the viewer's time zone. They are not true epoch instants.
 */
data class BatteryHistory(
    val startMillis: Long,
    val endMillis: Long,
    val levels: List<LevelPoint>,
    /** On/off states keyed by their history name, e.g. `screen`, `running`, `mobile_radio`. */
    val flags: Map<String, List<HistorySpan>>,
    /** Enumerated states keyed by name, e.g. `device_idle=full`, `phone_signal_strength=good`. */
    val states: Map<String, List<ValueSpan>>,
    /** UID-attributed spans keyed by name: `wake_lock`, `job`, `top`. */
    val tagged: Map<String, List<TaggedSpan>>,
    val markers: List<HistoryMarker>,
) {
    val durationMillis: Long get() = endMillis - startMillis

    fun flag(name: String): List<HistorySpan> = flags[name].orEmpty()

    fun state(name: String): List<ValueSpan> = states[name].orEmpty()

    fun tagged(name: String): List<TaggedSpan> = tagged[name].orEmpty()

    /** Restricts the timeline to `[from, to]`, clipping spans that cross the boundaries. */
    fun window(from: Long, to: Long = endMillis): BatteryHistory {
        val lo = from.coerceIn(startMillis, endMillis)
        val hi = to.coerceIn(lo, endMillis)
        val before = levels.lastOrNull { it.millis <= lo }
        val inside = levels.filter { it.millis in (lo + 1)..hi }
        return BatteryHistory(
            startMillis = lo,
            endMillis = hi,
            levels = listOfNotNull(before?.copy(millis = lo)) + inside,
            flags = flags.mapValues { (_, v) -> v.clip(lo, hi) { s, e -> HistorySpan(s, e) } },
            states = states.mapValues { (_, v) -> v.clip(lo, hi) { s, e -> copy(startMillis = s, endMillis = e) } },
            tagged = tagged.mapValues { (_, v) -> v.clip(lo, hi) { s, e -> copy(startMillis = s, endMillis = e) } },
            markers = markers.filter { it.millis in lo..hi },
        )
    }

    companion object {
        const val SCREEN = "screen"
        const val SCREEN_DOZE = "screen_doze"
        const val PLUGGED = "plugged"
        const val RUNNING = "running"
        const val WAKE_LOCK = "wake_lock"
        const val MOBILE_RADIO = "mobile_radio"
        const val WIFI_RADIO = "wifi_radio"
        const val GPS = "gps"
        const val BLE_SCAN = "ble_scan"
        const val AUDIO = "audio"
        const val VIDEO = "video"
        const val CAMERA = "camera"
        const val CELL_HIGH_TX = "cellular_high_tx_power"
        const val DEVICE_IDLE = "device_idle"
        const val SIGNAL = "phone_signal_strength"
        const val DATA_CONN = "data_conn"
        const val JOB = "job"
        const val TOP = "top"
    }
}

interface TimeSpan {
    val startMillis: Long
    val endMillis: Long
    val lengthMillis: Long get() = endMillis - startMillis
}

data class HistorySpan(override val startMillis: Long, override val endMillis: Long) : TimeSpan

data class ValueSpan(override val startMillis: Long, override val endMillis: Long, val value: String) : TimeSpan

data class TaggedSpan(override val startMillis: Long, override val endMillis: Long, val uid: AppUid?, val tag: String) : TimeSpan

data class LevelPoint(val millis: Long, val level: Int)

data class HistoryMarker(val millis: Long, val kind: Kind) {
    enum class Kind { RESET, START, SHUTDOWN, OVERFLOW }
}

/** Total time covered by [spans], counting overlaps once. */
fun List<TimeSpan>.coveredMillis(): Long {
    var total = 0L
    var curStart = Long.MIN_VALUE
    var curEnd = Long.MIN_VALUE
    for (s in sortedBy { it.startMillis }) {
        if (s.startMillis > curEnd) {
            if (curEnd > curStart) total += curEnd - curStart
            curStart = s.startMillis
            curEnd = s.endMillis
        } else if (s.endMillis > curEnd) {
            curEnd = s.endMillis
        }
    }
    if (curEnd > curStart) total += curEnd - curStart
    return total
}

/** Merges spans separated by less than [gapMillis], for drawing dense lanes at low resolution. */
fun List<TimeSpan>.coalesce(gapMillis: Long): List<HistorySpan> {
    val out = ArrayList<HistorySpan>()
    for (s in sortedBy { it.startMillis }) {
        val last = out.lastOrNull()
        if (last != null && s.startMillis - last.endMillis <= gapMillis) {
            if (s.endMillis > last.endMillis) out[out.lastIndex] = last.copy(endMillis = s.endMillis)
        } else {
            out += HistorySpan(s.startMillis, s.endMillis)
        }
    }
    return out
}

private inline fun <T : TimeSpan> List<T>.clip(lo: Long, hi: Long, rebuild: T.(Long, Long) -> T): List<T> = mapNotNull {
    val s = maxOf(it.startMillis, lo)
    val e = minOf(it.endMillis, hi)
    when {
        e < s || (e == s && it.lengthMillis > 0) -> null
        s == it.startMillis && e == it.endMillis -> it
        else -> it.rebuild(s, e)
    }
}
