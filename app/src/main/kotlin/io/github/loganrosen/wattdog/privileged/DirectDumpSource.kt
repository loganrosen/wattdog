package io.github.loganrosen.wattdog.privileged

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException

/**
 * Runs `dumpsys` as Wattdog itself after a one-time
 * `adb shell pm grant io.github.loganrosen.wattdog android.permission.DUMP`.
 *
 * batterystats additionally checks usage-stats access, which `pm grant ... PACKAGE_USAGE_STATS`
 * satisfies while the app op is in its default mode.
 */
class DirectDumpSource(private val context: Context) : DumpSource {

    fun isAvailable(): Boolean =
        context.checkSelfPermission(Manifest.permission.DUMP) == PackageManager.PERMISSION_GRANTED

    override suspend fun dump(target: DumpTarget): String = withContext(Dispatchers.IO) {
        val process = Dumpsys.start(target)
        val text = process.inputStream.bufferedReader().use { it.readText() }
        val code = process.waitFor()
        if (code != 0) throw IOException("dumpsys ${target.args.joinToString(" ")} exited with $code")
        text.requireNotDenied(target)
    }
}
