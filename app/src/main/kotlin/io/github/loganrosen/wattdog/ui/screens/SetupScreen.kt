package io.github.loganrosen.wattdog.ui.screens

import android.Manifest
import android.content.ClipData
import android.content.Intent
import android.content.pm.PackageManager
import android.text.format.DateUtils
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import io.github.loganrosen.wattdog.BuildConfig
import io.github.loganrosen.wattdog.alerts.LastCheck
import io.github.loganrosen.wattdog.privileged.AccessMode
import io.github.loganrosen.wattdog.privileged.AccessState
import io.github.loganrosen.wattdog.privileged.ShizukuStatus
import io.github.loganrosen.wattdog.ui.components.SectionCard
import io.github.loganrosen.wattdog.ui.components.StatRow
import kotlinx.coroutines.launch

private const val SHIZUKU_URL = "https://shizuku.rikka.app/download/"

data class BackgroundChecksUi(
    val enabled: Boolean,
    val lastCheck: LastCheck?,
    val onEnabledChange: (Boolean) -> Unit,
    val onCheckNow: () -> Unit,
)

@Composable
fun SetupScreen(
    access: AccessState,
    onRequestPermission: () -> Unit,
    launchShizuku: () -> Intent?,
    backgroundChecks: BackgroundChecksUi,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    Column(
        modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            "Wattdog reads dumpsys batterystats and dumpsys power, which need shell-level access. " +
                "Use Shizuku, or grant the permissions once over adb.",
            style = MaterialTheme.typography.bodyMedium,
        )

        SectionCard("Status") {
            StatRow(
                "Active source",
                when (access.mode) {
                    AccessMode.SHIZUKU -> "Shizuku"
                    AccessMode.DIRECT -> "adb grant"
                    AccessMode.NONE -> "None"
                },
            )
            StatRow("Shizuku", shizukuLabel(access.shizuku))
            StatRow("DUMP permission", if (access.directGranted) "Granted" else "Not granted")
        }

        val hasAccess = access.mode != AccessMode.NONE
        // Stays visible without access so checks that keep failing can still be turned off.
        if (hasAccess || backgroundChecks.enabled) BackgroundChecksCard(backgroundChecks, hasAccess)

        SectionCard("Option 1: Shizuku") {
            Text(shizukuHelp(access.shizuku), style = MaterialTheme.typography.bodyMedium)
            when (val s = access.shizuku) {
                ShizukuStatus.NotInstalled -> Button(onClick = {
                    context.startActivity(Intent(Intent.ACTION_VIEW, SHIZUKU_URL.toUri()))
                }) { Text("Get Shizuku") }

                ShizukuStatus.NotRunning -> launchShizuku()?.let { intent ->
                    Button(onClick = { context.startActivity(intent) }) { Text("Open Shizuku") }
                }

                is ShizukuStatus.PermissionRequired ->
                    if (s.permanentlyDenied) {
                        launchShizuku()?.let { intent ->
                            OutlinedButton(onClick = { context.startActivity(intent) }) { Text("Open Shizuku") }
                        }
                    } else {
                        Button(onClick = onRequestPermission) { Text("Grant access") }
                    }

                ShizukuStatus.Unsupported, ShizukuStatus.Granted -> Unit
            }
        }

        SectionCard("Option 2: adb (no Shizuku)") {
            Text(
                "From a computer with adb, run these once. They survive reboots but not reinstalling Wattdog.",
                style = MaterialTheme.typography.bodyMedium,
            )
            CommandBlock(
                "adb shell pm grant ${BuildConfig.APPLICATION_ID} android.permission.DUMP\n" +
                    "adb shell pm grant ${BuildConfig.APPLICATION_ID} android.permission.PACKAGE_USAGE_STATS",
            )
        }

        Text(
            "Dumps never leave the device. Snapshots are stored in app-private storage and contain device and " +
                "app identifiers, so be careful if you share them.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun BackgroundChecksCard(ui: BackgroundChecksUi, hasAccess: Boolean) {
    val context = LocalContext.current
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) ui.onEnabledChange(true)
    }
    fun enable() {
        val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        if (granted) ui.onEnabledChange(true) else permission.launch(Manifest.permission.POST_NOTIFICATIONS)
    }
    SectionCard("Background checks") {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "Every hour on battery, save an automatic snapshot (kept two days) so the title menu can " +
                    "show the last few hours or overnight, and notify about new wakelock, background work, " +
                    "or radio problems.",
                Modifier.weight(1f).padding(end = 12.dp),
                style = MaterialTheme.typography.bodyMedium,
            )
            Switch(
                checked = ui.enabled,
                onCheckedChange = { if (it) enable() else ui.onEnabledChange(false) },
                enabled = hasAccess || ui.enabled,
            )
        }
        if (ui.enabled) {
            ui.lastCheck?.let {
                val now = System.currentTimeMillis()
                val ago = if (now - it.atMillis < DateUtils.MINUTE_IN_MILLIS) {
                    "just now"
                } else {
                    DateUtils.getRelativeTimeSpanString(it.atMillis, now, DateUtils.MINUTE_IN_MILLIS)
                }
                Text(
                    "Last check $ago: ${it.summary}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            OutlinedButton(onClick = ui.onCheckNow, enabled = hasAccess) { Text("Check now") }
        }
    }
}

@Composable
private fun CommandBlock(text: String) {
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Surface(color = MaterialTheme.colorScheme.surfaceVariant, shape = MaterialTheme.shapes.small) {
            SelectionContainer {
                Text(
                    text,
                    Modifier.fillMaxWidth().padding(12.dp),
                    fontFamily = FontFamily.Monospace,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
        OutlinedButton(onClick = {
            scope.launch { clipboard.setClipEntry(ClipEntry(ClipData.newPlainText("adb commands", text))) }
        }) { Text("Copy") }
    }
}

private fun shizukuLabel(status: ShizukuStatus) = when (status) {
    ShizukuStatus.NotInstalled -> "Not installed"
    ShizukuStatus.NotRunning -> "Not running"
    ShizukuStatus.Unsupported -> "Too old"
    is ShizukuStatus.PermissionRequired -> if (status.permanentlyDenied) "Denied" else "Needs permission"
    ShizukuStatus.Granted -> "Ready"
}

private fun shizukuHelp(status: ShizukuStatus) = when (status) {
    ShizukuStatus.NotInstalled -> "Install Shizuku and start it with wireless debugging. No computer needed."

    ShizukuStatus.NotRunning -> "Shizuku is installed but its service isn't running. Start it from the Shizuku app."

    ShizukuStatus.Unsupported -> "This Shizuku version is too old. Update to v11 or newer."

    is ShizukuStatus.PermissionRequired ->
        if (status.permanentlyDenied) {
            "Wattdog was denied. Allow it under Authorized applications in Shizuku."
        } else {
            "Shizuku is running. Allow Wattdog to use it."
        }

    ShizukuStatus.Granted -> "Shizuku is ready."
}
