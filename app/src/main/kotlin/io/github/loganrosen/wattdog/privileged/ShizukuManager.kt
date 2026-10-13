package io.github.loganrosen.wattdog.privileged

import android.content.Context
import android.content.pm.PackageManager
import kotlin.coroutines.resume
import kotlin.time.Duration
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import rikka.shizuku.Shizuku

sealed interface ShizukuStatus {
    /** Neither the Shizuku app nor Sui is present. */
    data object NotInstalled : ShizukuStatus

    /** Installed but the server isn't running (it has to be restarted after every reboot without root). */
    data object NotRunning : ShizukuStatus

    /** Server older than API 11, which lacks the permission and UserService APIs we use. */
    data object Unsupported : ShizukuStatus

    /** [permanentlyDenied] is set after "deny and don't ask again"; requests then fail silently. */
    data class PermissionRequired(val permanentlyDenied: Boolean) : ShizukuStatus

    data object Granted : ShizukuStatus
}

/** Tracks Shizuku availability and our permission as a [StateFlow]. */
class ShizukuManager(private val context: Context) {
    private val _status = MutableStateFlow(computeStatus())
    val status: StateFlow<ShizukuStatus> = _status.asStateFlow()

    private val binderReceived = Shizuku.OnBinderReceivedListener { refresh() }
    private val binderDead = Shizuku.OnBinderDeadListener { refresh() }
    private val permissionResult = Shizuku.OnRequestPermissionResultListener { requestCode, _ ->
        if (requestCode == REQUEST_CODE) refresh()
    }

    init {
        Shizuku.addBinderReceivedListenerSticky(binderReceived)
        Shizuku.addBinderDeadListener(binderDead)
        Shizuku.addRequestPermissionResultListener(permissionResult)
    }

    fun refresh() {
        _status.value = computeStatus()
    }

    fun requestPermission() {
        if (_status.value is ShizukuStatus.PermissionRequired) Shizuku.requestPermission(REQUEST_CODE)
    }

    fun launchIntent() = context.packageManager.getLaunchIntentForPackage(SHIZUKU_PACKAGE)

    /**
     * When WorkManager starts our process cold, Shizuku hands over its binder asynchronously a moment
     * later, so an immediate ping reports "not running" even though the server is up.
     */
    suspend fun awaitBinder(timeout: Duration): Boolean {
        if (!Shizuku.pingBinder() && isInstalled()) {
            var listener: Shizuku.OnBinderReceivedListener? = null
            try {
                withTimeoutOrNull(timeout) {
                    suspendCancellableCoroutine { cont ->
                        listener = Shizuku.OnBinderReceivedListener { if (cont.isActive) cont.resume(Unit) }
                            .also(Shizuku::addBinderReceivedListenerSticky)
                    }
                }
            } finally {
                listener?.let(Shizuku::removeBinderReceivedListener)
            }
        }
        refresh()
        return Shizuku.pingBinder()
    }

    private fun computeStatus(): ShizukuStatus {
        if (!Shizuku.pingBinder()) {
            return if (isInstalled()) ShizukuStatus.NotRunning else ShizukuStatus.NotInstalled
        }
        if (Shizuku.isPreV11()) return ShizukuStatus.Unsupported
        return if (Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) {
            ShizukuStatus.Granted
        } else {
            ShizukuStatus.PermissionRequired(permanentlyDenied = Shizuku.shouldShowRequestPermissionRationale())
        }
    }

    private fun isInstalled(): Boolean = try {
        context.packageManager.getPackageInfo(SHIZUKU_PACKAGE, 0)
        true
    } catch (_: PackageManager.NameNotFoundException) {
        false
    }

    companion object {
        const val SHIZUKU_PACKAGE = "moe.shizuku.privileged.api"
        private const val REQUEST_CODE = 0x5741
    }
}
