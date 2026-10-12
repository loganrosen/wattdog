package io.github.loganrosen.wattdog.privileged

import android.os.ParcelFileDescriptor
import java.io.FileInputStream
import java.io.IOException

internal object Dumpsys {
    private const val BINARY = "/system/bin/dumpsys"

    fun start(target: DumpTarget): Process = ProcessBuilder(listOf(BINARY) + target.args)
        .redirectErrorStream(true)
        .start()
}

/**
 * Reads a pipe produced by [WattdogShellService] to EOF. The writer uses a reliable pipe, so a
 * failure on the shell side surfaces here as an [IOException] from `checkError`.
 */
internal fun ParcelFileDescriptor.readTextAndClose(): String = use { pfd ->
    val text = FileInputStream(pfd.fileDescriptor).bufferedReader().readText()
    pfd.checkError()
    text
}
