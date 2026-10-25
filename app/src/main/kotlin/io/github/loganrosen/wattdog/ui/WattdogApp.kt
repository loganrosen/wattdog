package io.github.loganrosen.wattdog.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import io.github.loganrosen.wattdog.batterystats.analysis.SuggestedAction
import io.github.loganrosen.wattdog.batterystats.analysis.WindowPresets
import io.github.loganrosen.wattdog.batterystats.analysis.formatShort
import io.github.loganrosen.wattdog.data.Analysis
import io.github.loganrosen.wattdog.data.Baseline
import io.github.loganrosen.wattdog.data.SnapshotMeta
import io.github.loganrosen.wattdog.privileged.AccessMode
import io.github.loganrosen.wattdog.ui.screens.AppsScreen
import io.github.loganrosen.wattdog.ui.screens.BackgroundChecksUi
import io.github.loganrosen.wattdog.ui.screens.DashboardScreen
import io.github.loganrosen.wattdog.ui.screens.HistoryScreen
import io.github.loganrosen.wattdog.ui.screens.RadiosScreen
import io.github.loganrosen.wattdog.ui.screens.SetupScreen
import io.github.loganrosen.wattdog.ui.screens.WakelocksScreen
import java.text.DateFormat
import java.time.Instant
import java.time.ZoneId
import java.time.format.TextStyle
import java.util.Date
import java.util.Locale
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.delay

private enum class DiagnosticsSection(val label: String) { WAKELOCKS("Wakelocks"), RADIOS("Radios") }

private enum class Tab(val label: String, val icon: ImageVector) {
    DASHBOARD("Today", Icons.Default.Home),
    HISTORY("Trends", Icons.Default.DateRange),
    APPS("Apps", Icons.AutoMirrored.Filled.List),
    DIAGNOSTICS("Diagnostics", Icons.Default.Build),

    // Reached from the top bar rather than the navigation bar.
    SETUP("Settings", Icons.Default.Settings),
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WattdogApp(viewModel: MainViewModel, modifier: Modifier = Modifier) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val backgroundEnabled by viewModel.backgroundChecksEnabled.collectAsStateWithLifecycle()
    val alertState by viewModel.alertState.collectAsStateWithLifecycle()
    val usage by viewModel.usage.collectAsStateWithLifecycle()
    val hasAccess = state.access.mode != AccessMode.NONE
    var tab by rememberSaveable { mutableStateOf(Tab.DASHBOARD) }
    var section by rememberSaveable { mutableStateOf(DiagnosticsSection.WAKELOCKS) }
    var snapshotDialogLabel by remember { mutableStateOf<String?>(null) }
    var focus by remember { mutableStateOf<Focus?>(null) }
    val snackbar = remember { SnackbarHostState() }
    val context = LocalContext.current
    var launchError by remember { mutableStateOf<String?>(null) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle

    // Android has no public callback for permission changes, so an adb grant made while we're in
    // the foreground would otherwise go unnoticed until the next resume.
    LaunchedEffect(hasAccess) {
        if (hasAccess) return@LaunchedEffect
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            while (true) {
                delay(1.seconds)
                viewModel.recheckAccess()
            }
        }
    }

    LaunchedEffect(launchError) {
        launchError?.let {
            snackbar.showSnackbar(it)
            launchError = null
        }
    }

    fun onAction(action: SuggestedAction) {
        when {
            action is SuggestedAction.TakeSnapshot -> snapshotDialogLabel = action.label

            action.opensSettings() -> {
                val analysis = state.analysis ?: return
                if (!context.launchFirst(action.settingsIntents(analysis))) {
                    launchError = "Couldn't open ${action.label().lowercase()} on this device"
                }
            }

            else -> action.toFocus()?.let {
                focus = it
                tab = when (it) {
                    is Focus.Wakelocks -> Tab.DIAGNOSTICS.also { section = DiagnosticsSection.WAKELOCKS }
                    is Focus.App, Focus.Apps -> Tab.APPS
                    Focus.Radios -> Tab.DIAGNOSTICS.also { section = DiagnosticsSection.RADIOS }
                }
            }
        }
    }

    val pendingAction by viewModel.pendingAction.collectAsStateWithLifecycle()
    LaunchedEffect(pendingAction) {
        pendingAction?.let {
            onAction(it)
            viewModel.actionHandled()
        }
    }

    LaunchedEffect(state.message) {
        state.message?.let {
            snackbar.showSnackbar(it)
            viewModel.messageShown()
        }
    }

    val current = if (hasAccess) tab else Tab.SETUP

    BackHandler(enabled = hasAccess && current != Tab.DASHBOARD) {
        tab = Tab.DASHBOARD
        focus = null
    }

    LaunchedEffect(current, hasAccess) {
        if (current == Tab.HISTORY && hasAccess) viewModel.loadHistory()
    }

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = {
                    when {
                        hasAccess && current != Tab.SETUP ->
                            BaselinePicker(
                                BaselinePickerUi(
                                    baseline = state.baseline,
                                    snapshots = state.snapshots,
                                    analysis = state.analysis,
                                    autoEnabled = backgroundEnabled,
                                    onSelect = viewModel::selectBaseline,
                                    onDelete = viewModel::deleteSnapshot,
                                    onMarkNow = { snapshotDialogLabel = "" },
                                    onOpenSettings = { tab = Tab.SETUP },
                                ),
                            )

                        hasAccess -> Text("Settings")

                        else -> Text("Wattdog")
                    }
                },
                actions = {
                    if (hasAccess) {
                        IconButton(onClick = viewModel::refresh, enabled = !state.loading) {
                            Icon(Icons.Default.Refresh, "Refresh")
                        }
                        IconButton(onClick = { tab = Tab.SETUP }) { Icon(Icons.Default.Settings, "Settings") }
                    }
                },
            )
        },
        bottomBar = {
            if (hasAccess) {
                NavigationBar {
                    Tab.entries.filter { it != Tab.SETUP }.forEach { t ->
                        NavigationBarItem(
                            selected = current == t,
                            onClick = {
                                tab = t
                                focus = null
                            },
                            icon = { Icon(t.icon, null) },
                            label = { Text(t.label) },
                        )
                    }
                }
            }
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            if (state.loading) LinearProgressIndicator(Modifier.fillMaxWidth())
            Box(Modifier.fillMaxSize()) {
                val analysis = state.analysis
                val error = state.error
                when {
                    current == Tab.SETUP -> SetupScreen(
                        access = state.access,
                        onRequestPermission = viewModel::requestShizukuPermission,
                        launchShizuku = viewModel::shizukuLaunchIntent,
                        backgroundChecks = BackgroundChecksUi(
                            enabled = backgroundEnabled,
                            lastCheck = alertState.lastCheck,
                            onEnabledChange = viewModel::setBackgroundChecks,
                            onCheckNow = viewModel::checkNow,
                        ),
                    )

                    error != null && analysis == null -> ErrorState(error, viewModel::refresh)

                    analysis == null -> Unit

                    else -> {
                        Column {
                            error?.let { ErrorBanner(it) }
                            when (current) {
                                Tab.DASHBOARD -> DashboardScreen(analysis, ::onAction)

                                Tab.HISTORY -> HistoryScreen(state.history, analysis, usage, onRetry = {
                                    viewModel.loadHistory(force = true)
                                })

                                Tab.APPS -> AppsScreen(analysis, focusUid = (focus as? Focus.App)?.uid)

                                Tab.DIAGNOSTICS -> Column {
                                    Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        DiagnosticsSection.entries.forEach { d ->
                                            FilterChip(
                                                selected = section == d,
                                                onClick = {
                                                    section = d
                                                    focus = null
                                                },
                                                label = { Text(d.label) },
                                            )
                                        }
                                    }
                                    when (section) {
                                        DiagnosticsSection.WAKELOCKS -> WakelocksScreen(analysis, focus = focus as? Focus.Wakelocks)
                                        DiagnosticsSection.RADIOS -> RadiosScreen(analysis)
                                    }
                                }

                                Tab.SETUP -> Unit
                            }
                        }
                    }
                }
            }
        }
    }

    snapshotDialogLabel?.let { initial ->
        SnapshotDialog(
            initialLabel = initial,
            onDismiss = { snapshotDialogLabel = null },
            onConfirm = {
                snapshotDialogLabel = null
                viewModel.takeSnapshot(it)
            },
        )
    }
}

@Composable
private fun BaselinePicker(ui: BaselinePickerUi, modifier: Modifier = Modifier) {
    var expanded by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf<SnapshotMeta?>(null) }
    Box(modifier) {
        TextButton(onClick = { expanded = true }) {
            Column(Modifier.weight(1f, fill = false)) {
                Text(
                    when (val b = ui.baseline) {
                        Baseline.SinceCharge -> "Since last full charge"
                        is Baseline.Snapshot -> "Since ${b.meta.title()}"
                    },
                    style = MaterialTheme.typography.titleLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                ui.windowLength?.let {
                    val asOf = ui.analysis?.capturedAtMillis?.let { at -> " · as of ${clockTime(at, System.currentTimeMillis())}" } ?: ""
                    Text("${it.formatShort()} on battery$asOf", style = MaterialTheme.typography.bodySmall, maxLines = 1)
                }
            }
            Icon(Icons.Default.ArrowDropDown, null)
        }
        DropdownMenu(expanded, onDismissRequest = { expanded = false }) {
            fun select(baseline: Baseline) {
                expanded = false
                ui.onSelect(baseline)
            }
            val now = remember(expanded) { System.currentTimeMillis() }
            val session = ui.analysis?.session
            val autos = ui.snapshots.filter { it.auto && ui.usable(it) }
            val presets = remember(autos, now) { WindowPresets.pick(autos, { it.capturedAtMillis }, now, ZoneId.systemDefault()) }
            val marks = ui.snapshots.filter { !it.auto }

            MenuHeader("Recent")
            DropdownMenuItem(
                text = {
                    MenuText("Since last full charge", session?.let { "${it.onBatteryRealtimeMs.milliseconds.formatShort()} on battery" })
                },
                onClick = { select(Baseline.SinceCharge) },
            )
            presets.forEach { p ->
                DropdownMenuItem(
                    text = {
                        MenuText(
                            p.kind.label,
                            "Since ${clockTime(
                                p.startMillis,
                                now,
                            )} · ${(now - p.startMillis).milliseconds.inWholeMinutes.minutes.formatShort()} ago",
                        )
                    },
                    onClick = { select(Baseline.Snapshot(p.point)) },
                )
            }
            when {
                !ui.autoEnabled -> DropdownMenuItem(
                    text = { MenuText("Last hour, overnight…", "Turn on background checks to get these") },
                    onClick = {
                        expanded = false
                        ui.onOpenSettings()
                    },
                )

                presets.isEmpty() -> DropdownMenuItem(
                    text = { MenuText("Last hour, overnight…", "Appear after an hour or so on battery") },
                    onClick = {},
                    enabled = false,
                )
            }

            HorizontalDivider()
            MenuHeader("Marks")
            marks.forEach { meta ->
                val stale = !ui.usable(meta)
                DropdownMenuItem(
                    text = {
                        MenuText(
                            meta.label,
                            if (stale) "Stats reset since (charged or rebooted)" else dateTime(meta.capturedAtMillis),
                        )
                    },
                    onClick = { select(Baseline.Snapshot(meta)) },
                    enabled = !stale,
                    trailingIcon = {
                        IconButton(onClick = { confirmDelete = meta }) { Icon(Icons.Default.Delete, "Delete ${meta.label}") }
                    },
                )
            }
            DropdownMenuItem(
                text = { MenuText("Mark now…", "Save this moment to compare against later") },
                leadingIcon = { Icon(Icons.Default.Add, null) },
                onClick = {
                    expanded = false
                    ui.onMarkNow()
                },
            )
        }
    }
    confirmDelete?.let { meta ->
        AlertDialog(
            onDismissRequest = { confirmDelete = null },
            title = { Text("Delete \"${meta.label}\"?") },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = null
                    ui.onDelete(meta)
                }) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = null }) { Text("Cancel") } },
        )
    }
}

private class BaselinePickerUi(
    val baseline: Baseline,
    val snapshots: List<SnapshotMeta>,
    val analysis: Analysis?,
    val autoEnabled: Boolean,
    val onSelect: (Baseline) -> Unit,
    val onDelete: (SnapshotMeta) -> Unit,
    val onMarkNow: () -> Unit,
    val onOpenSettings: () -> Unit,
)

/** On-battery time covered by what's shown, once it matches [BaselinePickerUi.baseline]. */
private val BaselinePickerUi.windowLength: Duration?
    get() = analysis?.takeIf { it.baseline == baseline }?.report?.onBattery?.realtime

/**
 * Whether a snapshot can still be compared against: it's from the current stats interval. One
 * saved after the last load is newer than anything known about, so it's given the benefit of the doubt.
 */
private fun BaselinePickerUi.usable(meta: SnapshotMeta): Boolean =
    analysis == null || meta.capturedAtMillis > analysis.capturedAtMillis || meta.belongsTo(analysis.session)

@Composable
private fun MenuHeader(text: String) {
    Text(
        text,
        Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.primary,
    )
}

@Composable
private fun MenuText(title: String, detail: String?) {
    Column {
        Text(title)
        detail?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
    }
}

private fun SnapshotMeta.title(): String = if (auto) clockTime(capturedAtMillis, System.currentTimeMillis()) else label

/** "10:52 PM", or "Tue 10:52 PM" when it wasn't today. */
private fun clockTime(millis: Long, now: Long): String {
    val zone = ZoneId.systemDefault()
    val at = Instant.ofEpochMilli(millis).atZone(zone)
    val time = DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(millis))
    return if (at.toLocalDate() == Instant.ofEpochMilli(now).atZone(zone).toLocalDate()) {
        time
    } else {
        "${at.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.getDefault())} $time"
    }
}

private fun dateTime(millis: Long): String = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(millis))

@Composable
private fun SnapshotDialog(initialLabel: String, onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    val default = remember { DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date()) }
    var label by remember { mutableStateOf(initialLabel) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Mark now") },
        text = {
            Column {
                Text(
                    "Saves the current stats so you can later see only what happened since, e.g. before " +
                        "changing a setting. Pick it under Marks in the title menu. Marks stop working once " +
                        "the phone charges.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                OutlinedTextField(
                    value = label,
                    onValueChange = { label = it },
                    label = { Text("Name") },
                    placeholder = { Text(default) },
                    singleLine = true,
                    modifier = Modifier.padding(top = 12.dp),
                )
            }
        },
        confirmButton = { Button(onClick = { onConfirm(label.ifBlank { default }) }) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun ErrorState(message: String, onRetry: () -> Unit) {
    Column(Modifier.padding(24.dp)) {
        Text("Couldn't load battery stats", style = MaterialTheme.typography.titleMedium)
        Text(message, Modifier.padding(vertical = 8.dp), style = MaterialTheme.typography.bodyMedium)
        Button(onClick = onRetry) { Text("Retry") }
    }
}

@Composable
private fun ErrorBanner(message: String) {
    Text(
        message,
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        color = MaterialTheme.colorScheme.error,
        style = MaterialTheme.typography.bodySmall,
    )
}
