package io.github.loganrosen.wattdog.batterystats.analysis

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.time.Duration.Companion.hours

class ExperimentsTest {
    private val hour = 3_600_000L

    private fun interval(startHour: Int, offHours: Int, offPct: Double) = UsageInterval(
        session = "s",
        startMillis = startHour * hour,
        endMillis = (startHour + offHours) * hour,
        screenOn = 0.hours,
        screenOnShare = 0.0,
        screenOff = offHours.hours,
        screenOffShare = offPct / 100,
    )

    private val lte = Experiment("Try LTE", startedAtMillis = 10 * hour, beforePercentPerHour = 2.0, beforeIsUsual = true)

    @Test
    fun `only time after the change counts, and there's no verdict until enough of it`() {
        val p = Experiments.progress(lte, listOf(interval(0, 8, 16.0), interval(10, 4, 4.0)))
        assertEquals(4.hours, p.screenOff)
        assertEquals(1.0, p.afterPercentPerHour!!, 1e-9)
        assertNull(p.outcome)
    }

    @Test
    fun `a clear drop is better`() {
        val p = Experiments.progress(lte, listOf(interval(10, 4, 4.0), interval(20, 4, 5.0)))
        assertEquals(ExperimentProgress.Outcome.BETTER, p.outcome)
        assertEquals(-0.4375, p.change(lte.beforePercentPerHour)!!, 1e-9)
    }

    @Test
    fun `ordinary variation is no change`() {
        val p = Experiments.progress(lte, listOf(interval(10, 8, 14.0)))
        assertEquals(ExperimentProgress.Outcome.NO_CHANGE, p.outcome)
    }

    @Test
    fun `a clear rise is worse`() {
        val p = Experiments.progress(lte, listOf(interval(10, 6, 18.0)))
        assertEquals(ExperimentProgress.Outcome.WORSE, p.outcome)
    }
}
