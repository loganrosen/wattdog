package io.github.loganrosen.wattdog.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.loganrosen.wattdog.batterystats.analysis.SuggestedAction
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
import java.util.Date

private enum class Tab(val label: String, val icon: ImageVector) {
    DASHBOARD("Dashboard", Icons.Default.Home),
    HISTORY("History", Icons.Default.DateRange),
    WAKELOCKS("Wakelocks", Icons.Default.Lock),
    RADIOS("Radios", Icons.Default.Call),
    APPS("Apps", Icons.AutoMirrored.Filled.List),

    // Reached from the top bar rather than the navigation bar.
    SETUP("Settings", Icons.Default.Settings),
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WattdogApp(viewModel: MainViewModel, modifier: Modifier = Modifier) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val backgroundEnabled by viewModel.backgroundChecksEnabled.collectAsStateWithLifecycle()
    val alertState by viewModel.alertState.collectAsStateWithLifecycle()
    val hasAccess = state.access.mode != AccessMode.NONE
    var tab by rememberSaveable { mutableStateOf(Tab.DASHBOARD) }
    var snapshotDialogLabel by remember { mutableStateOf<String?>(null) }
    var focus by remember { mutableStateOf<Focus?>(null) }
    val snackbar = remember { SnackbarHostState() }
    val context = LocalContext.current
    var launchError by remember { mutableStateOf<String?>(null) }

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
                    is Focus.Wakelocks -> Tab.WAKELOCKS
                    is Focus.App, Focus.Apps -> Tab.APPS
                    Focus.Radios -> Tab.RADIOS
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
                            BaselinePicker(state.baseline, state.snapshots, viewModel::selectBaseline, viewModel::deleteSnapshot)

                        hasAccess -> Text("Settings")

                        else -> Text("Wattdog")
                    }
                },
                actions = {
                    if (hasAccess) {
                        IconButton(onClick = { snapshotDialogLabel = "" }) { Icon(Icons.Default.Add, "Take snapshot") }
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
                                Tab.HISTORY -> HistoryScreen(state.history, analysis, onRetry = { viewModel.loadHistory(force = true) })
                                Tab.WAKELOCKS -> WakelocksScreen(analysis, focus = focus as? Focus.Wakelocks)
                                Tab.RADIOS -> RadiosScreen(analysis)
                                Tab.APPS -> AppsScreen(analysis, focusUid = (focus as? Focus.App)?.uid)
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
private fun BaselinePicker(
    baseline: Baseline,
    snapshots: List<SnapshotMeta>,
    onSelect: (Baseline) -> Unit,
    onDelete: (SnapshotMeta) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        TextButton(onClick = { expanded = true }) {
            Text(
                when (baseline) {
                    Baseline.SinceCharge -> "Since unplugged"
                    is Baseline.Snapshot -> "Since ${baseline.meta.label}"
                },
                style = MaterialTheme.typography.titleLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Icon(Icons.Default.ArrowDropDown, null)
        }
        DropdownMenu(expanded, onDismissRequest = { expanded = false }) {
            DropdownMenuItem(
                text = { Text("Since unplugged") },
                onClick = {
                    expanded = false
                    onSelect(Baseline.SinceCharge)
                },
            )
            snapshots.forEach { meta ->
                DropdownMenuItem(
                    text = {
                        Column {
                            Text(meta.label)
                            Text(
                                DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT)
                                    .format(Date(meta.capturedAtMillis)),
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    },
                    onClick = {
                        expanded = false
                        onSelect(Baseline.Snapshot(meta))
                    },
                    trailingIcon = {
                        IconButton(onClick = { onDelete(meta) }) { Icon(Icons.Default.Delete, "Delete snapshot") }
                    },
                )
            }
        }
    }
}

@Composable
private fun SnapshotDialog(initialLabel: String, onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    val default = remember { DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date()) }
    var label by remember { mutableStateOf(initialLabel) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Take snapshot") },
        text = {
            Column {
                Text(
                    "Saves the current stats as a baseline. Pick it from the title menu later to see only what " +
                        "happened since, e.g. an overnight test.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                OutlinedTextField(
                    value = label,
                    onValueChange = { label = it },
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
