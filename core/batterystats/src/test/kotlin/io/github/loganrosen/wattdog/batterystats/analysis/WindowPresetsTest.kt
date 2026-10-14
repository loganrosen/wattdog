package io.github.loganrosen.wattdog.batterystats.analysis

import java.time.LocalDateTime
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes

class WindowPresetsTest {
    private val zone = ZoneId.of("America/New_York")
    private fun at(day: Int, hour: Int, minute: Int = 0) =
        LocalDateTime.of(2026, 10, day, hour, minute).atZone(zone).toInstant().toEpochMilli()

    private fun pick(points: List<Long>, now: Long) = WindowPresets.pick(points, { it }, now, zone).associate { it.kind to it.point }

    @Test
    fun `hourly captures map to recent presets`() {
        val now = at(13, 20, 10)
        val points = (1..8).map { now - it.hours.inWholeMilliseconds + 5.minutes.inWholeMilliseconds }
        val presets = pick(points, now)
        assertEquals(points[0], presets[WindowKind.LAST_HOUR])
        assertEquals(points[2], presets[WindowKind.LAST_3H])
        assertEquals(points[5], presets[WindowKind.LAST_6H])
        assertEquals(null, presets[WindowKind.OVERNIGHT])
    }

    @Test
    fun `presets without a nearby capture are left out`() {
        val now = at(13, 20)
        // Only one capture, 5h ago: too far from 3h and 1h, close enough to 6h.
        val presets = pick(listOf(now - 5.hours.inWholeMilliseconds), now)
        assertEquals(setOf(WindowKind.LAST_6H), presets.keys)
    }

    @Test
    fun `captures younger than half an hour are ignored`() {
        val now = at(13, 20)
        assertEquals(emptyMap(), pick(listOf(now - 10.minutes.inWholeMilliseconds), now))
    }

    @Test
    fun `overnight in the morning picks the capture nearest 11 PM`() {
        val now = at(13, 7, 30)
        val points = listOf(at(12, 21, 40), at(12, 22, 50), at(13, 1, 10), at(13, 6, 20))
        val presets = pick(points, now)
        assertEquals(at(12, 22, 50), presets[WindowKind.OVERNIGHT])
        assertEquals(at(13, 6, 20), presets[WindowKind.LAST_HOUR])
    }

    @Test
    fun `one snapshot is not offered under two names`() {
        val now = at(13, 5, 0)
        // 11 PM is exactly 6h ago; overnight wins.
        val presets = WindowPresets.pick(listOf(at(12, 23)), { it }, now, zone)
        assertEquals(listOf(WindowKind.OVERNIGHT), presets.map { it.kind })
    }
}
