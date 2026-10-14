package io.github.loganrosen.wattdog.batterystats.analysis

import io.github.loganrosen.wattdog.batterystats.model.BatteryStatsReport
import io.github.loganrosen.wattdog.batterystats.model.PartialWakelock
import kotlin.math.roundToInt
import kotlin.time.Duration

/** What a finding cost in battery, from Android's power model or the coulomb counter. */
data class DrainCost(
    val mah: Double,
    val basis: Basis,
    /** Share of the battery's estimated capacity, 0..1. */
    val ofBattery: Double?,
    /** Share of everything drained in the report period, 0..1. */
    val ofDrain: Double?,
) {
    enum class Basis {
        /** Android's power model estimate. */
        ESTIMATED,

        /** Coulomb-counter discharge. */
        MEASURED,

        /** Covers more than the finding itself, e.g. all of an app's background use. */
        UPPER_BOUND,

        /** Android's model charges this, but nothing really used the power. */
        NOT_REAL,
    }

    /** e.g. `≈1.5% of battery (67 mAh)`. */
    fun summary(): String {
        val amount = (ofBattery?.let { "${formatBatteryShare(it)} of battery (${formatMah(mah)})" } ?: formatMah(mah))
        return when (basis) {
            Basis.ESTIMATED -> "≈$amount"
            Basis.MEASURED -> amount
            Basis.UPPER_BOUND -> "At most $amount"
            Basis.NOT_REAL -> "Android counts ≈$amount for this, but it isn't real drain"
        }
    }

    companion object {
        fun of(report: BatteryStatsReport, mah: Double, basis: Basis): DrainCost? {
            if (mah <= 0.0) return null
            return DrainCost(
                mah = mah,
                basis = basis,
                ofBattery = report.estimatedCapacityMah?.takeIf { it > 0 }?.let { mah / it },
                ofDrain = report.discharge.totalMah.takeIf { it > 0 }?.let { (mah / it).coerceAtMost(1.0) },
            )
        }
    }
}

/** batterystats only estimates wakelock power per UID, so this splits it across tags by hold time. */
class WakelockCosts(private val report: BatteryStatsReport) {
    private val uidMah = report.uidPower.associate { it.uid to (it.components["wakelock"] ?: 0.0) }
    private val uidTime = report.partialWakelocks.groupBy { it.uid }
        .mapValues { (_, locks) -> locks.fold(Duration.ZERO) { acc, wl -> acc + wl.total } }

    fun mah(wl: PartialWakelock): Double {
        val mah = uidMah[wl.uid] ?: return 0.0
        val time = uidTime[wl.uid]?.takeIf { it.isPositive() } ?: return 0.0
        return mah * (wl.total / time)
    }

    fun of(wl: PartialWakelock, basis: DrainCost.Basis): DrainCost? = DrainCost.of(report, mah(wl), basis)
}

fun formatBatteryShare(fraction: Double): String {
    val pct = fraction * 100
    return when {
        pct < 0.1 -> "<0.1%"
        pct < 10 -> "%.1f%%".format(pct)
        else -> "${pct.roundToInt()}%"
    }
}
