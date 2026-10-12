package io.github.loganrosen.wattdog.batterystats.analysis

import io.github.loganrosen.wattdog.batterystats.model.AppUid
import io.github.loganrosen.wattdog.batterystats.model.WakelockKind

/**
 * Something the user can do about an [Anomaly]. These are intentionally read-only: they navigate
 * inside Wattdog or open a system settings page where the user makes the change themselves.
 * The first action in [Anomaly.actions] is the primary one.
 */
sealed interface SuggestedAction {
    data class ShowWakelocks(val uid: AppUid? = null, val tag: String? = null, val kind: WakelockKind? = null) : SuggestedAction
    data class ShowApp(val uid: AppUid) : SuggestedAction
    data object ShowApps : SuggestedAction
    data object ShowRadios : SuggestedAction

    /** Save a baseline so the user can measure the effect of a settings change. */
    data class TakeSnapshot(val label: String) : SuggestedAction

    /** App info page. Only offered when [uid] maps to exactly one package for the current user. */
    data class OpenAppInfo(val uid: AppUid) : SuggestedAction

    /** The per-app battery page with Unrestricted / Optimized / Restricted. Same constraint as [OpenAppInfo]. */
    data class OpenAppBatteryUsage(val uid: AppUid) : SuggestedAction

    data object OpenMobileNetworkSettings : SuggestedAction
    data object OpenLockScreenSettings : SuggestedAction
    data object OpenBluetoothSettings : SuggestedAction
}
