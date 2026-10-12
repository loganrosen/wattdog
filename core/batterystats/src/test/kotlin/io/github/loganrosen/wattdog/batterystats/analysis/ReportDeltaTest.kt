package io.github.loganrosen.wattdog.batterystats.analysis

import io.github.loganrosen.wattdog.batterystats.Fixtures
import io.github.loganrosen.wattdog.batterystats.model.AppUid
import io.github.loganrosen.wattdog.batterystats.model.AwakeTime
import io.github.loganrosen.wattdog.batterystats.model.BatteryStatsReport
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes

class ReportDeltaTest {
    private val later = Fixtures.report

    /** The same session two hours earlier, before the audio leak and the sync storm started. */
    private val earlier: BatteryStatsReport = later.copy(
        onBattery = AwakeTime(8.hours, 4.hours),
        screenOff = AwakeTime(6.hours, 3.hours),
        discharge = later.discharge.copy(totalMah = 1000, screenOffMah = 500),
        partialWakelocks = later.partialWakelocks.map {
            if (it.tag == "AudioMix") it.copy(total = 2.hours + 50.minutes, count = 1) else it
        },
        uidPower = later.uidPower.filter { it.uid != AppUid(10301) },
        globalPower = later.globalPower.map { if (it.name == "mobile_radio") it.copy(totalMah = 200.0) else it },
        uids = later.uids.mapValues { (uid, d) ->
            if (uid == AppUid(10301)) d.copy(jobs = d.jobs.map { it.copy(count = it.count - 10) }) else d
        },
    )

    @Test
    fun `subtracts cumulative counters and drops unchanged entries`() {
        val delta = assertIs<DeltaResult.Ok>(ReportDelta.compute(earlier, later)).delta
        assertEquals(AwakeTime(2.hours, 2.hours), delta.onBattery)
        assertEquals(AwakeTime(2.hours, 2.hours), delta.screenOff)
        assertEquals(500, delta.discharge.totalMah)
        assertEquals(400, delta.discharge.screenOffMah)

        val audio = delta.partialWakelocks.single()
        assertEquals("AudioMix", audio.tag)
        assertEquals(2.hours, audio.total)
        assertEquals(1, audio.count)

        assertEquals(listOf(AppUid(10301)), delta.uidPower.map { it.uid })
        assertEquals(listOf("mobile_radio"), delta.globalPower.map { it.name })
        assertEquals(60.0, delta.globalPower.single().totalMah)

        assertEquals(setOf(AppUid(10301)), delta.uids.keys)
        assertEquals(listOf(10, 10), delta.uids.getValue(AppUid(10301)).jobs.map { it.count })
    }

    @Test
    fun `identical snapshots produce an empty delta`() {
        val delta = assertIs<DeltaResult.Ok>(ReportDelta.compute(later, later)).delta
        assertTrue(delta.partialWakelocks.isEmpty())
        assertTrue(delta.uids.isEmpty())
        assertTrue(delta.uidPower.isEmpty())
    }

    @Test
    fun `detects a stats reset`() {
        assertIs<DeltaResult.StatsReset>(ReportDelta.compute(later.copy(startClockTime = "2026-01-04-08-00-00"), later))
        assertIs<DeltaResult.StatsReset>(ReportDelta.compute(later, earlier.copy(startClockTime = later.startClockTime)))
    }
}
