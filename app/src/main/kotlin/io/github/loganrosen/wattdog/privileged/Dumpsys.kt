package io.github.loganrosen.wattdog.privileged

import android.os.ParcelFileDescriptor
import java.io.BufferedReader
import java.io.FileInputStream
import java.io.IOException

internal object Dumpsys {
    private const val BINARY = "/system/bin/dumpsys"

    fun start(target: DumpTarget): Process = ProcessBuilder(listOf(BINARY) + target.args)
        .redirectErrorStream(true)
        .start()
}

/**
 * Reads a pipe produced by [WattdogShellService]. The writer uses a reliable pipe, so a failure on
 * the shell side surfaces here as an [IOException] from `checkError` once [block] reaches EOF.
 */
internal fun <T> ParcelFileDescriptor.readAndClose(block: (BufferedReader) -> T): T = use { pfd ->
    val result = block(FileInputStream(pfd.fileDescriptor).bufferedReader())
    pfd.checkError()
    result
}
