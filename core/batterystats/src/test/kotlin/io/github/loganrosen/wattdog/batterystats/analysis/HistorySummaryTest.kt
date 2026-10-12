package io.github.loganrosen.wattdog.batterystats.analysis

import io.github.loganrosen.wattdog.batterystats.Fixtures
import io.github.loganrosen.wattdog.batterystats.model.AppUid
import io.github.loganrosen.wattdog.batterystats.parse.HistoryParser
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class HistorySummaryTest {
    private val hour = 3_600_000L

    @Test
    fun `screen on and off rates`() {
        val h = HistoryParser.parse(
            """
            Battery History (0% used):
                                0 (14) RESET:TIME: 2026-01-01-00-00-00
                                0 (2) 100 status=discharging +running +screen +top=u0a5:"com.example.a"
                              +1h (2) 095 -screen -top=u0a5:"com.example.a"
                         +1h30m (2) 095 -running
                              +2h (2) 094 +wake_lock=1000:"x"
                           +2h1m (2) 094 -wake_lock=1000:"y"
                              +4h (2) 092
            """.trimIndent(),
        )
        val s = HistorySummary.from(h)
        assertEquals(100 to 92, s.levelStart to s.levelEnd)
        assertEquals(hour, s.screenOnMillis)
        assertEquals(3 * hour, s.screenOffMillis)
        assertEquals(hour / 2, s.screenOffAwakeMillis)
        assertEquals(5.0, s.screenOnDropPerHour)
        assertEquals(1.0, s.screenOffDropPerHour)
        assertEquals(listOf(HistorySummary.UidTime(AppUid(10005), hour, 1)), s.topApps)
        assertEquals("x" to 60_000L, s.wakelocks.single().let { it.tag to it.millis })
    }

    @Test
    fun `charging time is excluded from drain rates`() {
        val h = HistoryParser.parse(
            """
            Battery History (0% used):
                                0 (14) RESET:TIME: 2026-01-01-00-00-00
                                0 (2) 050 status=discharging
                              +1h (2) 049 +plugged
                              +2h (2) 080 -plugged
                              +3h (2) 079
            """.trimIndent(),
        )
        val s = HistorySummary.from(h)
        assertEquals(hour, s.pluggedMillis)
        assertEquals(1.0, s.screenOffDropPerHour)
        assertNull(s.screenOnDropPerHour)
    }

    @Test
    fun `real pixel history`() {
        val s = HistorySummary.from(HistoryParser.parse(Fixtures.text("real/pixel9-api37-history.txt.gz")))
        assertEquals(47 to 16, s.levelStart to s.levelEnd)
        assertTrue(s.screenOnMillis in 1..s.screenOffMillis + s.screenOnMillis)
        assertTrue(assertNotNull(s.screenOffAwakeRatio) in 0.0..1.0)
        assertTrue(s.deepIdleMillis > 0)
        assertTrue(s.topApps.isNotEmpty() && s.wakelocks.isNotEmpty() && s.jobs.isNotEmpty())
        assertTrue(s.topApps.zipWithNext().all { (a, b) -> a.millis >= b.millis })
    }

    @Test
    fun `start clock time`() {
        val h = HistoryParser.parse(Fixtures.text("real/emulator-api34-history.txt.gz"))
        assertEquals(h.startMillis, HistorySummary.clockMillis("2026-10-12-19-11-16"))
        assertNull(HistorySummary.clockMillis("nope"))
    }
}
