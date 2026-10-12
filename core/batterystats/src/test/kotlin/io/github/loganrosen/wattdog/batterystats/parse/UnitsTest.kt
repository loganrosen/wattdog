package io.github.loganrosen.wattdog.batterystats.parse

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

class UnitsTest {
    @Test
    fun `parses spaced and compact durations`() {
        assertEquals(3.hours + 53.minutes + 55.seconds + 314.milliseconds, Units.duration("3h 53m 55s 314ms"))
        assertEquals(4.hours + 50.minutes, Units.duration("4h50m0s0ms"))
        assertEquals(850.milliseconds, Units.duration("850ms"))
    }

    @Test
    fun `negative durations are negative as a whole`() {
        assertEquals(-(2.seconds + 308.milliseconds), Units.duration("-2s -308ms"))
        assertEquals(-(26.minutes + 46.seconds + 249.milliseconds), Units.duration("-26m46s249ms"))
    }

    @Test
    fun `rejects non-durations`() {
        assertNull(Units.duration("never"))
        assertNull(Units.bytes("lots"))
    }

    @Test
    fun `bytes use 1024 multipliers`() {
        assertEquals(1024L, Units.bytes("1.00KB"))
        assertEquals(2L * 1024 * 1024, Units.bytes("2.00MB"))
        assertEquals(Math.round(1.59 * 1024 * 1024 * 1024), Units.bytes("1.59GB"))
        assertEquals(0L, Units.bytes("0B"))
    }

    @Test
    fun `mah strips suffix`() {
        assertEquals(334.0, Units.mah("334mAh"))
        assertEquals(67.67, Units.mah(" 67.67mAh"))
    }
}
