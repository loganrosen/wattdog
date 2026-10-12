package io.github.loganrosen.wattdog.batterystats.model

import kotlin.time.Duration

/** Live state from `dumpsys power`. */
data class PowerState(
    val wakefulness: String? = null,
    val isPowered: Boolean? = null,
    val batteryLevel: Int? = null,
    val deviceIdleMode: Boolean? = null,
    val lightDeviceIdleMode: Boolean? = null,
    val holdingWakeLockSuspendBlocker: Boolean? = null,
    val holdingDisplaySuspendBlocker: Boolean? = null,
    val wakeLocks: List<HeldWakeLock> = emptyList(),
)

data class HeldWakeLock(
    /** e.g. `PARTIAL_WAKE_LOCK`, `DOZE_WAKE_LOCK`. */
    val type: String,
    val tag: String,
    /** How long ago it was acquired (positive). */
    val heldFor: Duration?,
    val ownerUid: AppUid,
    /** UIDs from the WorkSource, which is who batterystats blames (e.g. the app behind an audioserver lock). */
    val workSourceUids: List<AppUid>,
) {
    val blamedUids: List<AppUid> get() = workSourceUids.ifEmpty { listOf(ownerUid) }
}
