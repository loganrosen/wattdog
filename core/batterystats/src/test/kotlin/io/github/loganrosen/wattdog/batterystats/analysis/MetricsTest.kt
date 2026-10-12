package io.github.loganrosen.wattdog.batterystats.analysis

import io.github.loganrosen.wattdog.batterystats.Fixtures
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.hours

class MetricsTest {
    private val r = Fixtures.report

    @Test
    fun `drain metrics split screen on and off`() {
        val m = DrainMetrics.from(r)
        assertEquals(2.hours, m.screenOn)
        assertEquals(8.hours, m.screenOff)
        assertEquals(0.625, m.screenOffAwakeRatio)
        assertEquals(150.0, m.overallMahPerHour)
        assertEquals(300.0, m.screenOnMahPerHour)
        assertEquals(112.5, m.screenOffMahPerHour)
        assertEquals(2.25, m.screenOffPercentPerHour!!, 1e-9)
    }

    @Test
    fun `radio attribution separates standby from app traffic`() {
        val radio = RadioAttribution.from(r)
        assertEquals(250.0, radio.cellularDrainMah)
        assertEquals(40.0, radio.cellularAppsMah)
        assertEquals(220.0, radio.cellularStandbyMah)
        assertEquals(250.0 / 12, radio.mahPerMb!!, 1e-9)
        assertEquals(0.5, radio.poorSignalRatio!!, 1e-9)
        assertEquals(0.1, radio.outOfServiceRatio!!, 1e-9)
        assertEquals(0.2, radio.bluetoothScanRatio!!, 1e-9)
    }

    @Test
    fun `doze summary`() {
        val d = DozeSummary.from(r)
        assertEquals(120.0, d.screenDozeMahPerHour)
        assertEquals(40.0, d.ambientDisplayMah)
        assertEquals(0.125, d.deepIdleRatio)
        assertEquals(120.0 / 900, d.screenDozeShareOfScreenOffMah!!, 1e-9)
    }
}
