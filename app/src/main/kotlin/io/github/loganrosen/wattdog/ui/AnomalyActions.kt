package io.github.loganrosen.wattdog.ui

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.provider.Settings
import androidx.core.net.toUri
import io.github.loganrosen.wattdog.batterystats.analysis.SuggestedAction
import io.github.loganrosen.wattdog.batterystats.model.AppUid
import io.github.loganrosen.wattdog.batterystats.model.WakelockKind
import io.github.loganrosen.wattdog.data.Analysis

/** Where an in-app CTA lands, and what that screen should scroll to and highlight. */
sealed interface Focus {
    data class Wakelocks(val uid: AppUid?, val tag: String?, val kind: WakelockKind?) : Focus
    data class App(val uid: AppUid) : Focus
    data object Apps : Focus
    data object Radios : Focus
}

fun SuggestedAction.label(): String = when (this) {
    is SuggestedAction.ShowWakelocks -> if (tag != null) "View wakelock" else "View wakelocks"
    is SuggestedAction.ShowApp -> "App details"
    SuggestedAction.ShowApps -> "View apps"
    SuggestedAction.ShowRadios -> "View radios"
    is SuggestedAction.TakeSnapshot -> "Mark now"
    is SuggestedAction.OpenAppInfo -> "Open App info"
    is SuggestedAction.OpenAppBatteryUsage -> "App battery settings"
    SuggestedAction.OpenMobileNetworkSettings -> "Mobile network settings"
    SuggestedAction.OpenLockScreenSettings -> "Lock screen settings"
    SuggestedAction.OpenBluetoothSettings -> "Bluetooth settings"
}

fun SuggestedAction.opensSettings(): Boolean = when (this) {
    is SuggestedAction.OpenAppInfo, is SuggestedAction.OpenAppBatteryUsage,
    SuggestedAction.OpenMobileNetworkSettings, SuggestedAction.OpenLockScreenSettings,
    SuggestedAction.OpenBluetoothSettings,
    -> true

    else -> false
}

fun SuggestedAction.toFocus(): Focus? = when (this) {
    is SuggestedAction.ShowWakelocks -> Focus.Wakelocks(uid, tag, kind)
    is SuggestedAction.ShowApp -> Focus.App(uid)
    SuggestedAction.ShowApps -> Focus.Apps
    SuggestedAction.ShowRadios -> Focus.Radios
    else -> null
}

/**
 * Settings intents to try in order. Per-app pages are only offered for a UID that maps to exactly
 * one package for this user; otherwise the page would describe something other than what
 * batterystats measured. Returns empty when the action has no settings page or isn't applicable.
 */
fun SuggestedAction.settingsIntents(analysis: Analysis): List<Intent> {
    fun pkg(uid: AppUid) = analysis.label(uid).settingsPackage
    fun appInfo(p: String) = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, "package:$p".toUri())
    return when (this) {
        is SuggestedAction.OpenAppInfo -> listOfNotNull(pkg(uid)?.let(::appInfo))

        is SuggestedAction.OpenAppBatteryUsage -> pkg(uid)?.let {
            // Not a public constant, but exported by AOSP Settings and lands on the Unrestricted /
            // Optimized / Restricted page. App info is one tap further from the same place.
            listOf(Intent("android.settings.VIEW_ADVANCED_POWER_USAGE_DETAIL", "package:$it".toUri()), appInfo(it))
        }.orEmpty()

        SuggestedAction.OpenMobileNetworkSettings ->
            listOf(Intent(Settings.ACTION_NETWORK_OPERATOR_SETTINGS), Intent(Settings.ACTION_WIRELESS_SETTINGS))

        // There's no public intent for the AOD toggle; on Pixel it lives in lock screen settings.
        SuggestedAction.OpenLockScreenSettings ->
            listOf(Intent("android.settings.LOCK_SCREEN_SETTINGS"), Intent(Settings.ACTION_DISPLAY_SETTINGS))

        SuggestedAction.OpenBluetoothSettings -> listOf(Intent(Settings.ACTION_BLUETOOTH_SETTINGS))

        else -> emptyList()
    }
}

fun SuggestedAction.isAvailable(analysis: Analysis): Boolean = !opensSettings() || settingsIntents(analysis).isNotEmpty()

/** Starts the first intent that resolves. Returns false if none did. */
fun Context.launchFirst(intents: List<Intent>): Boolean = intents.any { intent ->
    try {
        startActivity(intent)
        true
    } catch (_: ActivityNotFoundException) {
        false
    } catch (_: SecurityException) {
        false
    }
}
