package io.github.loganrosen.wattdog.batterystats.analysis

import io.github.loganrosen.wattdog.batterystats.Fixtures
import io.github.loganrosen.wattdog.batterystats.model.AppUid
import io.github.loganrosen.wattdog.batterystats.model.PartialWakelock
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import io.github.loganrosen.wattdog.batterystats.model.WakelockKind
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
    fun `sorted by severity`() {
        assertEquals(anomalies.sortedByDescending { it.severity }.map { it.severity }, anomalies.map { it.severity })
    }
}
