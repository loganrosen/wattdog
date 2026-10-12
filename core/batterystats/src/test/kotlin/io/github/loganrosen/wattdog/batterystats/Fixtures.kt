package io.github.loganrosen.wattdog.batterystats

import io.github.loganrosen.wattdog.batterystats.model.BatteryStatsReport
import io.github.loganrosen.wattdog.batterystats.model.PowerState
import io.github.loganrosen.wattdog.batterystats.parse.BatteryStatsParser
import io.github.loganrosen.wattdog.batterystats.parse.PowerDumpParser

object Fixtures {
    fun text(name: String): String =
        checkNotNull(Fixtures::class.java.getResource("/$name")) { "missing fixture $name" }.readText()

    val report: BatteryStatsReport by lazy { BatteryStatsParser.parse(text("batterystats-charged.txt")) }
    val power: PowerState by lazy { PowerDumpParser.parse(text("power.txt")) }
}
