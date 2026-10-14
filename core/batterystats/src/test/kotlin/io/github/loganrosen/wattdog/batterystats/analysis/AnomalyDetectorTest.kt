package io.github.loganrosen.wattdog.batterystats.analysis

import io.github.loganrosen.wattdog.batterystats.Fixtures
import io.github.loganrosen.wattdog.batterystats.model.AppUid
import io.github.loganrosen.wattdog.batterystats.model.PartialWakelock
import io.github.loganrosen.wattdog.batterystats.model.WakelockKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes

class AnomalyDetectorTest {
    private val anomalies = AnomalyDetector().detect(Fixtures.report, Fixtures.power)
    private fun of(kind: AnomalyKind) = anomalies.filter { it.kind == kind }

    @Test
    fun `flags the stuck AudioMix wakelock first`() {
        val stuck = anomalies.first()
        assertEquals(AnomalyKind.STUCK_AUDIO_WAKELOCK, stuck.kind)
        assertEquals(Severity.CRITICAL, stuck.severity)
        assertEquals(AppUid(10250), stuck.uid)
        assertTrue(stuck.activeNow)
    }

    @Test
    fun `audio wakelock that matches playback time is not stuck`() {
        val report = Fixtures.report.copy(
            partialWakelocks = listOf(PartialWakelock(AppUid(10250), "AudioMix", 40.minutes, 3)),
            globalPower = Fixtures.report.globalPower.map {
                if (it.name == "audio") it.copy(duration = 35.minutes) else it
            },
        )
        assertTrue(AnomalyDetector().detect(report).none { it.kind == AnomalyKind.STUCK_AUDIO_WAKELOCK })
    }

    @Test
    fun `long non-job wakelock is flagged and marked active from dumpsys power`() {
        val long = of(AnomalyKind.LONG_WAKELOCK).single()
        assertEquals(AppUid(10301), long.uid)
        assertEquals(Severity.WARNING, long.severity)
        assertTrue(long.activeNow)
    }

    @Test
    fun `jobs and alarms`() {
        assertEquals(AppUid(10301), of(AnomalyKind.HEAVY_JOBS).single().uid)
        val alarm = of(AnomalyKind.FREQUENT_ALARM).single()
        assertTrue("com.example.sync.POLL" in alarm.detail)
    }

    @Test
    fun `cellular standby with almost no data`() {
        assertEquals(Severity.CRITICAL, of(AnomalyKind.CELLULAR_STANDBY).single().severity)
    }

    @Test
    fun `stuck wakelock is charged its uid's wakelock power`() {
        val cost = of(AnomalyKind.STUCK_AUDIO_WAKELOCK).single().cost!!
        assertEquals(DrainCost.Basis.ESTIMATED, cost.basis)
        assertEquals(55.0, cost.mah, 0.01)
        assertEquals("≈1.1% of battery (55 mAh)", cost.summary())
    }

    @Test
    fun `cellular standby and screen-off findings carry a cost`() {
        assertTrue(of(AnomalyKind.CELLULAR_STANDBY).single().cost!!.mah > 0)
        assertTrue(of(AnomalyKind.FREQUENT_ALARM).single().cost == null)
    }

    @Test
    fun `cost summary wording`() {
        assertEquals("At most 0.5% of battery (25 mAh)", DrainCost(25.0, DrainCost.Basis.UPPER_BOUND, 0.005, null).summary())
        assertEquals("12 mAh", DrainCost(12.0, DrainCost.Basis.MEASURED, null, null).summary())
        assertEquals("≈<0.1% of battery (2.0 mAh)", DrainCost(2.0, DrainCost.Basis.ESTIMATED, 0.0004, null).summary())
    }

    @Test
    fun `screen-off health`() {
        assertEquals(Severity.CRITICAL, of(AnomalyKind.SCREEN_OFF_AWAKE).single().severity)
        assertEquals(Severity.WARNING, of(AnomalyKind.HIGH_SCREEN_OFF_DRAIN).single().severity)
        assertEquals(1, of(AnomalyKind.POOR_DEEP_DOZE).size)
    }

    @Test
    fun `below-threshold signals stay quiet`() {
        assertFalse(anomalies.any { it.kind == AnomalyKind.AMBIENT_DISPLAY })
        assertFalse(anomalies.any { it.kind == AnomalyKind.BLUETOOTH_SCAN })
        assertFalse(anomalies.any { it.kind == AnomalyKind.WIFI_MULTICAST })
    }

    @Test
    fun `every anomaly has advice and a primary action`() {
        anomalies.forEach {
            assertTrue(it.advice.isNotBlank(), "${it.kind} has no advice")
            assertTrue(it.actions.isNotEmpty(), "${it.kind} has no actions")
        }
    }

    @Test
    fun `actions point at the offender`() {
        val stuck = of(AnomalyKind.STUCK_AUDIO_WAKELOCK).single()
        assertEquals(SuggestedAction.ShowWakelocks(AppUid(10250), "AudioMix", WakelockKind.AUDIO), stuck.actions.first())
        assertTrue(SuggestedAction.OpenAppInfo(AppUid(10250)) in stuck.actions)

        assertTrue(SuggestedAction.OpenAppBatteryUsage(AppUid(10301)) in of(AnomalyKind.HEAVY_JOBS).single().actions)
        assertEquals(SuggestedAction.OpenMobileNetworkSettings, of(AnomalyKind.CELLULAR_STANDBY).single().actions.first())
    }

    @Test
    fun `system-held audio wakelock advice does not blame an app`() {
        val report = Fixtures.report.copy(
            partialWakelocks = listOf(PartialWakelock(AppUid(1041), "AudioMix", 3.hours, 3)),
        )
        val stuck = AnomalyDetector().detect(report).single { it.kind == AnomalyKind.STUCK_AUDIO_WAKELOCK }
        assertTrue("system audio service" in stuck.advice)
    }

    @Test
    fun `held wakelock that dumpsys power doesn't have is a phantom`() {
        val report = Fixtures.report.copy(
            partialWakelocks = listOf(PartialWakelock(AppUid(10316), "AudioMix", 1.hours, 0, runningFor = 1.hours)),
        )
        val anomalies = AnomalyDetector().detect(report, Fixtures.power.copy(wakeLocks = emptyList()))
        val phantom = anomalies.single { it.kind == AnomalyKind.PHANTOM_WAKELOCK }
        assertEquals(Severity.INFO, phantom.severity)
        assertFalse(phantom.activeNow)
        assertTrue(anomalies.none { it.kind == AnomalyKind.STUCK_AUDIO_WAKELOCK })
    }

    @Test
    fun `wakelock longer than screen-off uptime is a phantom even without a power dump`() {
        val report = Fixtures.report.copy(
            partialWakelocks = listOf(PartialWakelock(AppUid(10301), "SomeLock", 6.hours, 0, runningFor = 6.hours)),
        )
        val anomalies = AnomalyDetector().detect(report)
        assertEquals(AppUid(10301), anomalies.single { it.kind == AnomalyKind.PHANTOM_WAKELOCK }.uid)
        assertTrue(anomalies.none { it.kind == AnomalyKind.LONG_WAKELOCK })
    }

    @Test
    fun `lock held since before a delta window is not a phantom`() {
        val wl = PartialWakelock(AppUid(10250), "AudioMix", 30.minutes, 0, runningFor = 20.hours)
        val report = Fixtures.report.copy(partialWakelocks = listOf(wl))
        assertFalse(PhantomWakelocks(report, Fixtures.power).isPhantom(wl))
    }

    @Test
    fun `sorted by severity`() {
        assertEquals(anomalies.sortedByDescending { it.severity }.map { it.severity }, anomalies.map { it.severity })
    }
}
