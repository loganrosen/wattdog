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
    fun `causes cover heavy use as well as idle drain, even when every finding is minor`() {
        assertEquals(listOf(Diagnosis.Area.PROCESSING, Diagnosis.Area.MOBILE), d.idleBreakdown.take(2).map { it.area })
        assertEquals(1.0, d.idleBreakdown.sumOf { it.fraction }, 0.06)
        assertEquals(1.0, d.inUseBreakdown.sumOf { it.fraction }, 0.06)
        assertEquals(
            listOf("Using the phone drained it quickly", "Your phone kept waking up while idle", "Staying on 5G cost power while idle"),
            d.causes.map { it.title },
            d.causes.joinToString("\n") { it.summary },
        )
        assertTrue(d.causes.none { c -> c.findings.any { it.kind == AnomalyKind.PHANTOM_WAKELOCK } })
    }

    @Test
    fun `screen-on cause admits how much Android couldn't trace`() {
        val use = d.causes.first()
        assertTrue("couldn't trace" in use.summary, use.summary)
        assertEquals(DrainCost.Basis.MEASURED, use.cost?.basis)
    }

    @Test
    fun `idle wakeups are pinned on Android, not an app, and link to the wakeup reasons`() {
        val wakeups = d.causes[1]
        assertTrue("Android's own services" in wakeups.summary, wakeups.summary)
        assertTrue("Wi-Fi" in wakeups.summary, wakeups.summary)
        assertEquals(null, wakeups.action)
        assertEquals(SuggestedAction.ShowWakelocks(wakeupReasons = true), wakeups.details)
    }

    @Test
    fun `mobile drain without much app data is not blamed on syncing apps`() {
        val mobile = d.causes[2]
        assertTrue("wasn't app data" in mobile.summary, mobile.summary)
        assertTrue("syncing" !in mobile.summary && "uid 10249" !in mobile.summary, mobile.summary)
        assertEquals(SuggestedAction.OpenMobileNetworkSettings, mobile.action)
        assertEquals("Try LTE for a day", mobile.actionLabel)
    }

    @Test
    fun `top apps leave out Android's own UIDs`() {
        assertTrue(d.topApps.all { it.uid.isApplication })
        assertEquals(AppUid.parse("u0a290"), d.topApps.first().uid)
    }
}
