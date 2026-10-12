package io.github.loganrosen.wattdog.batterystats

import io.github.loganrosen.wattdog.batterystats.model.BatteryStatsReport
import io.github.loganrosen.wattdog.batterystats.model.PowerState
import io.github.loganrosen.wattdog.batterystats.parse.BatteryStatsParser
import io.github.loganrosen.wattdog.batterystats.parse.PowerDumpParser
import java.util.zip.GZIPInputStream

object Fixtures {
    fun text(name: String): String {
        val stream = checkNotNull(Fixtures::class.java.getResourceAsStream("/$name")) { "missing fixture $name" }
        return (if (name.endsWith(".gz")) GZIPInputStream(stream) else stream).bufferedReader().use { it.readText() }
    }

    val report: BatteryStatsReport by lazy { BatteryStatsParser.parse(text("batterystats-charged.txt")) }
    val power: PowerState by lazy { PowerDumpParser.parse(text("power.txt")) }

    /** Real captures, anonymized with scripts/anonymize_dump.py. */
    object Real {
        val pixelApi37: BatteryStatsReport by lazy { BatteryStatsParser.parse(text("real/pixel9-api37-charged.txt.gz")) }
        val pixelApi37Power: PowerState by lazy { PowerDumpParser.parse(text("real/pixel9-api37-power.txt.gz")) }
        val emulatorApi34: BatteryStatsReport by lazy { BatteryStatsParser.parse(text("real/emulator-api34-charged.txt.gz")) }
        val emulatorApi34Power: PowerState by lazy { PowerDumpParser.parse(text("real/emulator-api34-power.txt.gz")) }
    }
}
