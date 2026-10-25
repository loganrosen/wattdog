package io.github.loganrosen.wattdog.batterystats.analysis

import io.github.loganrosen.wattdog.batterystats.Fixtures
import io.github.loganrosen.wattdog.batterystats.model.AppUid
import io.github.loganrosen.wattdog.batterystats.model.PowerComponent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DiagnosisTest {
    private val r = Fixtures.Real.pixelApi37ModemGlitch
    private val d = Diagnosis.from(r, AnomalyDetector().detect(r)) { "uid ${it.raw}" }

    @Test
    fun `per-app screen-off power is parsed`() {
        val u = r.uidPower.first { it.uid == AppUid.parse("u0a290") }
        assertEquals(5.53, u.screenOffComponents["cpu"])
        assertTrue("cpu:bg" !in u.screenOffComponents)
        assertTrue("screen" !in u.screenOffComponents)
    }

    @Test
    fun `verdict leads with where the battery went, then judges idle drain`() {
        assertEquals(Diagnosis.Level.POOR, d.verdict.level)
        assertEquals(3.0, d.idle!!.percentPerHour, 0.05)
        assertTrue("It used 78%: 49% over 2h 43m of screen time" in d.verdict.explanation, d.verdict.explanation)
        assertTrue("3.0% an hour" in d.verdict.explanation, d.verdict.explanation)
    }

    @Test
    fun `heavy use is summarised as screen time per charge rather than blamed`() {
        assertEquals(5.6, d.inUse!!.perCharge!!.inWholeMinutes / 60.0, 0.05)
        assertTrue(d.causes.none { "Using the phone" in it.title })
    }

    @Test
    fun `breakdowns account for all of the measured drain`() {
        assertEquals(listOf(Diagnosis.Area.PROCESSING, Diagnosis.Area.MOBILE), d.idleBreakdown.shares.take(2).map { it.area })
        assertEquals(1.0, d.idleBreakdown.shares.sumOf { it.fraction }, 0.001)
        assertEquals(1.0, d.inUseBreakdown.shares.sumOf { it.fraction }, 0.001)
        assertFalse(d.idleBreakdown.overEstimated)
    }

    @Test
    fun `breakdown never exceeds 100 percent when Android's estimates exceed what the battery measured`() {
        val b = Diagnosis.breakdown(
            418,
            listOf(PowerComponent("cpu", 240.0, null), PowerComponent("mobile_radio", 150.0, null), PowerComponent("wifi", 75.0, null)),
        )
        assertTrue(b.overEstimated)
        assertNull(b.unmeasured)
        assertEquals(1.0, b.shares.sumOf { it.fraction }, 0.001)
    }

    @Test
    fun `nothing is recommended without something specific to change`() {
        assertEquals(emptyList(), d.causes.map { it.title })
        assertEquals(
            listOf("Background work used power while idle", "Mobile data kept the modem busy while idle"),
            d.observations.take(2).map { it.title },
        )
        assertTrue(d.observations.none { c -> c.findings.any { it.kind == AnomalyKind.PHANTOM_WAKELOCK } })
    }

    @Test
    fun `idle processing is split between Android and apps across every UID, not just the top few`() {
        // 183 of about 400 mAh of screen-off cpu/wakelock/GPU is charged to platform UIDs.
        val processing = d.observations.first { it.title.startsWith("Background work") }
        assertTrue("Android's own services were about 4" in processing.summary, processing.summary)
        assertTrue("Wi-Fi" in processing.summary, processing.summary)
        assertEquals(SuggestedAction.ShowWakelocks(wakeupReasons = true), processing.details)
    }

    @Test
    fun `5G is not blamed when most of the modem's cost was app data`() {
        // Android attributed 285 of 412 mAh of screen-off mobile radio power to UIDs.
        val mobile = d.observations.first { it.title.startsWith("Mobile") }
        assertTrue("about 69%" in mobile.summary, mobile.summary)
        assertTrue("on 5G 91%" in mobile.summary, mobile.summary)
        assertNull(mobile.action)
    }

    @Test
    fun `LTE is offered as an experiment when the modem idling on 5G was the cost`() {
        val standby = r.copy(
            globalPowerScreenOff = r.globalPowerScreenOff.map {
                if (it.name ==
                    "mobile_radio"
                ) {
                    it.copy(appsMah = it.totalMah * 0.2)
                } else {
                    it
                }
            },
        )
        val mobile = Diagnosis.from(standby, AnomalyDetector().detect(standby)) { "uid ${it.raw}" }.causes
            .first { it.title.startsWith("Mobile") }
        assertEquals(SuggestedAction.OpenMobileNetworkSettings, mobile.action)
        assertEquals("Try LTE for a day", mobile.actionLabel)
    }

    @Test
    fun `top apps leave out Android's own UIDs`() {
        assertTrue(d.topApps.all { it.uid.isApplication })
        assertEquals(AppUid.parse("u0a290"), d.topApps.first().uid)
    }
}
