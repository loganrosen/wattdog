package io.github.loganrosen.wattdog.privileged

import android.os.ParcelFileDescriptor
import android.util.Log
import java.io.FileOutputStream
import kotlin.concurrent.thread
import kotlin.system.exitProcess

/**
 * Runs inside a process that Shizuku starts as the shell user (uid 2000), which holds DUMP.
 * Shizuku instantiates it reflectively, so the no-arg constructor must survive R8 (see proguard-rules.pro).
 */
class WattdogShellService : IWattdogShell.Stub() {

    override fun destroy() {
        exitProcess(0)
    }

    override fun dumpsys(args: Array<out String>?): ParcelFileDescriptor {
        val target = DumpTarget.match(args)
            ?: throw SecurityException("dumpsys ${args?.joinToString(" ")} is not allowlisted")
        val (read, write) = ParcelFileDescriptor.createReliablePipe()
        thread(name = "wattdog-dumpsys-${target.name}") {
            try {
                val process = Dumpsys.start(target)
                // The FileOutputStream borrows the descriptor; the PFD owns and closes it.
                val out = FileOutputStream(write.fileDescriptor)
                process.inputStream.use { it.copyTo(out) }
                out.flush()
                val code = process.waitFor()
                if (code == 0) write.close() else write.closeWithError("dumpsys exited with $code")
            } catch (e: Exception) {
                Log.w(TAG, "dumpsys ${target.args} failed", e)
                runCatching { write.closeWithError(e.toString()) }
            }
        }
        return read
    }

    private companion object {
        const val TAG = "WattdogShell"
    }
}
