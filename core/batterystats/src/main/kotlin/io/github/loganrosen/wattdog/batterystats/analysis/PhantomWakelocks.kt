package io.github.loganrosen.wattdog.batterystats.analysis

import io.github.loganrosen.wattdog.batterystats.model.AppUid
import io.github.loganrosen.wattdog.batterystats.model.BatteryStatsReport
import io.github.loganrosen.wattdog.batterystats.model.PartialWakelock
import io.github.loganrosen.wattdog.batterystats.model.PowerState
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes

/**
 * Finds partial wakelocks whose batterystats timer is running with no real lock behind it.
 *
 * Seen on Pixel builds with frozen/cached-process wakelock disabling: an audioserver AudioMix
 * lock attributed to an app gets a start in batterystats with no matching stop. The timer then
 * survives stats resets (showing `(0 times)`) until reboot.
 */
class PhantomWakelocks(private val report: BatteryStatsReport, power: PowerState?, private val slack: Duration = 5.minutes) {
    enum class Reason { EXCEEDS_AWAKE_TIME, NOT_IN_POWER_MANAGER }

    private val heldNow: Set<Pair<AppUid, String>>? = power?.wakeLocks
        ?.filter { it.type == "PARTIAL_WAKE_LOCK" }
        ?.flatMap { lock -> lock.blamedUids.map { it to lock.tag } }
        ?.toSet()

    fun isHeldNow(wl: PartialWakelock): Boolean = heldNow?.contains(wl.uid to wl.tag) == true

    fun reason(wl: PartialWakelock): Reason? {
        // Partial wakelock timers only advance while the screen is off, and a real lock keeps the
        // CPU out of suspend, so its total can't exceed screen-off uptime. runningFor isn't used
        // here because in a delta it still counts from before the window started.
        val awake = report.screenOff.uptime
        if (awake > Duration.ZERO && wl.total > awake + slack) return Reason.EXCEEDS_AWAKE_TIME
        val running = wl.runningFor ?: return null
        if (heldNow != null && running >= slack && !isHeldNow(wl)) return Reason.NOT_IN_POWER_MANAGER
        return null
    }

    fun isPhantom(wl: PartialWakelock): Boolean = reason(wl) != null
}
