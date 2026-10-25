package io.github.loganrosen.wattdog.batterystats.analysis

import java.time.LocalDate
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes

class UsageLogTest {
    private val zone = ZoneOffset.UTC
    private val hour = 3_600_000L
    private val day0 = LocalDate.of(2026, 10, 1).atStartOfDay(zone).toInstant().toEpochMilli()

    /** Builds cumulative points from a list of (hours on screen, % used on screen, hours off, % used off) steps. */
    private fun points(session: String, startMillis: Long, steps: List<Step>, capacity: Int = 5000): List<UsagePoint> {
        var t = startMillis
        var p = UsagePoint(t, session, 0, 0, 0, 0, 0, capacity)
        val out = mutableListOf(p)
        for (s in steps) {
            val onMs = (s.onHours * hour).toLong()
            val offMs = (s.offHours * hour).toLong()
            t += onMs + offMs
            p = p.copy(
                capturedAtMillis = t,
                onBatteryMs = p.onBatteryMs + onMs + offMs,
                screenOnMs = p.screenOnMs + onMs,
                screenOnMah = p.screenOnMah + (s.onPct * capacity / 100).toInt(),
                screenOffMs = p.screenOffMs + offMs,
                screenOffMah = p.screenOffMah + (s.offPct * capacity / 100).toInt(),
            )
            out += p
        }
        return out
    }

    private data class Step(val onHours: Double, val onPct: Double, val offHours: Double, val offPct: Double)

    @Test
    fun `intervals only pair points from the same session`() {
        val a = points("s1", day0, listOf(Step(1.0, 10.0, 2.0, 2.0)))
        val b = points("s2", day0 + 5 * hour, listOf(Step(0.5, 5.0, 3.0, 3.0)))
        val intervals = UsageLog.intervals(a + b)
        assertEquals(2, intervals.size)
        assertEquals(listOf("s1", "s2"), intervals.map { it.session })
        assertEquals(2.hours, intervals[0].screenOff)
        assertEquals(0.02, intervals[0].screenOffShare, 1e-9)
        assertEquals(30.minutes, intervals[1].screenOn)
    }

    @Test
    fun `no baseline until enough blocks over enough days`() {
        // Two nights of eight hours each: plenty of blocks but only two days.
        val pts = (0 until 2).flatMap { d ->
            points("s$d", day0 + d * 24 * hour, List(4) { Step(0.0, 0.0, 2.0, 1.0) })
        }
        assertNull(UsageLog.typical(UsageLog.intervals(pts), day0 + 3 * 24 * hour, zone) { false })
    }

    @Test
    fun `baseline is the median screen-off rate`() {
        // Five nights at 0.5, 0.5, 0.6, 0.7, 2.0 % an hour, each 6h starting at midnight.
        val rates = listOf(0.5, 0.5, 0.6, 0.7, 2.0)
        val pts = rates.flatMapIndexed { d, r ->
            points("s$d", day0 + d * 24 * hour, listOf(Step(0.0, 0.0, 6.0, r * 6)))
        }
        val typical = assertNotNull(UsageLog.typical(UsageLog.intervals(pts), day0 + 6 * 24 * hour, zone) { false })
        assertEquals(5, typical.periods)
        assertEquals(5, typical.days)
        assertEquals(0.6, typical.idle.median, 0.01)
        assertEquals(0.5, typical.idle.low, 0.01)
        assertEquals(0.7, typical.idle.high, 0.01)
        assertNull(typical.inUse)
        assertFalse(typical.idle.isAbove(0.8))
        assertTrue(typical.idle.isAbove(1.5))
    }

    @Test
    fun `excluded and old intervals are left out`() {
        val rates = listOf(0.5, 0.5, 0.6, 0.7, 0.6, 3.0)
        val pts = rates.flatMapIndexed { d, r ->
            points("s$d", day0 + d * 24 * hour, listOf(Step(0.0, 0.0, 6.0, r * 6)))
        }
        val intervals = UsageLog.intervals(pts)
        val withoutLast = assertNotNull(UsageLog.typical(intervals, day0 + 6 * 24 * hour, zone) { it.session == "s5" })
        assertEquals(5, withoutLast.periods)
        assertTrue(withoutLast.idle.isAbove(3.0))
        // Thirty days later everything has aged out.
        assertNull(UsageLog.typical(intervals, day0 + 30 * 24 * hour, zone) { false })
    }

    @Test
    fun `days add up intervals ending on the same date`() {
        val pts = points("s", day0 + 8 * hour, listOf(Step(1.0, 10.0, 1.0, 1.0), Step(2.0, 20.0, 4.0, 2.0)))
        val days = UsageLog.days(UsageLog.intervals(pts), zone)
        assertEquals(1, days.size)
        assertEquals(3.hours, days[0].screenOn)
        assertEquals(0.30, days[0].screenOnShare, 1e-9)
        assertEquals(0.6, days[0].idlePercentPerHour!!, 1e-9)
    }
}
