package io.github.loganrosen.wattdog.privileged

import java.io.BufferedReader
import kotlin.time.Duration
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

enum class AccessMode { SHIZUKU, DIRECT, NONE }

data class AccessState(val shizuku: ShizukuStatus, val directGranted: Boolean) {
    val mode: AccessMode
        get() = when {
            shizuku == ShizukuStatus.Granted -> AccessMode.SHIZUKU
            directGranted -> AccessMode.DIRECT
            else -> AccessMode.NONE
        }
}

/** Picks Shizuku when it's granted, otherwise an adb-granted DUMP permission. */
class PrivilegedAccess(
    private val shizuku: ShizukuManager,
    private val shizukuClient: ShizukuShellClient,
    private val direct: DirectDumpSource,
    scope: CoroutineScope,
) : DumpSource {
    private val directGranted = MutableStateFlow(direct.isAvailable())

    val state: StateFlow<AccessState> = combine(shizuku.status, directGranted, ::AccessState)
        .stateIn(scope, SharingStarted.Eagerly, AccessState(shizuku.status.value, directGranted.value))

    /** Call on resume: Shizuku may have been started, or DUMP granted over adb, while we were away. */
    fun refresh() {
        shizuku.refresh()
        directGranted.value = direct.isAvailable()
    }

    /** Like [refresh], but gives a cold-started process time to receive Shizuku's binder. */
    suspend fun awaitAccess(timeout: Duration): AccessMode {
        shizuku.awaitBinder(timeout)
        directGranted.value = direct.isAvailable()
        return AccessState(shizuku.status.value, directGranted.value).mode
    }

    fun requestShizukuPermission() = shizuku.requestPermission()

    fun shizukuLaunchIntent() = shizuku.launchIntent()

    override suspend fun <T> read(target: DumpTarget, block: (BufferedReader) -> T): T {
        // Read the live status rather than the cached combined state, which lags by a dispatch.
        val current = AccessState(shizuku.status.value, direct.isAvailable())
        return when (current.mode) {
            AccessMode.SHIZUKU -> shizukuClient.read(target, block)
            AccessMode.DIRECT -> direct.read(target, block)
            AccessMode.NONE -> throw NoPrivilegedAccessException()
        }
    }
}
