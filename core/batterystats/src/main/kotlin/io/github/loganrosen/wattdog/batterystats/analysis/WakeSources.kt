package io.github.loganrosen.wattdog.batterystats.analysis

import io.github.loganrosen.wattdog.batterystats.model.KernelWakelock
import kotlin.time.Duration

/** Hardware or kernel source behind a wakeup reason or kernel wakelock name. */
enum class WakeSource(val label: String, val explanation: String) {
    WIFI(
        "Wi-Fi",
        "The Wi-Fi chip woke the phone for network traffic: push messages, app syncs, or broadcast and " +
            "discovery traffic from other devices on the network (casting, smart home).",
    ),
    MODEM(
        "Cellular modem",
        "The modem woke the phone: incoming data, network paging, signal changes, or Wi-Fi calling/VoLTE " +
            "keeping its connection alive.",
    ),
    GPS("GPS", "The GPS chip woke the phone to deliver location fixes. Check which apps use location."),
    ALARM(
        "Timers & alarms",
        "Scheduled alarms and timers woke the phone, or kept it from going back to sleep. Frequent alarms " +
            "add up even when each one is short.",
    ),
    SENSOR_HUB(
        "Sensor hub",
        "The always-on sensor processor woke the phone: always-on display, lift to wake, step counting, " +
            "Now Playing, or hotword detection.",
    ),
    BLUETOOTH("Bluetooth", "The Bluetooth chip woke the phone for a connected device or a scan result."),
    POWER("Power & charging", "The power management chip woke the phone (battery, charger, or button events)."),
    APPS(
        "App & system wakelocks",
        "Android held the phone awake for apps or system services. See the partial wakelocks list.",
    ),
    OTHER("Other", "Kernel driver activity that Wattdog doesn't recognise."),
    ;

    companion object {
        // Ordered: the first matching pattern wins, so specific names come before broad ones.
        private val patterns = listOf(
            WIFI to Regex("dhdpcie|dhd_|wlan|wcnss|wifi|bcmdhd|cnss", RegexOption.IGNORE_CASE),
            MODEM to Regex(
                "cp2ap|s5100|modem|rmnet|cpif|mif_|ipa_|qmi|smp2p|ims",
                RegexOption.IGNORE_CASE,
            ),
            GPS to Regex("gnss|gps", RegexOption.IGNORE_CASE),
            SENSOR_HUB to Regex("aoc|slpi|ssc_|chre|sensor", RegexOption.IGNORE_CASE),
            BLUETOOTH to Regex("bluetooth|\\bbt_|hci|btpower", RegexOption.IGNORE_CASE),
            ALARM to Regex("rtc|alarm|timerfd", RegexOption.IGNORE_CASE),
            POWER to Regex("s2mpg|pmic|charger|battery|usb|gpio_keys|power_key", RegexOption.IGNORE_CASE),
            APPS to Regex("PowerManagerService|userspace-abort|system_server", RegexOption.IGNORE_CASE),
        )

        fun of(name: String): WakeSource = patterns.firstOrNull { (_, re) -> re.containsMatchIn(name) }?.first ?: OTHER

        /** True for a suspend attempt that was cancelled rather than a wakeup from sleep. */
        fun isAbort(name: String): Boolean = name.startsWith("Abort:")
    }
}

data class WakeSourceTotal(val source: WakeSource, val wakeups: Int, val aborts: Int, val awake: Duration)

/** Groups kernel wakeup reasons by [WakeSource], largest awake time first. */
fun wakeSourceTotals(reasons: List<KernelWakelock>): List<WakeSourceTotal> = reasons
    .groupBy { WakeSource.of(it.name) }
    .map { (source, items) ->
        val (aborts, wakes) = items.partition { WakeSource.isAbort(it.name) }
        WakeSourceTotal(
            source = source,
            wakeups = wakes.sumOf { it.count },
            aborts = aborts.sumOf { it.count },
            awake = items.fold(Duration.ZERO) { acc, it -> acc + it.total },
        )
    }
    .sortedByDescending { it.awake }
