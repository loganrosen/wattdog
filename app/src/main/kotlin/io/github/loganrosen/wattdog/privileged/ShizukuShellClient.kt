package io.github.loganrosen.wattdog.privileged

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.os.IBinder
import io.github.loganrosen.wattdog.BuildConfig
import java.io.BufferedReader
import java.io.IOException
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import rikka.shizuku.Shizuku

/** Binds [WattdogShellService] through Shizuku and runs allowlisted dumps in it. */
class ShizukuShellClient(context: Context) : DumpSource {
    private val serviceArgs = Shizuku.UserServiceArgs(
        ComponentName(context.packageName, WattdogShellService::class.java.name),
    )
        .daemon(false)
        .processNameSuffix("dump")
        .debuggable(BuildConfig.DEBUG)
        // A new version code makes Shizuku restart the service with the updated APK.
        .version(BuildConfig.VERSION_CODE)

    private val mutex = Mutex()

    @Volatile
    private var service: IWattdogShell? = null

    @Volatile
    private var pending: CompletableDeferred<IWattdogShell>? = null

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            if (binder == null || !binder.pingBinder()) return
            val shell = IWattdogShell.Stub.asInterface(binder)
            service = shell
            pending?.complete(shell)
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            service = null
        }
    }

    override suspend fun <T> read(target: DumpTarget, block: (BufferedReader) -> T): T {
        val shell = connect()
        return withContext(Dispatchers.IO) {
            val pfd = shell.dumpsys(target.args.toTypedArray()) ?: throw IOException("Shell service returned no output")
            pfd.readAndClose { it.consume(target, block) }
        }
    }

    private suspend fun connect(): IWattdogShell = mutex.withLock {
        service?.takeIf { it.asBinder().isBinderAlive }?.let { return@withLock it }
        val deferred = CompletableDeferred<IWattdogShell>().also { pending = it }
        withContext(Dispatchers.Main) { Shizuku.bindUserService(serviceArgs, connection) }
        try {
            withTimeout(BIND_TIMEOUT) { deferred.await() }
        } finally {
            pending = null
        }
    }

    fun release() {
        if (Shizuku.pingBinder()) Shizuku.unbindUserService(serviceArgs, connection, true)
        service = null
    }

    private companion object {
        // First bind spawns a new app_process, which takes a couple of seconds on slow devices.
        val BIND_TIMEOUT = 15.seconds
    }
}
