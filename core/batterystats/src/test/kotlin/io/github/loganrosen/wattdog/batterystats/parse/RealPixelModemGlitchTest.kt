package io.github.loganrosen.wattdog.batterystats.parse

import io.github.loganrosen.wattdog.batterystats.Fixtures
import io.github.loganrosen.wattdog.batterystats.analysis.AnomalyDetector
import io.github.loganrosen.wattdog.batterystats.analysis.AnomalyKind
import io.github.loganrosen.wattdog.batterystats.analysis.AnomalyThresholds
import io.github.loganrosen.wattdog.batterystats.analysis.DrainCost
import io.github.loganrosen.wattdog.batterystats.analysis.DrainCoverage
import io.github.loganrosen.wattdog.batterystats.analysis.RadioAttribution
import io.github.loganrosen.wattdog.batterystats.analysis.Severity
import io.github.loganrosen.wattdog.batterystats.analysis.SuggestedAction
import io.github.loganrosen.wattdog.batterystats.model.AppUid
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes

/** Pixel 9 Pro XL, API 37, ~12.5 h on battery, where the modem reported 12 h of Rx and a 2,500 mAh drain. */
class RealPixelModemGlitchTest {
    private val r = Fixtures.Real.pixelApi37ModemGlitch
    private val anomalies = AnomalyDetector().detect(r)

    @Test
    fun `cellular uses the power model, not the broken modem counters`() {
        val radio = RadioAttribution.from(r)
        assertEquals(2500.0, radio.cellularControllerMah)
        assertTrue(radio.cellularCountersBroken)
        assertEquals(545.0, radio.cellularDrainMah)
        assertEquals(390.0, radio.cellularAppsMah)
        assertEquals(radio.cellularDrainMah, radio.cellularAppsMah + radio.cellularStandbyMah, 0.01)
        assertEquals(162.0, radio.wifiDrainMah)
    }

    @Test
    fun `modest modem cost on 125 MB is not flagged`() {
        assertTrue(anomalies.none { it.kind == AnomalyKind.CELLULAR_STANDBY }, anomalies.map { it.title }.toString())
    }

    @Test
    fun `coverage shows how much drain the components miss`() {
        val c = assertNotNull(DrainCoverage.from(r))
        assertEquals(3498, c.measuredMah)
        assertEquals(2231.0, c.modeledMah, 1.0)
        assertEquals(0.36, c.unexplainedShare!!, 0.01)
        assertEquals(1111.0, c.screenOff!!.modeledMah, 1.0)
        assertTrue(c.screenOn!!.unexplainedMah > c.screenOff!!.unexplainedMah)
    }

    @Test
    fun `AOD finding costs the display estimate, not all drain while dozing`() {
        val aod = anomalies.single { it.kind == AnomalyKind.AMBIENT_DISPLAY }
        assertEquals(DrainCost.Basis.ESTIMATED, aod.cost?.basis)
        assertEquals(79.0, aod.cost!!.mah)
        assertTrue("715 mAh" in aod.detail, aod.detail)
    }

    @Test
    fun `bluetooth finding points at the background app scanner, not Android`() {
        val philips = AppUid(10665)
        with(r.uids.getValue(philips)) {
            assertEquals(4.hours + 10.minutes, bluetoothScanBackground.inWholeMinutes.minutes)
        }
        assertEquals(2, r.uids.getValue(AppUid(10694)).bluetoothScanUnoptimized.inWholeMinutes)

        val bt = anomalies.single { it.kind == AnomalyKind.BLUETOOTH_SCAN }
        assertEquals(philips, bt.uid)
        assertNull(bt.cost)
        assertTrue("Android itself kept a filtered scan" in bt.detail, bt.detail)
        assertEquals(SuggestedAction.OpenAppInfo(philips), bt.actions.first())
    }

    @Test
    fun `known platform alarms are explained instead of blamed`() {
        val alarms = anomalies.filter { it.kind == AnomalyKind.FREQUENT_ALARM }
        assertEquals(4, alarms.size, alarms.map { it.subject }.toString())
        assertTrue(alarms.all { it.severity == Severity.INFO })
        val dpd = alarms.single { it.subject!!.contains("ACTION_DPD") }
        assertTrue("Wi-Fi calling" in dpd.detail)
        assertTrue(dpd.actions.none { it is SuggestedAction.OpenAppBatteryUsage })
    }

    @Test
    fun `system jobs are not treated as a restrictable app`() {
        val jobs = anomalies.single { it.kind == AnomalyKind.HEAVY_JOBS && it.uid == AppUid(1000) }
        assertNull(jobs.cost)
        assertTrue(jobs.actions.none { it is SuggestedAction.OpenAppBatteryUsage })
        assertTrue("can't be restricted" in jobs.advice)
    }

    @Test
    fun `CPU awake finding names the Wi-Fi chip, not the stuck X wakelock`() {
        // This capture sits at 29.8% awake, just under the default threshold.
        val awake = AnomalyDetector(AnomalyThresholds(screenOffAwakeRatio = 0.25)).detect(r)
            .single { it.kind == AnomalyKind.SCREEN_OFF_AWAKE }
        assertTrue("What woke it up: Wi-Fi" in awake.detail, awake.detail)
        assertTrue("not counting stuck timers" in awake.detail, awake.detail)
        assertTrue(awake.advice.startsWith("The Wi-Fi chip"), awake.advice)
    }
}
