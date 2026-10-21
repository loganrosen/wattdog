package io.github.loganrosen.wattdog.batterystats.analysis

import io.github.loganrosen.wattdog.batterystats.Fixtures
import io.github.loganrosen.wattdog.batterystats.model.AppUid
import kotlin.test.Test
import kotlin.test.assertEquals
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
    fun `idle drain of 3 percent an hour is called out`() {
        assertEquals(Diagnosis.Level.POOR, d.verdict.level)
        assertEquals(3.0, d.idle!!.percentPerHour, 0.05)
        assertTrue("18% an hour" in d.verdict.explanation, d.verdict.explanation)
    }

    @Test
    fun `breakdowns account for all of the measured drain`() {
        assertEquals(listOf(Diagnosis.Area.PROCESSING, Diagnosis.Area.MOBILE), d.idleBreakdown.take(2).map { it.area })
        assertEquals(1.0, d.idleBreakdown.sumOf { it.fraction }, 0.001)
        assertEquals(1.0, d.inUseBreakdown.sumOf { it.fraction }, 0.001)
    }

    @Test
    fun `causes cover heavy use as well as idle drain, even when every finding is minor`() {
        assertEquals(
            listOf(
                "Using the phone drained it quickly",
                "Background work used power while idle",
                "Mobile data kept the modem busy while idle",
            ),
            d.causes.map { it.title },
            d.causes.joinToString("\n") { it.title + ": " + it.summary },
        )
        assertEquals("Using the phone drained it quickly", d.causes[0].title)
        assertTrue(d.causes.none { c -> c.findings.any { it.kind == AnomalyKind.PHANTOM_WAKELOCK } })
    }

    @Test
    fun `screen-on cause admits how much Android couldn't trace`() {
        val use = d.causes.first()
        assertTrue("couldn't trace" in use.summary, use.summary)
        assertEquals(DrainCost.Basis.MEASURED, use.cost?.basis)
    }

    @Test
    fun `idle processing is split between Android and apps across every UID, not just the top few`() {
        // 183 of about 400 mAh of screen-off cpu/wakelock/GPU is charged to platform UIDs.
        val processing = d.causes[1]
        assertTrue("Android's own services were about 4" in processing.summary, processing.summary)
        assertTrue("Wi-Fi" in processing.summary, processing.summary)
        assertEquals(SuggestedAction.ShowWakelocks(wakeupReasons = true), processing.details)
    }

    @Test
    fun `idle mobile cost reflects how much Android tied to data, and 5G is only offered as an experiment`() {
        // Android attributed 285 of 412 mAh of screen-off mobile radio power to UIDs.
        val mobile = d.causes[2]
        assertEquals("Mobile data kept the modem busy while idle", mobile.title)
        assertTrue("about 69%" in mobile.summary, mobile.summary)
        assertTrue("on 5G 91%" in mobile.summary, mobile.summary)
        assertEquals(SuggestedAction.OpenMobileNetworkSettings, mobile.action)
        assertEquals("Try LTE for a day", mobile.actionLabel)
    }

    @Test
    fun `top apps leave out Android's own UIDs`() {
        assertTrue(d.topApps.all { it.uid.isApplication })
        assertEquals(AppUid.parse("u0a290"), d.topApps.first().uid)
    }
}
