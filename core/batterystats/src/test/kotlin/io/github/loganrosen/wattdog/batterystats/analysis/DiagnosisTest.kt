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
    fun `causes come from where idle power went, even when every finding is minor`() {
        assertEquals(listOf(Diagnosis.Area.PROCESSING, Diagnosis.Area.MOBILE), d.idleBreakdown.take(2).map { it.area })
        assertEquals(1.0, d.idleBreakdown.sumOf { it.fraction }, 0.06)
        assertEquals(
            listOf("Your phone kept waking up while idle", "Mobile network used a lot while idle"),
            d.causes.map { it.title },
        )
        val wakeups = d.causes.first()
        assertTrue("Android itself" in wakeups.summary, wakeups.summary)
        assertTrue("Wi-Fi" in wakeups.summary, wakeups.summary)
        assertTrue(d.causes.none { c -> c.findings.any { it.kind == AnomalyKind.PHANTOM_WAKELOCK } })
    }

    @Test
    fun `mobile cause names apps, not the modem's own standby`() {
        val mobile = d.causes.first { it.title.startsWith("Mobile") }
        assertTrue("uid 0" !in mobile.summary && "uid 1001" !in mobile.summary, mobile.summary)
        assertEquals(SuggestedAction.ShowRadios, mobile.action)
    }
}
