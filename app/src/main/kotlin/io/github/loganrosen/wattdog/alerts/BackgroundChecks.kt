package io.github.loganrosen.wattdog.alerts

import android.content.Context
import androidx.core.content.edit
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import io.github.loganrosen.wattdog.data.SnapshotStore
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** The user-facing switch for periodic checks, which also take the automatic snapshots. */
class BackgroundChecks(context: Context, private val store: AlertStore, private val snapshots: SnapshotStore) {
    private val prefs = context.getSharedPreferences("background_checks", Context.MODE_PRIVATE)
    private val work = WorkManager.getInstance(context)
    private val _enabled = MutableStateFlow(prefs.getBoolean(KEY_ENABLED, false))
    val enabled: StateFlow<Boolean> = _enabled.asStateFlow()

    suspend fun setEnabled(on: Boolean) {
        prefs.edit { putBoolean(KEY_ENABLED, on) }
        _enabled.value = on
        if (on) {
            schedule()
            checkNow()
        } else {
            work.cancelUniqueWork(PERIODIC)
            work.cancelUniqueWork(ONCE)
            store.clear()
            snapshots.deleteAuto()
        }
    }

    init {
        // Picks up schedule changes in new versions; UPDATE keeps the existing timing.
        if (_enabled.value) schedule()
    }

    private fun schedule() {
        val request = PeriodicWorkRequestBuilder<AnomalyCheckWorker>(INTERVAL_HOURS, TimeUnit.HOURS).build()
        work.enqueueUniquePeriodicWork(PERIODIC, ExistingPeriodicWorkPolicy.UPDATE, request)
    }

    fun checkNow() {
        work.enqueueUniqueWork(ONCE, ExistingWorkPolicy.KEEP, OneTimeWorkRequestBuilder<AnomalyCheckWorker>().build())
    }

    private companion object {
        const val KEY_ENABLED = "enabled"
        const val PERIODIC = "anomaly-check"
        const val ONCE = "anomaly-check-now"
        const val INTERVAL_HOURS = 1L
    }
}
