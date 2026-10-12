package io.github.loganrosen.wattdog.batterystats.parse

import io.github.loganrosen.wattdog.batterystats.model.AppUid
import io.github.loganrosen.wattdog.batterystats.model.HeldWakeLock
import io.github.loganrosen.wattdog.batterystats.model.PowerState

/** Parser for `dumpsys power`. Only the first occurrence of each `mField=` is used; later ones are history. */
object PowerDumpParser {
    private val field = Regex("""^\s{2}(m\w+)=(\S+)""")
    private val wakeLockLine = Regex("""^\s+(\w+_WAKE_LOCK)\s+'(.*)'\s+(.*)$""")
    private val acquired = Regex("""ACQ=(\S+)""")
    private val ownerUid = Regex("""\(uid=(\d+)""")
    private val workSource = Regex("""ws=WorkSource\{(.*?)\}(?:\s|\)|$)""")
    private val workChainHead = Regex("""WorkChain\{\((\d+)""")
    private val plainWorkSourceUid = Regex("""(?:^|,\s*)(\d+)""")

    fun parse(text: String): PowerState {
        val fields = mutableMapOf<String, String>()
        val wakeLocks = mutableListOf<HeldWakeLock>()
        var inWakeLocks = false
        for (line in text.lineSequence()) {
            if (line.startsWith("Wake Locks: size=")) {
                inWakeLocks = true
                continue
            }
            if (inWakeLocks) {
                if (line.isBlank()) {
                    inWakeLocks = false
                } else {
                    parseWakeLock(line)?.let { wakeLocks += it }
                }
                continue
            }
            field.find(line)?.let { m -> fields.putIfAbsent(m.groupValues[1], m.groupValues[2]) }
        }
        return PowerState(
            wakefulness = fields["mWakefulness"],
            isPowered = fields["mIsPowered"]?.toBooleanStrictOrNull(),
            batteryLevel = fields["mBatteryLevel"]?.toIntOrNull(),
            deviceIdleMode = fields["mDeviceIdleMode"]?.toBooleanStrictOrNull(),
            lightDeviceIdleMode = fields["mLightDeviceIdleMode"]?.toBooleanStrictOrNull(),
            holdingWakeLockSuspendBlocker = fields["mHoldingWakeLockSuspendBlocker"]?.toBooleanStrictOrNull(),
            holdingDisplaySuspendBlocker = fields["mHoldingDisplaySuspendBlocker"]?.toBooleanStrictOrNull(),
            wakeLocks = wakeLocks,
        )
    }

    private fun parseWakeLock(line: String): HeldWakeLock? {
        val m = wakeLockLine.find(line) ?: return null
        val rest = m.groupValues[3]
        val owner = ownerUid.find(rest)?.groupValues?.get(1)?.toIntOrNull() ?: return null
        return HeldWakeLock(
            type = m.groupValues[1],
            tag = m.groupValues[2],
            heldFor = acquired.find(rest)?.groupValues?.get(1)?.let(Units::duration)?.absoluteValue,
            ownerUid = AppUid(owner),
            workSourceUids = workSource.find(rest)?.groupValues?.get(1)?.let(::workSourceUids).orEmpty(),
        )
    }

    /**
     * WorkSource prints either `10154 com.google.android.gms, 10155` or
     * ` chains=WorkChain{(10290), (1041)}`; for chains the first node is the attributed UID.
     */
    private fun workSourceUids(content: String): List<AppUid> {
        val plain = content.substringBefore("chains=")
        val uids = plainWorkSourceUid.findAll(plain.trim()).map { it.groupValues[1].toInt() } +
            workChainHead.findAll(content).map { it.groupValues[1].toInt() }
        return uids.distinct().map(::AppUid).toList()
    }
}
