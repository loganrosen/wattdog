package io.github.loganrosen.wattdog.batterystats.parse

import io.github.loganrosen.wattdog.batterystats.Fixtures
import io.github.loganrosen.wattdog.batterystats.analysis.AnomalyDetector
import io.github.loganrosen.wattdog.batterystats.analysis.DrainMetrics
import io.github.loganrosen.wattdog.batterystats.analysis.RadioAttribution
import io.github.loganrosen.wattdog.batterystats.model.AppUid
import io.github.loganrosen.wattdog.batterystats.model.WakelockKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * Android 14 (API 34) emulator. The format differs from newer releases: per-UID power components
 * are inline in parentheses, the Global block is indented one level deeper, some rows carry an
 * `[energy consumption]` tag, and there is no coulomb counter so every mAh discharge is 0.
 */
class EmulatorApi34Test {
    private val r = Fixtures.Real.emulatorApi34

    @Test
    fun `summary`() {
        assertEquals("2026-10-12-19-11-16", r.startClockTime)
        assertEquals(true, r.currentlyOnBattery)
        assertEquals(10, r.estimatedCapacityMah)
        assertEquals(2.minutes + 56.seconds + 216.milliseconds, r.onBattery.realtime)
        assertEquals(1.minutes + 50.seconds + 800.milliseconds, r.screenOff.realtime)
        assertEquals(Duration.ZERO, r.screenDoze)
        assertEquals(1, r.screenOnCount)
        assertEquals(0, r.discharge.totalMah)
        assertEquals(16, r.dischargePercent?.lowerBound)
        assertEquals(1.minutes + 44.seconds + 591.milliseconds, r.doze.deepIdling)
        assertEquals(8.seconds + 583.milliseconds, r.totalPartialWakelock)
    }

    @Test
    fun `cellular without drain line`() {
        with(r.cellular) {
            assertNull(drainMah)
            assertEquals(2.minutes + 56.seconds + 216.milliseconds, radioAccessTech["hspa"])
            assertEquals(2.minutes + 26.seconds + 204.milliseconds, signalStrength["great"])
        }
    }

    @Test
    fun `global power block is indented deeper and tagged`() {
        val names = r.globalPower.map { it.name }
        assertTrue(names.containsAll(listOf("screen", "cpu", "mobile_radio", "wakelock", "idle")), "global: $names")
        assertEquals(0.000250, r.globalComponent("mobile_radio")?.totalMah)
        assertEquals(0.0, r.globalComponent("mobile_radio")?.appsMah)
        assertEquals(1.minutes + 5.seconds + 416.milliseconds, r.globalComponent("screen")?.duration)
    }

    @Test
    fun `inline uid power components`() {
        val system = r.uidPower.first { it.uid == AppUid.parse("1000") }
        assertEquals(0.0518, system.totalMah)
        assertEquals(0.0517, system.components["sensors"])
        assertEquals(0.0000872, system.components["cpu"])
        assertTrue("cpu:fg" !in system.components)

        val gms = r.uidPower.first { it.uid == AppUid.parse("u0a127") }
        assertEquals(0.0000152, gms.backgroundMah)
        assertEquals(0.0000169, gms.foregroundServiceMah)
        assertTrue(gms.components.isNotEmpty())
    }

    @Test
    fun `wakelocks and uid details`() {
        val top = r.partialWakelocks.first()
        assertEquals(AppUid.parse("u0a125"), top.uid)
        assertEquals(WakelockKind.JOB, top.kind)
        assertTrue(top.isHeld)
        assertTrue(r.partialWakelocks.any { it.tag == "GCoreFlp" && it.kind == WakelockKind.LOCATION })
        assertTrue(r.kernelWakelocks.any { it.name == "emulator_wake_lock" })

        val dialer = assertNotNull(r.uids[AppUid.parse("u0a125")])
        assertTrue("com.google.android.dialer" in dialer.packages)
        assertEquals(2, dialer.jobs.size)
    }

    @Test
    fun `analysis tolerates a device without a coulomb counter`() {
        val m = DrainMetrics.from(r)
        assertEquals(0, m.dischargeMah)
        RadioAttribution.from(r)
        val anomalies = AnomalyDetector().detect(r, Fixtures.Real.emulatorApi34Power)
        assertTrue(anomalies.all { it.advice.isNotBlank() })
    }

    @Test
    fun `power dump`() {
        val p = Fixtures.Real.emulatorApi34Power
        assertNotNull(p.wakefulness)
        assertNotNull(p.batteryLevel)
    }
}
