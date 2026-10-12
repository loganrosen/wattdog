package io.github.loganrosen.wattdog.batterystats.model

/**
 * A kernel UID as printed by batterystats via `UserHandle.formatUid`, e.g. `1000`, `u0a316`,
 * `u10a622`, `u0i12`, `u0ai3`, or `u10s1000`.
 */
@JvmInline
value class AppUid(val raw: Int) : Comparable<AppUid> {
    val userId: Int get() = raw / PER_USER_RANGE
    val appId: Int get() = raw % PER_USER_RANGE

    val isApplication: Boolean get() = appId in FIRST_APPLICATION_UID..LAST_APPLICATION_UID
    val isIsolated: Boolean get() = appId in FIRST_APP_ZYGOTE_ISOLATED_UID..LAST_ISOLATED_UID

    /** Mirrors `UserHandle.formatUid` so the value round-trips with batterystats output. */
    fun format(): String = when {
        raw < FIRST_APPLICATION_UID -> raw.toString()
        isIsolated && appId >= FIRST_ISOLATED_UID -> "u${userId}i${appId - FIRST_ISOLATED_UID}"
        isIsolated -> "u${userId}ai${appId - FIRST_APP_ZYGOTE_ISOLATED_UID}"
        appId >= FIRST_APPLICATION_UID -> "u${userId}a${appId - FIRST_APPLICATION_UID}"
        else -> "u${userId}s$appId"
    }

    override fun compareTo(other: AppUid): Int = raw.compareTo(other.raw)

    override fun toString(): String = format()

    companion object {
        const val PER_USER_RANGE = 100_000
        const val FIRST_APPLICATION_UID = 10_000
        const val LAST_APPLICATION_UID = 19_999
        const val FIRST_APP_ZYGOTE_ISOLATED_UID = 90_000
        const val FIRST_ISOLATED_UID = 99_000
        const val LAST_ISOLATED_UID = 99_999

        private val pattern = Regex("""^(?:(\d+)|u(\d+)(ai|a|i|s)(\d+))$""")

        fun parse(text: String): AppUid? {
            val m = pattern.matchEntire(text.trim()) ?: return null
            m.groupValues[1].takeIf { it.isNotEmpty() }?.let { return AppUid(it.toInt()) }
            val user = m.groupValues[2].toInt()
            val n = m.groupValues[4].toInt()
            val appId = when (m.groupValues[3]) {
                "a" -> FIRST_APPLICATION_UID + n
                "i" -> FIRST_ISOLATED_UID + n
                "ai" -> FIRST_APP_ZYGOTE_ISOLATED_UID + n
                else -> n
            }
            return AppUid(user * PER_USER_RANGE + appId)
        }
    }
}

/** Well-known AIDs from `system/core/libcutils/include/private/android_filesystem_config.h`. */
object SystemUids {
    private val names = mapOf(
        0 to "Kernel / root",
        1000 to "Android System",
        1001 to "Phone (radio)",
        1002 to "Bluetooth",
        1003 to "Graphics",
        1010 to "Wi-Fi",
        1013 to "Media server",
        1019 to "DRM",
        1021 to "GPS",
        1027 to "NFC",
        1036 to "Logging (logd)",
        1037 to "Shared RELRO",
        1041 to "Audio server",
        1046 to "Media codec",
        1047 to "Camera server",
        1066 to "statsd",
        1073 to "Network stack",
        2000 to "Shell",
        9999 to "Nobody",
    )

    fun nameFor(uid: AppUid): String? = if (uid.appId < AppUid.FIRST_APPLICATION_UID) names[uid.appId] else null
}
