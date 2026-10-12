package io.github.loganrosen.wattdog.privileged

import java.io.BufferedReader

/**
 * The only `dumpsys` invocations Wattdog makes. The shell-side service rejects anything else, so
 * the privileged binder can't be used as a general shell.
 */
enum class DumpTarget(val args: List<String>) {
    BATTERYSTATS_CHARGED(listOf("batterystats", "--charged")),
    BATTERYSTATS_HISTORY(listOf("batterystats", "--history")),
    POWER(listOf("power")),
    DEVICE_IDLE(listOf("deviceidle")),
    BATTERY(listOf("battery")),
    ;

    companion object {
        fun match(args: Array<out String>?): DumpTarget? = args?.toList()?.let { list -> entries.firstOrNull { it.args == list } }
    }
}

/** Something that can run an allowlisted `dumpsys` with the DUMP permission. */
interface DumpSource {
    /**
     * Streams the output of [target] through [block] on an IO thread. `--history` runs to tens of
     * megabytes on a busy device, so parsers should consume it line by line.
     */
    suspend fun <T> read(target: DumpTarget, block: (BufferedReader) -> T): T

    suspend fun dump(target: DumpTarget): String = read(target) { it.readText() }
}

class DumpPermissionDeniedException(message: String) : SecurityException(message)

class NoPrivilegedAccessException : IllegalStateException("Neither Shizuku nor the DUMP permission is available")

/** Services print a denial to the output stream rather than failing the call. */
internal fun BufferedReader.requireNotDenied(target: DumpTarget): BufferedReader {
    val buf = CharArray(DENIAL_PEEK)
    mark(DENIAL_PEEK)
    val n = read(buf).coerceAtLeast(0)
    reset()
    val head = String(buf, 0, n)
    if (head.startsWith("Permission Denial") || head.contains("Permission Denial: can't dump")) {
        throw DumpPermissionDeniedException("dumpsys ${target.args.joinToString(" ")}: ${head.lineSequence().first()}")
    }
    return this
}

/**
 * Runs [block], then drains anything it left unread so the producer sees a clean EOF instead of a
 * broken pipe, which would turn an intentional early stop into a dumpsys failure.
 */
internal fun <T> BufferedReader.consume(target: DumpTarget, block: (BufferedReader) -> T): T {
    val result = block(requireNotDenied(target))
    val sink = CharArray(DRAIN_BUFFER)
    while (read(sink) >= 0) Unit
    return result
}

private const val DENIAL_PEEK = 512
private const val DRAIN_BUFFER = 8192
