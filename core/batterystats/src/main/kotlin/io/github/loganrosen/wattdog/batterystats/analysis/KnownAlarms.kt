package io.github.loganrosen.wattdog.batterystats.analysis

/** Explains wakeup alarm tags that come from platform features rather than app polling. */
internal object KnownAlarms {
    data class Info(val what: String, val advice: String, val actions: List<SuggestedAction> = emptyList())

    private val byTagFragment = listOf(
        "IkeAlarmReceiver.ACTION_DPD" to Info(
            what = "Wi-Fi calling checking that its connection to your carrier is still alive (IKE dead peer detection)",
            advice = "Expected while Wi-Fi calling is on and connected. Turning off Wi-Fi calling stops it, " +
                "but calls and texts then go over cellular only.",
            actions = listOf(SuggestedAction.OpenMobileNetworkSettings),
        ),
        "IkeAlarmReceiver.ACTION_REKEY" to Info(
            what = "Wi-Fi calling renewing its encryption keys",
            advice = "Expected while Wi-Fi calling is on.",
            actions = listOf(SuggestedAction.OpenMobileNetworkSettings),
        ),
        "doze_time_tick" to Info(
            what = "the always-on display updating its clock",
            advice = "Comes with always-on display. It stops if you turn that off, but each tick is cheap; " +
                "the display itself costs more.",
            actions = listOf(SuggestedAction.OpenLockScreenSettings),
        ),
        "NetworkLocationActivityDetection" to Info(
            what = "Google's activity recognition (walking, driving, still) running on behalf of this app",
            advice = "Used for step counts, bedtime, and driving detection. If you don't need that from this app, " +
                "remove its Physical activity permission in App info.",
        ),
    )

    fun lookup(tag: String): Info? = byTagFragment.firstOrNull { (fragment, _) -> tag.contains(fragment) }?.second
}
