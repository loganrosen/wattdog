package io.github.loganrosen.wattdog

import android.content.Context
import io.github.loganrosen.wattdog.alerts.AlertStore
import io.github.loganrosen.wattdog.alerts.AnomalyNotifier
import io.github.loganrosen.wattdog.alerts.BackgroundCheck
import io.github.loganrosen.wattdog.alerts.BackgroundChecks
import io.github.loganrosen.wattdog.data.BatteryRepository
import io.github.loganrosen.wattdog.data.SnapshotStore
import io.github.loganrosen.wattdog.data.UidResolver
import io.github.loganrosen.wattdog.privileged.DirectDumpSource
import io.github.loganrosen.wattdog.privileged.PrivilegedAccess
import io.github.loganrosen.wattdog.privileged.ShizukuManager
import io.github.loganrosen.wattdog.privileged.ShizukuShellClient
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/** Manual DI; the graph is small enough that Hilt would cost more than it saves. */
class AppContainer(context: Context) {
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    val access = PrivilegedAccess(
        shizuku = ShizukuManager(context),
        shizukuClient = ShizukuShellClient(context),
        direct = DirectDumpSource(context),
        scope = appScope,
    )

    val uidResolver = UidResolver(context.packageManager)
    val snapshots = SnapshotStore(File(context.filesDir, "snapshots"))
    val repository = BatteryRepository(access, snapshots, uidResolver)

    val alertStore = AlertStore(File(context.filesDir, "alerts")).also { appScope.launch { it.load() } }
    val notifier = AnomalyNotifier(context)
    val backgroundCheck = BackgroundCheck(access, repository, snapshots, alertStore, notifier)
    val backgroundChecks = BackgroundChecks(context, alertStore, snapshots)
}
