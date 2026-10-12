package io.github.loganrosen.wattdog.privileged

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import java.io.BufferedReader
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Runs `dumpsys` as Wattdog itself after a one-time
 * `adb shell pm grant io.github.loganrosen.wattdog android.permission.DUMP`.
 *
 * batterystats additionally checks usage-stats access, which `pm grant ... PACKAGE_USAGE_STATS`
 * satisfies while the app op is in its default mode.
 */
class DirectDumpSource(private val context: Context) : DumpSource {

    fun isAvailable(): Boolean = context.checkSelfPermission(Manifest.permission.DUMP) == PackageManager.PERMISSION_GRANTED

    override suspend fun <T> read(target: DumpTarget, block: (BufferedReader) -> T): T = withContext(Dispatchers.IO) {
        val process = Dumpsys.start(target)
        try {
            val result = process.inputStream.bufferedReader().use { it.consume(target, block) }
            val code = process.waitFor()
            if (code != 0) throw IOException("dumpsys ${target.args.joinToString(" ")} exited with $code")
            result
        } finally {
            process.destroy()
        }
    }
}
