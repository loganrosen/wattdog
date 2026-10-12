package io.github.loganrosen.wattdog.batterystats.parse

import io.github.loganrosen.wattdog.batterystats.Fixtures
import io.github.loganrosen.wattdog.batterystats.model.AppUid
import io.github.loganrosen.wattdog.batterystats.model.WakelockKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

class BatteryStatsParserTest {
    private val r = Fixtures.report

    @Test
    fun `summary times and discharge`() {
        assertEquals("2026-01-05-22-00-00", r.startClockTime)
        assertEquals(true, r.currentlyOnBattery)
        assertEquals(5000, r.estimatedCapacityMah)
        assertEquals(10.hours, r.onBattery.realtime)
        assertEquals(6.hours, r.onBattery.uptime)
        assertEquals(8.hours, r.screenOff.realtime)
        assertEquals(5.hours, r.screenOff.uptime)
        assertEquals(1.hours, r.screenDoze)
        assertEquals(2.hours, r.screenOn)
        assertEquals(40, r.screenOnCount)
        with(r.discharge) {
            assertEquals(1500, totalMah)
            assertEquals(600, screenOnMah)
            assertEquals(900, screenOffMah)
            assertEquals(120, screenDozeMah)
            assertEquals(30, lightDozeMah)
            assertEquals(200, deepDozeMah)
        }
        assertEquals(30, r.dischargePercent?.lowerBound)
        assertEquals(3, r.dischargePercent?.screenDoze)
    }

    @Test
    fun `doze and wakelock totals`() {
        assertEquals(1.hours, r.doze.deepIdling)
        assertEquals(4, r.doze.deepIdlingCount)
        assertEquals(1.hours, r.doze.lightIdling)
        assertEquals(12, r.doze.lightIdlingCount)
        assertEquals(30.minutes, r.doze.longestDeepIdle)
        assertEquals(5.hours + 10.minutes, r.totalPartialWakelock)
        assertEquals(5.minutes, r.totalFullWakelock)
        assertEquals(10.minutes, r.wifiMulticastWakelock)
        assertEquals(2.hours, r.bluetoothScan)
    }

    @Test
    fun `connectivity sections`() {
        with(r.cellular) {
            assertEquals(3.hours, kernelActive)
            assertEquals(50.minutes, rx)
            assertEquals(6.minutes, tx)
            assertEquals(250.0, drainMah)
            assertEquals(12L * 1024 * 1024, totalBytes)
            assertEquals(9000, rxPackets)
            assertEquals(mapOf("oos" to 1.hours, "lte" to 6.hours, "nr" to 3.hours), radioAccessTech)
            assertEquals(3.hours, signalStrength["very poor"])
            assertEquals(1.hours, signalStrength["great"])
            assertEquals(5, signalStrength.size)
        }
        with(r.wifi) {
            assertEquals(80.0, drainMah)
            assertEquals(20.minutes, scan)
            assertEquals(550L * 1024 * 1024, totalBytes)
        }
        assertEquals(5.0, r.gpsDrainMah)
    }

    @Test
    fun `global power components`() {
        assertEquals(listOf("screen", "cpu", "audio", "mobile_radio", "wifi", "wakelock", "ambient_display"), r.globalPower.map { it.name })
        val radio = assertNotNull(r.globalComponent("mobile_radio"))
        assertEquals(260.0, radio.totalMah)
        assertEquals(40.0, radio.appsMah)
        assertEquals(220.0, radio.unattributedMah)
        assertEquals(5.minutes, r.globalComponent("audio")?.duration)
        assertEquals(listOf("cpu", "mobile_radio", "ambient_display"), r.globalPowerScreenOff.map { it.name })
    }

    @Test
    fun `uid power rows`() {
        assertEquals(listOf("u0a250", "1000", "u0a300", "u0a301"), r.uidPower.map { it.uid.toString() })
        val podcast = r.uidPower.first()
        assertEquals(320.0, podcast.totalMah)
        assertEquals(300.0, podcast.backgroundMah)
        assertEquals(10.0, podcast.foregroundServiceMah)
        // Only the unconditional line, and only top-level components.
        assertEquals(mapOf("cpu" to 250.0, "wakelock" to 55.0, "mobile_radio" to 15.0), podcast.components)
        assertEquals(10.0, r.uidPower.first { it.uid == AppUid(10300) }.cachedMah)
    }

    @Test
    fun `partial wakelocks`() {
        assertEquals(4, r.partialWakelocks.size)
        val audio = r.partialWakelocks.first()
        assertEquals(AppUid(10250), audio.uid)
        assertEquals("AudioMix", audio.tag)
        assertEquals(WakelockKind.AUDIO, audio.kind)
        assertEquals(4.hours + 50.minutes, audio.total)
        assertEquals(17_400_000.milliseconds, audio.runningFor)
        assertTrue(audio.isHeld)
        val job = r.partialWakelocks.first { it.tag.startsWith("*job*") }
        assertEquals(WakelockKind.JOB, job.kind)
        assertEquals(240, job.count)
        assertNull(job.runningFor)
        assertEquals(WakelockKind.ALARM, r.partialWakelocks.first { it.tag == "*alarm*" }.kind)
    }

    @Test
    fun `ignores content outside the since-charge section`() {
        assertTrue(r.partialWakelocks.none { "ShouldNotParse" in it.tag })
        assertTrue(r.uids.keys.none { it == AppUid(10998) || it == AppUid(10999) })
    }

    @Test
    fun `kernel wakelocks and wakeup reasons`() {
        assertEquals("PowerManagerService.WakeLocks", r.kernelWakelocks.first().name)
        assertEquals(900, r.kernelWakelocks.last().count)
        assertEquals("Abort: Pending Wakeup Sources: modem_wake", r.wakeupReasons.first().name)
        assertEquals(200, r.wakeupReasons.last().count)
    }

    @Test
    fun `per-uid details`() {
        assertEquals(setOf("1000", "u0a250", "u0a301", "u10a5"), r.uids.keys.map { it.toString() }.toSet())

        val podcast = r.uids.getValue(AppUid(10250))
        assertEquals(4L * 1024 * 1024, podcast.mobileRxBytes)
        assertEquals(5.minutes, podcast.mobileRadioActive)
        assertEquals(20, podcast.mobileRadioActiveCount)
        assertEquals(listOf("com.example.podcast"), podcast.packages)

        val sync = r.uids.getValue(AppUid(10301))
        assertEquals(1.hours, sync.bluetoothScanActual)
        assertEquals(50.minutes, sync.bluetoothScanBackground)
        assertEquals(2, sync.jobs.size)
        assertEquals(230, sync.jobs[0].backgroundCount)
        assertEquals("UploadWorker", sync.jobs[1].displayName)
        assertEquals("com.example.sync/.SyncJobService", sync.jobs[0].displayName)
        // Zero-count alarms are dropped.
        assertEquals(1, sync.wakeupAlarms.size)
        assertEquals("com.example.sync", sync.wakeupAlarms[0].packageName)
        assertEquals(200, sync.wakeupAlarms[0].count)

        val system = r.uids.getValue(AppUid(1000))
        assertEquals(-(2.seconds + 308.milliseconds), system.jobs.single().total)

        assertEquals(1024L, r.uids.getValue(AppUid(1010005)).wifiRxBytes)
    }

    @Test
    fun `missing header is an error`() {
        assertFailsWith<BatteryStatsParseException> { BatteryStatsParser.parse("Permission Denial: can't dump BatteryStats") }
    }
}
