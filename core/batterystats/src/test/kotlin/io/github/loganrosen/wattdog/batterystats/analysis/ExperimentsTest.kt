package io.github.loganrosen.wattdog.batterystats.analysis

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.hours

class ExperimentsTest {
    private val hour = 3_600_000L
    private val day = 24 * hour

    /** An eight-hour night starting at 23:00 on [day], all screen off. */
    private fun night(day: Int, pctPerHour: Double, wifi: Boolean = true, aod: Boolean = true, wakes: Int = 1) = UsageInterval(
        session = "s",
        startMillis = day * this.day + 23 * hour,
        endMillis = day * this.day + 31 * hour,
        screenOn = 0.hours,
        screenOnShare = 0.0,
        screenOff = 8.hours,
        screenOffShare = pctPerHour * 8 / 100,
        screenDoze = if (aod) 8.hours else 0.hours,
        wifiConnected = if (wifi) 8.hours else 0.hours,
        screenWakes = wakes,
    )

    private val lte = Experiment("Try LTE", startedAtMillis = 10 * day + 12 * hour)
    private val earlier = listOf(night(5, 1.5), night(6, 1.6), night(7, 1.4), night(8, 1.7))

    @Test
    fun `nothing to judge until a quiet stretch after the change`() {
        val busy = UsageInterval("s", 10 * day + 13 * hour, 10 * day + 21 * hour, 2.hours, 0.1, 6.hours, 0.06, wifiConnected = 8.hours)
        val p = Experiments.progress(lte, earlier + busy)
        assertNull(p.outcome)
        assertEquals(4, p.before)
    }

    @Test
    fun `clearly below every similar earlier night is lower`() {
        val p = Experiments.progress(lte, earlier + night(10, 0.9))
        assertEquals(ExperimentProgress.Outcome.LOWER, p.outcome)
        assertEquals(0.9, p.afterPercentPerHour!!, 1e-9)
        assertEquals(1.55, p.earlierPercentPerHour!!, 1e-9)
    }

    @Test
    fun `inside the earlier range is similar even when the median differs`() {
        val spread = listOf(night(5, 1.0), night(6, 2.0), night(7, 2.1))
        assertEquals(ExperimentProgress.Outcome.SIMILAR, Experiments.progress(lte, spread + night(10, 1.2)).outcome)
    }

    @Test
    fun `clearly above every similar earlier night is higher`() {
        assertEquals(ExperimentProgress.Outcome.HIGHER, Experiments.progress(lte, earlier + night(10, 2.4)).outcome)
    }

    @Test
    fun `nights on another network aren't compared`() {
        val p = Experiments.progress(lte, earlier + night(10, 2.4, wifi = false))
        assertEquals(ExperimentProgress.Outcome.NOTHING_TO_COMPARE, p.outcome)
        assertTrue(p.earlier.isEmpty())
    }

    @Test
    fun `an always-on display change isn't matched on the display`() {
        val aod = Experiment("Always-on display", lte.startedAtMillis, matchAod = false)
        val after = night(10, 0.9, aod = false)
        assertEquals(ExperimentProgress.Outcome.NOTHING_TO_COMPARE, Experiments.progress(lte, earlier + after).outcome)
        assertEquals(ExperimentProgress.Outcome.LOWER, Experiments.progress(aod, earlier + after).outcome)
    }

    @Test
    fun `starting from the lock screen settings action stops matching the display`() {
        val e = Experiments.start("AOD", SuggestedAction.OpenLockScreenSettings, 0)
        assertEquals(false, e.matchAod)
        assertEquals(true, Experiments.start("LTE", SuggestedAction.OpenMobileNetworkSettings, 0).matchAod)
    }

    @Test
    fun `a restless night doesn't count`() {
        val p = Experiments.progress(lte, earlier + night(10, 0.9, wakes = 8))
        assertNull(p.outcome)
    }

    @Test
    fun `nights longer ago than two weeks aren't compared`() {
        val late = Experiment("Try LTE", startedAtMillis = 30 * day)
        val p = Experiments.progress(late, earlier + night(30, 0.9))
        assertEquals(ExperimentProgress.Outcome.NOTHING_TO_COMPARE, p.outcome)
        assertEquals(0, p.before)
    }
}
