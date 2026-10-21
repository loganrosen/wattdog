package io.github.loganrosen.wattdog.batterystats.analysis

import io.github.loganrosen.wattdog.batterystats.model.KernelWakelock
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.minutes

class WakeSourcesTest {
    @Test
    fun `classifies Pixel wakeup reasons`() {
        mapOf(
            "508 dhdpcie_host_wake" to WakeSource.WIFI,
            "Abort: Pending Wakeup Sources: wlan_txfl_wake" to WakeSource.WIFI,
            "437 cp2ap_wakeup" to WakeSource.MODEM,
            "Abort: Pending Wakeup Sources: s5100_wake_lock" to WakeSource.MODEM,
            "Abort: Pending Wakeup Sources: gnss_ipc" to WakeSource.GPS,
            "-1 MAILBOX_AOCA322APx100" to WakeSource.SENSOR_HUB,
            "333 rtc-alarm0" to WakeSource.ALARM,
            "Abort: Device alarmtimer.4.auto failed to suspend: error -16" to WakeSource.ALARM,
            "Abort: Pending Wakeup Sources: epolld28:system_server.[timerfd39:system_server]" to WakeSource.ALARM,
            "322 s2mpg14-irq" to WakeSource.POWER,
            "Abort: Pending Wakeup Sources: userspace-abort" to WakeSource.APPS,
            "Abort: Device 0001:01:00.0 failed to suspend: error -16" to WakeSource.OTHER,
        ).forEach { (name, expected) -> assertEquals(expected, WakeSource.of(name), name) }
    }

    @Test
    fun `totals split wakeups from cancelled sleeps`() {
        val totals = wakeSourceTotals(
            listOf(
                KernelWakelock("508 dhdpcie_host_wake", 80.minutes, 2000),
                KernelWakelock("Abort: Pending Wakeup Sources: wlan_rx_wake", 2.minutes, 25),
                KernelWakelock("333 rtc-alarm0", 20.minutes, 800),
            ),
        )
        assertEquals(WakeSource.WIFI, totals.first().source)
        assertEquals(2000, totals.first().wakeups)
        assertEquals(25, totals.first().aborts)
        assertEquals(82.minutes, totals.first().awake)
    }
}
