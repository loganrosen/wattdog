package io.github.loganrosen.wattdog.alerts

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import io.github.loganrosen.wattdog.WattdogApplication
import kotlinx.coroutines.CancellationException

class AnomalyCheckWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val container = (applicationContext as WattdogApplication).container
        try {
            container.backgroundCheck.run()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // A retry storm wouldn't help; the next periodic run tries again.
            Log.w(TAG, "Background check failed", e)
            container.alertStore.update {
                it.copy(lastCheck = LastCheck(System.currentTimeMillis(), "Failed: ${e.message ?: e.javaClass.simpleName}"))
            }
        }
        return Result.success()
    }

    private companion object {
        const val TAG = "AnomalyCheckWorker"
    }
}
