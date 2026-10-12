package io.github.loganrosen.wattdog.batterystats.parse

import io.github.loganrosen.wattdog.batterystats.Fixtures
import io.github.loganrosen.wattdog.batterystats.model.AppUid
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

class PowerDumpParserTest {
    private val p = Fixtures.power

    @Test
    fun `uses the first occurrence of each field`() {
        assertEquals("Asleep", p.wakefulness)
        assertEquals(false, p.isPowered)
        assertEquals(70, p.batteryLevel)
        assertEquals(true, p.lightDeviceIdleMode)
        assertEquals(false, p.deviceIdleMode)
        assertEquals(true, p.holdingWakeLockSuspendBlocker)
    }

    @Test
    fun `parses held wakelocks and blames the work source`() {
        assertEquals(3, p.wakeLocks.size)
        val audio = p.wakeLocks[0]
        assertEquals("PARTIAL_WAKE_LOCK", audio.type)
        assertEquals("AudioMix", audio.tag)
        assertEquals(4.hours + 50.minutes, audio.heldFor)
        assertEquals(AppUid(1041), audio.ownerUid)
        assertEquals(listOf(AppUid(10250)), audio.blamedUids)

        val chained = p.wakeLocks[1]
        assertEquals(1.minutes + 2.seconds + 3.milliseconds, chained.heldFor)
        assertEquals(listOf(AppUid(10301)), chained.blamedUids)

        val doze = p.wakeLocks[2]
        assertEquals("DOZE_WAKE_LOCK", doze.type)
        assertEquals(listOf(AppUid(1000)), doze.blamedUids)
    }
}
