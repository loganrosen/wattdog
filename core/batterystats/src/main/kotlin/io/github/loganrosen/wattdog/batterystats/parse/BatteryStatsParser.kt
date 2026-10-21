package io.github.loganrosen.wattdog.batterystats.parse

import io.github.loganrosen.wattdog.batterystats.model.AlarmStat
import io.github.loganrosen.wattdog.batterystats.model.AppUid
import io.github.loganrosen.wattdog.batterystats.model.AwakeTime
import io.github.loganrosen.wattdog.batterystats.model.BatteryStatsReport
import io.github.loganrosen.wattdog.batterystats.model.CellularStats
import io.github.loganrosen.wattdog.batterystats.model.Discharge
import io.github.loganrosen.wattdog.batterystats.model.DischargePercent
import io.github.loganrosen.wattdog.batterystats.model.DozeStats
import io.github.loganrosen.wattdog.batterystats.model.JobStat
import io.github.loganrosen.wattdog.batterystats.model.KernelWakelock
import io.github.loganrosen.wattdog.batterystats.model.PartialWakelock
import io.github.loganrosen.wattdog.batterystats.model.PowerComponent
import io.github.loganrosen.wattdog.batterystats.model.UidDetail
import io.github.loganrosen.wattdog.batterystats.model.UidPower
import io.github.loganrosen.wattdog.batterystats.model.WifiStats
import io.github.loganrosen.wattdog.batterystats.parse.Units.DURATION_PATTERN
import kotlin.math.roundToInt
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

class BatteryStatsParseException(message: String) : IllegalArgumentException(message)

/**
 * Parser for the human-readable output of `dumpsys batterystats --charged`.
 *
 * The format is not a stable API, so the parser is deliberately lenient: unknown lines are
 * skipped and every field has a default. It keys off indentation the same way
 * `BatteryStats.dumpLocked` emits it (2 spaces for top-level items, deeper for children).
 */
object BatteryStatsParser {
    private const val STATS_HEADER = "Statistics since last charge:"

    fun parse(text: String): BatteryStatsReport = parse(text.lineSequence())

    fun parse(lines: Sequence<String>): BatteryStatsReport {
        val state = ParseState()
        var inStats = false
        var sawHeader = false
        for (line in lines) {
            if (!inStats) {
                if (line.trimEnd() == STATS_HEADER) {
                    inStats = true
                    sawHeader = true
                }
                continue
            }
            if (line.isBlank()) continue
            val indent = line.indexOfFirst { it != ' ' }
            if (indent == 0) break
            state.accept(indent, line.trim())
        }
        if (!sawHeader) throw BatteryStatsParseException("Missing \"$STATS_HEADER\"; expected `dumpsys batterystats --charged` output")
        return state.build()
    }

    private enum class Section { SUMMARY, DISCHARGE_PCT, CONNECTIVITY, CELLULAR, WIFI, GPS, POWER, UID_DETAIL, IGNORED }

    private enum class CellSub { NONE, TX, RAT, SIGNAL, SKIP }

    private enum class PowerSub { GLOBAL_ALL, GLOBAL_SCREEN_OFF, GLOBAL_OTHER, UID }

    private val partialWakelockRegex =
        Regex("""^Wake lock (\S+) (.+): ($DURATION_PATTERN) \((\d+) times\)(.*)$""")
    private val kernelWakelockRegex = Regex("""^Kernel Wake lock (.+): ($DURATION_PATTERN) \((\d+) times\)""")
    private val wakeupReasonRegex = Regex("""^Wakeup reason (.+): ($DURATION_PATTERN) \((\d+) times\)""")
    private val uidDetailHeader = Regex("""^(u\d+(?:ai|a|i|s)\d+|\d+):$""")
    private val uidPowerHeader = Regex("""^UID (\S+): ([\d.]+)(.*)$""")
    private val uidPowerState = Regex("""\b(fg|bg|fgs|cached): ([\d.]+)""")
    private val powerComponent = Regex("""^([A-Za-z_]+): ([\d.]+)(?: \[[^\]]*])?(?: apps: ([\d.]+))?(?: duration: (.+))?$""")
    private val uidComponent = Regex("""(?:^|\s)([A-Za-z_]+)=([\d.]+)""")
    private val realtimeUptime = Regex("""^(.+?) \(.*?\) realtime, (.+?) \(.*?\) uptime""")
    private val timesX = Regex("""\)\s*(\d+)x""")
    private val network = Regex("""^(\S+) received, (\S+) sent""")
    private val jobRegex =
        Regex("""^Job (.+): ($DURATION_PATTERN) realtime \((\d+) times\)(?:, ($DURATION_PATTERN) background \((\d+) times\))?""")
    private val alarmRegex = Regex("""^Wakeup alarm (.+): (\d+) times""")
    private val ratRegex = Regex("""^(\S+)\s+($DURATION_PATTERN)""")

    private class UidBuilder(val uid: AppUid) {
        var mobileRx = 0L
        var mobileTx = 0L
        var wifiRx = 0L
        var wifiTx = 0L
        var radioActive = Duration.ZERO
        var radioActiveCount = 0
        var btActual = Duration.ZERO
        var btBackground = Duration.ZERO
        var btUnoptimized = Duration.ZERO
        val jobs = mutableListOf<JobStat>()
        val alarms = mutableListOf<AlarmStat>()
        val packages = mutableListOf<String>()
        var currentApk: String? = null

        fun build() = UidDetail(
            uid = uid,
            mobileRxBytes = mobileRx,
            mobileTxBytes = mobileTx,
            wifiRxBytes = wifiRx,
            wifiTxBytes = wifiTx,
            mobileRadioActive = radioActive,
            mobileRadioActiveCount = radioActiveCount,
            bluetoothScanActual = btActual,
            bluetoothScanBackground = btBackground,
            bluetoothScanUnoptimized = btUnoptimized,
            jobs = jobs.toList(),
            wakeupAlarms = alarms.toList(),
            packages = packages.toList(),
        )
    }

    private class ParseState {
        var report = BatteryStatsReport()
        var section = Section.SUMMARY
        var cellSub = CellSub.NONE
        var powerSub = PowerSub.GLOBAL_ALL
        var cellular = CellularStats()
        var wifi = WifiStats()
        val rat = linkedMapOf<String, Duration>()
        val signal = linkedMapOf<String, Duration>()
        var discharge = Discharge()
        var doze = DozeStats()
        val pct = mutableMapOf<String, Int>()
        val globalPower = mutableListOf<PowerComponent>()
        val globalPowerScreenOff = mutableListOf<PowerComponent>()
        val uidPower = mutableListOf<UidPower>()
        var currentUidPower: UidPower? = null
        var uidComponentsSeen = false
        var globalComponentIndent: Int? = null
        val partial = mutableListOf<PartialWakelock>()
        val kernel = mutableListOf<KernelWakelock>()
        val wakeups = mutableListOf<KernelWakelock>()
        val uidDetails = linkedMapOf<AppUid, UidBuilder>()
        var currentUid: UidBuilder? = null

        fun accept(indent: Int, content: String) {
            if (indent <= 2) acceptTopLevel(content) else acceptChild(indent, content)
        }

        private fun acceptTopLevel(content: String) {
            flushUidPower()
            currentUid = null
            when {
                content == "CONNECTIVITY POWER SUMMARY START" -> section = Section.CONNECTIVITY

                content == "CONNECTIVITY POWER SUMMARY END" -> section = Section.SUMMARY

                content == "Cellular Statistics:" -> {
                    section = Section.CELLULAR
                    cellSub = CellSub.NONE
                }

                content == "Wifi Statistics:" -> section = Section.WIFI

                content == "GPS Statistics:" -> section = Section.GPS

                content == "Estimated power use (mAh):" -> {
                    section = Section.POWER
                    powerSub = PowerSub.GLOBAL_ALL
                }

                content.startsWith("Device battery use since last full charge") -> section = Section.DISCHARGE_PCT

                content.startsWith("UID ") && section == Section.POWER -> startUidPower(content)

                content.startsWith("Kernel Wake lock ") -> {
                    section = Section.IGNORED
                    parseKernel(content)
                }

                content.startsWith("Wake lock ") -> {
                    section = Section.IGNORED
                    parsePartial(content)
                }

                content.startsWith("Wakeup reason ") -> {
                    section = Section.IGNORED
                    parseWakeupReason(content)
                }

                uidDetailHeader.matches(content) -> startUidDetail(content.removeSuffix(":"))

                else -> {
                    section = if (content.endsWith(":")) Section.IGNORED else Section.SUMMARY
                    parseSummary(content)
                }
            }
        }

        private fun acceptChild(indent: Int, content: String) {
            when (section) {
                Section.DISCHARGE_PCT -> parseDischargePercent(content)

                Section.CELLULAR -> parseCellular(indent, content)

                Section.WIFI -> if (indent <= 5) parseWifi(content)

                Section.GPS -> if (content.startsWith("GPS Battery Drain:")) {
                    report = report.copy(gpsDrainMah = Units.mah(content.substringAfter(":")))
                }

                Section.POWER -> parsePowerChild(indent, content)

                Section.UID_DETAIL -> parseUidDetail(indent, content)

                Section.SUMMARY, Section.CONNECTIVITY, Section.IGNORED -> Unit
            }
        }

        private fun parseSummary(content: String) {
            val key = content.substringBefore(": ")
            val value = content.substringAfter(": ", "")
            when (key) {
                "System starts" -> {
                    val onBattery = content.substringAfter("currently on battery: ", "").trim()
                    if (onBattery.isNotEmpty()) report = report.copy(currentlyOnBattery = onBattery.toBoolean())
                }

                "Estimated battery capacity" ->
                    report = report.copy(
                        estimatedCapacityMah = value.removeSuffix("mAh").trim().toDoubleOrNull()?.roundToInt(),
                    )

                "Time on battery" -> parseAwake(value)?.let { report = report.copy(onBattery = it) }

                "Time on battery screen off" -> parseAwake(value)?.let { report = report.copy(screenOff = it) }

                "Time on battery screen doze" -> report = report.copy(screenDoze = durationBeforeParen(value))

                "Discharge" -> discharge = discharge.copy(totalMah = mahInt(value))

                "Screen off discharge" -> discharge = discharge.copy(screenOffMah = mahInt(value))

                "Screen doze discharge" -> discharge = discharge.copy(screenDozeMah = mahInt(value))

                "Screen on discharge" -> discharge = discharge.copy(screenOnMah = mahInt(value))

                "Device light doze discharge" -> discharge = discharge.copy(lightDozeMah = mahInt(value))

                "Device deep doze discharge" -> discharge = discharge.copy(deepDozeMah = mahInt(value))

                "Start clock time" -> report = report.copy(startClockTime = value.trim())

                "Screen on" -> report = report.copy(
                    screenOn = durationBeforeParen(value),
                    screenOnCount = timesX.find(value)?.groupValues?.get(1)?.toInt() ?: 0,
                )

                "Device full idling" -> doze = doze.copy(
                    deepIdling = durationBeforeParen(value),
                    deepIdlingCount = timesX.find(value)?.groupValues?.get(1)?.toInt() ?: 0,
                )

                "Device light idling" -> doze = doze.copy(
                    lightIdling = durationBeforeParen(value),
                    lightIdlingCount = timesX.find(value)?.groupValues?.get(1)?.toInt() ?: 0,
                )

                "Idle mode full time" -> {
                    val longest = value.substringAfter("-- longest ", "")
                    if (longest.isNotEmpty()) doze = doze.copy(longestDeepIdle = Units.duration(longest))
                }

                "Total full wakelock time" -> report = report.copy(totalFullWakelock = durationBeforeParen(value))

                "Total partial wakelock time" -> report = report.copy(totalPartialWakelock = durationBeforeParen(value))

                "Total WiFi Multicast wakelock time" ->
                    report = report.copy(wifiMulticastWakelock = durationBeforeParen(value))

                "Bluetooth scan time" -> report = report.copy(bluetoothScan = durationBeforeParen(value))
            }
        }

        private fun parseDischargePercent(content: String) {
            val key = content.substringBefore(":").trim()
            content.substringAfter(":").trim().toIntOrNull()?.let { pct[key] = it }
        }

        private fun parseCellular(indent: Int, content: String) {
            if (indent >= 7) {
                when (cellSub) {
                    CellSub.TX -> cellular = cellular.copy(tx = cellular.tx + durationAfterLastColon(content))

                    CellSub.RAT -> ratRegex.find(content)?.let { m ->
                        rat[m.groupValues[1]] = Units.duration(m.groupValues[2]) ?: Duration.ZERO
                    }

                    CellSub.SIGNAL -> if (content.contains("):")) {
                        signal[content.substringBefore(" (").trim()] = durationAfterLastColon(content)
                    }

                    CellSub.NONE, CellSub.SKIP -> Unit
                }
                return
            }
            cellSub = CellSub.NONE
            val key = content.substringBefore(":")
            val value = content.substringAfter(":", "").trim()
            when (key) {
                "Cellular kernel active time" -> cellular = cellular.copy(kernelActive = durationBeforeParen(value))
                "Cellular Sleep time" -> cellular = cellular.copy(sleep = durationBeforeParen(value))
                "Cellular Idle time" -> cellular = cellular.copy(idle = durationBeforeParen(value))
                "Cellular Rx time" -> cellular = cellular.copy(rx = durationBeforeParen(value))
                "Cellular Tx time" -> cellSub = CellSub.TX
                "Cellular Battery drain" -> cellular = cellular.copy(drainMah = Units.mah(value))
                "Cellular data received" -> cellular = cellular.copy(rxBytes = Units.bytes(value) ?: 0)
                "Cellular data sent" -> cellular = cellular.copy(txBytes = Units.bytes(value) ?: 0)
                "Cellular packets received" -> cellular = cellular.copy(rxPackets = value.toLongOrNull() ?: 0)
                "Cellular packets sent" -> cellular = cellular.copy(txPackets = value.toLongOrNull() ?: 0)
                "Cellular Radio Access Technology" -> cellSub = CellSub.RAT
                else -> cellSub = if (key.startsWith("Cellular Rx signal strength")) CellSub.SIGNAL else CellSub.SKIP
            }
        }

        private fun parseWifi(content: String) {
            val key = content.substringBefore(":")
            val value = content.substringAfter(":", "").trim()
            wifi = when (key) {
                "Wifi kernel active time" -> wifi.copy(kernelActive = durationBeforeParen(value))
                "WiFi Scan time" -> wifi.copy(scan = durationBeforeParen(value))
                "WiFi Rx time" -> wifi.copy(rx = durationBeforeParen(value))
                "WiFi Tx time" -> wifi.copy(tx = durationBeforeParen(value))
                "WiFi Battery drain" -> wifi.copy(drainMah = Units.mah(value))
                "Wifi data received" -> wifi.copy(rxBytes = Units.bytes(value) ?: 0)
                "Wifi data sent" -> wifi.copy(txBytes = Units.bytes(value) ?: 0)
                else -> wifi
            }
        }

        private fun parsePowerChild(indent: Int, content: String) {
            if (content.startsWith("UID ")) {
                // API 35+ prints UID rows at the top level; API 34 nests them under the section header.
                flushUidPower()
                startUidPower(content)
                return
            }
            if (currentUidPower != null) {
                // Only the first, unconditional component line belongs to the UID total; the
                // "(on battery, ...)" lines that follow are per-state breakdowns.
                if (!uidComponentsSeen && !content.startsWith("(")) {
                    currentUidPower = currentUidPower!!.copy(components = parseUidComponents(content))
                    uidComponentsSeen = true
                }
                return
            }
            when {
                content == "Global" -> {
                    powerSub = PowerSub.GLOBAL_ALL
                    globalComponentIndent = null
                }

                content.startsWith("(") ->
                    powerSub =
                        if (content == "(on battery, screen off/doze)") PowerSub.GLOBAL_SCREEN_OFF else PowerSub.GLOBAL_OTHER

                else -> {
                    val m = powerComponent.matchEntire(content) ?: return
                    val component = PowerComponent(
                        name = m.groupValues[1],
                        totalMah = m.groupValues[2].toDouble(),
                        appsMah = m.groupValues[3].toDoubleOrNull(),
                        duration = m.groupValues[4].takeIf { it.isNotBlank() }?.let(Units::duration),
                    )
                    when (powerSub) {
                        PowerSub.GLOBAL_ALL -> {
                            // Components sit level with "Global" on API 35+ and one level deeper on API 34.
                            val expected = globalComponentIndent ?: indent.also { globalComponentIndent = it }
                            if (indent == expected) globalPower += component
                        }

                        PowerSub.GLOBAL_SCREEN_OFF -> globalPowerScreenOff += component

                        else -> Unit
                    }
                }
            }
        }

        private fun parseUidComponents(text: String): Map<String, Double> =
            uidComponent.findAll(text).associate { it.groupValues[1] to it.groupValues[2].toDouble() }

        private fun startUidPower(content: String) {
            val m = uidPowerHeader.matchEntire(content) ?: return
            val uid = AppUid.parse(m.groupValues[1]) ?: return
            val rest = m.groupValues[3]
            val inlineAt = Regex("""(?:^|\s)\(\s+[A-Za-z_]+=""").find(rest)?.range?.first
            val inline = inlineAt?.let { rest.substring(it).trim().removePrefix("(").removeSuffix(")") }.orEmpty()
            val states = uidPowerState.findAll(inlineAt?.let { rest.substring(0, it) } ?: rest).associate {
                it.groupValues[1] to it.groupValues[2].toDouble()
            }
            currentUidPower = UidPower(
                uid = uid,
                totalMah = m.groupValues[2].toDouble(),
                foregroundMah = states["fg"] ?: 0.0,
                backgroundMah = states["bg"] ?: 0.0,
                foregroundServiceMah = states["fgs"] ?: 0.0,
                cachedMah = states["cached"] ?: 0.0,
                components = parseUidComponents(inline),
            )
            // API 34 prints components inline: `UID 1000: 0.05 ( cpu=0.01 (8s) sensors=0.04 )`.
            uidComponentsSeen = inline.isNotBlank()
            section = Section.POWER
        }

        private fun flushUidPower() {
            currentUidPower?.let { uidPower += it }
            currentUidPower = null
        }

        private fun parsePartial(content: String) {
            val m = partialWakelockRegex.matchEntire(content) ?: return
            val uid = AppUid.parse(m.groupValues[1]) ?: return
            val rest = m.groupValues[5]
            partial += PartialWakelock(
                uid = uid,
                tag = m.groupValues[2],
                total = Units.duration(m.groupValues[3]) ?: Duration.ZERO,
                count = m.groupValues[4].toInt(),
                maxMs = Regex("""max=(\d+)""").find(rest)?.groupValues?.get(1)?.toLong(),
                actualMs = Regex("""actual=(\d+)""").find(rest)?.groupValues?.get(1)?.toLong(),
                runningFor = Regex("""\(running for (\d+)ms\)""").find(rest)?.groupValues?.get(1)?.toLong()?.milliseconds,
            )
        }

        private fun parseKernel(content: String) {
            val m = kernelWakelockRegex.find(content) ?: return
            kernel += KernelWakelock(m.groupValues[1], Units.duration(m.groupValues[2]) ?: Duration.ZERO, m.groupValues[3].toInt())
        }

        private fun parseWakeupReason(content: String) {
            val m = wakeupReasonRegex.find(content) ?: return
            wakeups += KernelWakelock(m.groupValues[1], Units.duration(m.groupValues[2]) ?: Duration.ZERO, m.groupValues[3].toInt())
        }

        private fun startUidDetail(uidText: String) {
            val uid = AppUid.parse(uidText) ?: return
            section = Section.UID_DETAIL
            currentUid = uidDetails.getOrPut(uid) { UidBuilder(uid) }
        }

        private fun parseUidDetail(indent: Int, content: String) {
            val b = currentUid ?: return
            if (indent == 4) b.currentApk = null
            when {
                content.startsWith("Mobile network: ") -> network.find(content.removePrefix("Mobile network: "))?.let {
                    b.mobileRx = Units.bytes(it.groupValues[1]) ?: 0
                    b.mobileTx = Units.bytes(it.groupValues[2]) ?: 0
                }

                content.startsWith("Wi-Fi network: ") -> network.find(content.removePrefix("Wi-Fi network: "))?.let {
                    b.wifiRx = Units.bytes(it.groupValues[1]) ?: 0
                    b.wifiTx = Units.bytes(it.groupValues[2]) ?: 0
                }

                content.startsWith("Mobile radio active: ") -> {
                    val value = content.removePrefix("Mobile radio active: ")
                    b.radioActive = durationBeforeParen(value)
                    b.radioActiveCount = timesX.find(value)?.groupValues?.get(1)?.toInt() ?: 0
                }

                content.startsWith("Bluetooth Scan (total actual realtime): ") ->
                    b.btActual = durationAfterFirstColon(content)

                content.startsWith("Bluetooth Scan (background realtime): ") ->
                    b.btBackground = durationAfterFirstColon(content)

                content.startsWith("Unoptimized Bluetooth Scan (realtime): ") ->
                    b.btUnoptimized = durationAfterFirstColon(content)

                content.startsWith("Job ") -> jobRegex.find(content)?.let { m ->
                    b.jobs += JobStat(
                        name = m.groupValues[1],
                        total = Units.duration(m.groupValues[2]) ?: Duration.ZERO,
                        count = m.groupValues[3].toInt(),
                        background = m.groupValues[4].takeIf { it.isNotEmpty() }?.let(Units::duration) ?: Duration.ZERO,
                        backgroundCount = m.groupValues[5].toIntOrNull() ?: 0,
                    )
                }

                indent == 4 && content.startsWith("Apk ") && content.endsWith(":") -> {
                    val pkg = content.removePrefix("Apk ").removeSuffix(":")
                    b.currentApk = pkg
                    b.packages += pkg
                }

                content.startsWith("Wakeup alarm ") -> alarmRegex.find(content)?.let { m ->
                    val count = m.groupValues[2].toInt()
                    if (count > 0) b.alarms += AlarmStat(b.currentApk, m.groupValues[1], count)
                }
            }
        }

        fun build(): BatteryStatsReport {
            flushUidPower()
            val dischargePercent = pct["Amount discharged (lower bound)"]?.let { lower ->
                DischargePercent(
                    lowerBound = lower,
                    upperBound = pct["Amount discharged (upper bound)"] ?: lower,
                    screenOn = pct["Amount discharged while screen on"] ?: 0,
                    screenOff = pct["Amount discharged while screen off"] ?: 0,
                    screenDoze = pct["Amount discharged while screen doze"] ?: 0,
                )
            }
            return report.copy(
                discharge = discharge,
                dischargePercent = dischargePercent,
                doze = doze,
                cellular = cellular.copy(radioAccessTech = rat.toMap(), signalStrength = signal.toMap()),
                wifi = wifi,
                globalPower = globalPower.toList(),
                globalPowerScreenOff = globalPowerScreenOff.toList(),
                uidPower = uidPower.sortedByDescending { it.totalMah },
                partialWakelocks = partial.sortedByDescending { it.total },
                kernelWakelocks = kernel.sortedByDescending { it.total },
                wakeupReasons = wakeups.sortedByDescending { it.total },
                uids = uidDetails.mapValues { it.value.build() },
            )
        }

        private fun parseAwake(value: String): AwakeTime? {
            val m = realtimeUptime.find(value) ?: return null
            return AwakeTime(Units.duration(m.groupValues[1]) ?: return null, Units.duration(m.groupValues[2]) ?: return null)
        }

        private fun durationBeforeParen(value: String): Duration = Units.duration(value.substringBefore("(")) ?: Duration.ZERO

        private fun durationAfterLastColon(content: String): Duration = durationBeforeParen(content.substringAfterLast(":"))

        private fun durationAfterFirstColon(content: String): Duration = durationBeforeParen(content.substringAfter("): "))

        private fun mahInt(value: String): Int = value.removeSuffix("mAh").trim().toIntOrNull() ?: 0
    }
}
