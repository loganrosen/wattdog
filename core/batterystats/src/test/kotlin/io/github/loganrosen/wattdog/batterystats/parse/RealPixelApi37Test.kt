package io.github.loganrosen.wattdog.batterystats.parse

import io.github.loganrosen.wattdog.batterystats.Fixtures
import io.github.loganrosen.wattdog.batterystats.analysis.AnomalyDetector
import io.github.loganrosen.wattdog.batterystats.analysis.AnomalyKind
import io.github.loganrosen.wattdog.batterystats.analysis.DrainCost
import io.github.loganrosen.wattdog.batterystats.analysis.DrainMetrics
import io.github.loganrosen.wattdog.batterystats.analysis.RadioAttribution
import io.github.loganrosen.wattdog.batterystats.analysis.Severity
import io.github.loganrosen.wattdog.batterystats.model.AppUid
import io.github.loganrosen.wattdog.batterystats.model.WakelockKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/** Pixel 9 Pro XL, Android API 37, ~6.6 h on battery. */
class RealPixelApi37Test {
    private val r = Fixtures.Real.pixelApi37

    @Test
    fun `summary matches the dump header`() {
        assertEquals("2026-10-12-11-11-46", r.startClockTime)
        assertEquals(false, r.currentlyOnBattery)
        assertEquals(4480, r.estimatedCapacityMah)
        assertEquals(6.hours + 37.minutes + 53.seconds + 163.milliseconds, r.onBattery.realtime)
        assertEquals(4.hours + 19.minutes + 0.seconds + 804.milliseconds, r.screenOff.realtime)
        assertEquals(52.minutes + 20.seconds + 986.milliseconds, r.screenDoze)
        assertEquals(82, r.screenOnCount)
        with(r.discharge) {
            assertEquals(2705, totalMah)
            assertEquals(1950, screenOnMah)
            assertEquals(755, screenOffMah)
            assertEquals(383, screenDozeMah)
            assertEquals(598, deepDozeMah)
        }
        assertEquals(64, r.dischargePercent?.lowerBound)
        assertEquals(12, r.dischargePercent?.screenDoze)
    }

    @Test
    fun `doze, wakelock and bluetooth totals`() {
        assertEquals(3.hours + 25.minutes + 19.seconds + 92.milliseconds, r.doze.deepIdling)
        assertEquals(31, r.doze.deepIdlingCount)
        assertEquals(56.minutes + 14.seconds + 15.milliseconds, r.doze.longestDeepIdle)
        assertEquals(4.hours + 19.minutes + 3.seconds + 532.milliseconds, r.totalPartialWakelock)
        assertEquals(24.minutes + 22.seconds + 114.milliseconds, r.totalFullWakelock)
        assertEquals(1.hours + 36.minutes + 3.seconds + 54.milliseconds, r.wifiMulticastWakelock)
        assertEquals(r.onBattery.realtime, r.bluetoothScan)
    }

    @Test
    fun `connectivity`() {
        with(r.cellular) {
            assertEquals(2.hours + 45.minutes + 4.seconds + 984.milliseconds, kernelActive)
            assertEquals(334.0, drainMah)
            assertTrue(radioAccessTech.isNotEmpty(), "RAT breakdown: $radioAccessTech")
            assertTrue(signalStrength.isNotEmpty(), "signal buckets: $signalStrength")
        }
        with(r.wifi) {
            assertEquals(549.0, drainMah)
            assertEquals(1.hours + 24.minutes + 6.seconds + 897.milliseconds, scan)
            assertTrue(rxBytes > 1_500_000_000L, "wifi rx $rxBytes")
        }
        assertEquals(67.67, r.gpsDrainMah)
    }

    @Test
    fun `global power block`() {
        val radio = assertNotNull(r.globalComponent("mobile_radio"))
        assertEquals(307.0, radio.totalMah)
        assertEquals(249.0, radio.appsMah)
        assertEquals(16.4, r.globalComponent("ambient_display")?.totalMah)
        assertEquals(236.0, r.globalPowerScreenOff.first { it.name == "mobile_radio" }.totalMah)
        assertEquals(4.hours + 10.minutes + 46.seconds + 411.milliseconds, r.globalComponent("cpu")?.duration)
    }

    @Test
    fun `per-uid power is sorted and plausible`() {
        assertTrue(r.uidPower.size > 50, "uid rows: ${r.uidPower.size}")
        val top = r.uidPower.first()
        assertEquals(AppUid.parse("u0a204"), top.uid)
        assertEquals(268.0, top.totalMah)
        assertEquals(110.0, top.cachedMah)
        assertEquals(150.0, top.components["cpu"])
        assertTrue(r.uidPower.sumOf { it.totalMah } <= 2705 * 1.1)
        assertTrue(r.uidPower.zipWithNext().all { (a, b) -> a.totalMah >= b.totalMah })
    }

    @Test
    fun `wakelocks and uid details`() {
        assertTrue(r.partialWakelocks.size > 100, "partial: ${r.partialWakelocks.size}")
        assertTrue(r.partialWakelocks.zipWithNext().all { (a, b) -> a.total >= b.total })
        assertTrue(r.partialWakelocks.any { it.kind == WakelockKind.JOB })
        assertTrue(r.partialWakelocks.any { it.kind == WakelockKind.ALARM })
        assertTrue(r.kernelWakelocks.isNotEmpty())
        assertTrue(r.wakeupReasons.isNotEmpty())

        assertTrue(r.uids.size > 100, "uid detail blocks: ${r.uids.size}")
        assertTrue(r.uids.values.any { it.jobs.isNotEmpty() })
        assertTrue(r.uids.values.any { it.wakeupAlarms.isNotEmpty() })
        assertTrue(r.uids.values.sumOf { it.mobileBytes } > 0)
    }

    @Test
    fun `analysis runs end to end`() {
        val m = DrainMetrics.from(r)
        assertNotNull(m.screenOffMahPerHour)
        assertTrue(m.screenOffMahPerHour!! in 100.0..300.0, "screen-off rate ${m.screenOffMahPerHour}")

        val radio = RadioAttribution.from(r)
        assertEquals(58.0, radio.cellularStandbyMah, 0.5)

        val anomalies = AnomalyDetector().detect(r, Fixtures.Real.pixelApi37Power)
        assertTrue(anomalies.isNotEmpty())
        assertTrue(anomalies.all { it.advice.isNotBlank() && it.actions.isNotEmpty() })
    }

    @Test
    fun `stuck Messenger AudioMix timer is reported as a phantom, not drain`() {
        for (power in listOf(Fixtures.Real.pixelApi37Power, null)) {
            val anomalies = AnomalyDetector().detect(r, power)
            val phantom = anomalies.single { it.kind == AnomalyKind.PHANTOM_WAKELOCK }
            assertEquals(AppUid(10316), phantom.uid)
            assertEquals(Severity.INFO, phantom.severity)
            assertEquals(DrainCost.Basis.NOT_REAL, phantom.cost?.basis)
            assertTrue(phantom.cost!!.mah > 0)
            assertTrue(anomalies.none { it.kind == AnomalyKind.STUCK_AUDIO_WAKELOCK && it.uid == AppUid(10316) })
        }
    }

    @Test
    fun `power dump`() {
        val p = Fixtures.Real.pixelApi37Power
        assertEquals("Dozing", p.wakefulness)
        assertEquals(true, p.isPowered)
        assertEquals(35, p.batteryLevel)
        assertEquals(false, p.deviceIdleMode)
    }
}
