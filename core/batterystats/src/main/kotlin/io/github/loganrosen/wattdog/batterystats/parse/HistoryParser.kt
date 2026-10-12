package io.github.loganrosen.wattdog.batterystats.parse

import io.github.loganrosen.wattdog.batterystats.model.AppUid
import io.github.loganrosen.wattdog.batterystats.model.BatteryHistory
import io.github.loganrosen.wattdog.batterystats.model.HistoryMarker
import io.github.loganrosen.wattdog.batterystats.model.HistorySpan
import io.github.loganrosen.wattdog.batterystats.model.LevelPoint
import io.github.loganrosen.wattdog.batterystats.model.TaggedSpan
import io.github.loganrosen.wattdog.batterystats.model.ValueSpan
import java.time.LocalDateTime
import java.time.ZoneOffset

/**
 * Streaming parser for `dumpsys batterystats --history`.
 *
 * Handles both line formats:
 * - Android 15+ (`Battery History [Format: 2]`): `  MM-DD HH:MM:SS.mmm LLL events`
 * - Android 14: `  +1h2m3s4ms (2) LLL events`, offsets relative to the last `RESET:TIME:`/`TIME:`.
 *
 * The first line after a reset lists the full state; later lines carry deltas (`+screen`,
 * `-wake_lock`, `device_idle=full`, `+job=u0a12:"tag"`).
 */
object HistoryParser {
    private val absoluteLine = Regex("""^ {1,4}(\d\d)-(\d\d) (\d\d):(\d\d):(\d\d)\.(\d{3})(?: (.*))?$""")
    private val relativeLine = Regex("""^\s+(0|\+[0-9dhms]+) \((\d+)\)(?: (.*))?$""")
    private val relativeToken = Regex("""(\d+)(ms|d|h|m|s)""")
    private val levelToken = Regex("""^\d{3}$""")
    private val tagValue = Regex("""^([^:"]*):"(.*)"$""")

    private val stateKeys = setOf(
        "status",
        "plug",
        BatteryHistory.DEVICE_IDLE,
        BatteryHistory.SIGNAL,
        BatteryHistory.DATA_CONN,
        "phone_state",
        "wifi_signal_strength",
    )
    private val taggedKeys = setOf(BatteryHistory.WAKE_LOCK, BatteryHistory.JOB, BatteryHistory.TOP)

    fun parse(text: String): BatteryHistory = parse(text.lineSequence())

    fun parse(lines: Sequence<String>, defaultYear: Int = LocalDateTime.now().year): BatteryHistory {
        val b = Builder(defaultYear)
        for (line in lines) {
            if (line.isBlank()) continue
            if (!line[0].isWhitespace()) {
                if (line.startsWith("Battery History")) continue
                if (b.sawEvent) break else continue
            }
            b.accept(line)
        }
        return b.build()
    }

    private class Builder(var year: Int) {
        var sawEvent = false
        private var lastMillis = Long.MIN_VALUE
        private var firstMillis: Long? = null
        private var lastMonth = 0
        private var anchorClock: Long? = null
        private var anchorOffset = 0L

        private var level: Int? = null
        private val levels = ArrayList<LevelPoint>()
        private val markers = ArrayList<HistoryMarker>()

        private val flags = LinkedHashMap<String, MutableList<HistorySpan>>()
        private val openFlags = LinkedHashMap<String, Long>()
        private val states = LinkedHashMap<String, MutableList<ValueSpan>>()
        private val openStates = LinkedHashMap<String, Pair<Long, String>>()
        private val tagged = LinkedHashMap<String, MutableList<TaggedSpan>>()
        private val openTagged = LinkedHashMap<Triple<String, String, String>, Long>()

        fun accept(line: String) {
            absoluteLine.matchEntire(line)?.let { m ->
                val month = m.groupValues[1].toInt()
                if (month < lastMonth) year++
                lastMonth = month
                val ldt = LocalDateTime.of(
                    year,
                    month,
                    m.groupValues[2].toInt(),
                    m.groupValues[3].toInt(),
                    m.groupValues[4].toInt(),
                    m.groupValues[5].toInt(),
                )
                body(ldt.toEpochSecond(ZoneOffset.UTC) * 1000 + m.groupValues[6].toInt(), m.groupValues[7])
                return
            }
            relativeLine.matchEntire(line)?.let { m ->
                val offset = relativeMillis(m.groupValues[1])
                val body = m.groupValues[3]
                val clock = timeLine(body)
                if (clock != null) {
                    anchorClock = clock
                    anchorOffset = offset
                }
                body((anchorClock ?: 0L) + offset - anchorOffset, body)
            }
        }

        private fun body(rawMillis: Long, body: String) {
            val t = maxOf(rawMillis, lastMillis)
            lastMillis = t
            if (firstMillis == null) firstMillis = t
            sawEvent = true
            when {
                body.startsWith("RESET:TIME:") -> {
                    timeLine(body)?.let { syncYear(it) }
                    closeAll(t)
                    markers += HistoryMarker(t, HistoryMarker.Kind.RESET)
                }

                body.startsWith("TIME:") -> timeLine(body)?.let { syncYear(it) }

                body.startsWith("START") -> {
                    closeAll(t)
                    markers += HistoryMarker(t, HistoryMarker.Kind.START)
                }

                body.startsWith("SHUTDOWN") -> {
                    closeAll(t)
                    markers += HistoryMarker(t, HistoryMarker.Kind.SHUTDOWN)
                }

                body.startsWith("*OVERFLOW*") -> markers += HistoryMarker(t, HistoryMarker.Kind.OVERFLOW)

                else -> events(t, tokenize(body))
            }
        }

        private fun syncYear(clock: Long) {
            val ldt = LocalDateTime.ofEpochSecond(clock / 1000, 0, ZoneOffset.UTC)
            year = ldt.year
            lastMonth = ldt.monthValue
        }

        private fun events(t: Long, tokens: List<String>) {
            var i = 0
            if (tokens.isNotEmpty() && levelToken.matches(tokens[0])) {
                val l = tokens[0].toInt()
                if (l != level) levels += LevelPoint(t, l)
                level = l
                i = 1
            }
            while (i < tokens.size) {
                val tok = tokens[i++]
                val sign = tok[0]
                val signed = sign == '+' || sign == '-'
                val body = if (signed) tok.substring(1) else tok
                val eq = body.indexOf('=')
                val key = if (eq >= 0) body.substring(0, eq) else body
                val value = if (eq >= 0) body.substring(eq + 1) else null
                when {
                    value == null && signed -> {
                        if (sign == '+') openFlag(key, t) else closeFlag(key, t)
                        if (sign == '-' && key in taggedKeys) closeTagged(key, t) { true }
                    }

                    value == null -> Unit

                    key in taggedKeys -> {
                        val m = tagValue.matchEntire(value)
                        val uid = m?.groupValues?.get(1).orEmpty()
                        val tag = m?.groupValues?.get(2) ?: value
                        if (sign == '-') {
                            // The history records the wakelock pool as one span: `+` names the first
                            // holder and `-` the last releaser, which are usually different tags.
                            if (key == BatteryHistory.WAKE_LOCK) {
                                closeTagged(key, t) { true }
                                closeFlag(key, t)
                            } else {
                                closeTagged(key, t) { it.second == uid && it.third == tag }
                            }
                        } else {
                            openTagged.putIfAbsent(Triple(key, uid, tag), t)
                            if (key == BatteryHistory.WAKE_LOCK) openFlag(key, t)
                        }
                    }

                    !signed && key in stateKeys -> setState(key, value, t)
                }
            }
        }

        private fun openFlag(name: String, t: Long) {
            openFlags.putIfAbsent(name, t)
        }

        private fun closeFlag(name: String, t: Long) {
            val start = openFlags.remove(name) ?: return
            flags.getOrPut(name) { ArrayList() } += HistorySpan(start, t)
        }

        private fun setState(key: String, value: String, t: Long) {
            val open = openStates[key]
            if (open?.second == value) return
            if (open != null) states.getOrPut(key) { ArrayList() } += ValueSpan(open.first, t, open.second)
            openStates[key] = t to value
        }

        private inline fun closeTagged(key: String, t: Long, match: (Triple<String, String, String>) -> Boolean) {
            val it = openTagged.entries.iterator()
            while (it.hasNext()) {
                val (k, start) = it.next()
                if (k.first != key || !match(k)) continue
                it.remove()
                tagged.getOrPut(key) { ArrayList() } += TaggedSpan(start, t, AppUid.parse(k.second), k.third)
            }
        }

        private fun closeAll(t: Long) {
            openFlags.keys.toList().forEach { closeFlag(it, t) }
            openStates.forEach { (k, v) -> states.getOrPut(k) { ArrayList() } += ValueSpan(v.first, t, v.second) }
            openStates.clear()
            taggedKeys.forEach { key -> closeTagged(key, t) { true } }
        }

        fun build(): BatteryHistory {
            val start = firstMillis ?: 0L
            val end = maxOf(lastMillis, start)
            closeAll(end)
            return BatteryHistory(
                startMillis = start,
                endMillis = end,
                levels = levels,
                flags = flags,
                states = states,
                tagged = tagged.mapValues { (_, v) -> v.sortedBy { it.startMillis } },
                markers = markers,
            )
        }
    }

    private fun relativeMillis(text: String): Long {
        if (text == "0") return 0
        var total = 0L
        for (m in relativeToken.findAll(text)) {
            val n = m.groupValues[1].toLong()
            total += when (m.groupValues[2]) {
                "d" -> n * 86_400_000
                "h" -> n * 3_600_000
                "m" -> n * 60_000
                "s" -> n * 1000
                else -> n
            }
        }
        return total
    }

    /** Parses `TIME: 2026-10-12-19-11-16` (optionally prefixed by `RESET:`) into wall-clock millis. */
    private fun timeLine(body: String): Long? {
        if (!body.startsWith("TIME:") && !body.startsWith("RESET:TIME:")) return null
        val p = body.substringAfter("TIME:").trim().split('-').mapNotNull { it.toIntOrNull() }
        if (p.size < 6) return null
        return LocalDateTime.of(p[0], p[1], p[2], p[3], p[4], p[5]).toEpochSecond(ZoneOffset.UTC) * 1000
    }

    /** Splits on spaces outside double quotes; quoted tags can contain spaces. */
    internal fun tokenize(body: String): List<String> {
        val out = ArrayList<String>()
        val sb = StringBuilder()
        var quoted = false
        for (c in body) {
            when {
                c == '"' -> {
                    quoted = !quoted
                    sb.append(c)
                }

                c == ' ' && !quoted -> if (sb.isNotEmpty()) {
                    out += sb.toString()
                    sb.setLength(0)
                }

                else -> sb.append(c)
            }
        }
        if (sb.isNotEmpty()) out += sb.toString()
        return out
    }
}
