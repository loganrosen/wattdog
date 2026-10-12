package io.github.loganrosen.wattdog.privileged

/**
 * The only `dumpsys` invocations Wattdog makes. The shell-side service rejects anything else, so
 * the privileged binder can't be used as a general shell.
 */
enum class DumpTarget(val args: List<String>) {
    BATTERYSTATS_CHARGED(listOf("batterystats", "--charged")),
    POWER(listOf("power")),
    DEVICE_IDLE(listOf("deviceidle")),
    BATTERY(listOf("battery")),
    ;

    companion object {
        fun match(args: Array<out String>?): DumpTarget? = args?.toList()?.let { list -> entries.firstOrNull { it.args == list } }
    }
}

/** Something that can run an allowlisted `dumpsys` with the DUMP permission. */
fun interface DumpSource {
    suspend fun dump(target: DumpTarget): String
}

class DumpPermissionDeniedException(message: String) : SecurityException(message)

class NoPrivilegedAccessException : IllegalStateException("Neither Shizuku nor the DUMP permission is available")

/** Services print a denial to the output stream rather than failing the call. */
internal fun String.requireNotDenied(target: DumpTarget): String {
    val head = take(512)
    if (head.startsWith("Permission Denial") || head.contains("Permission Denial: can't dump")) {
        throw DumpPermissionDeniedException("dumpsys ${target.args.joinToString(" ")}: ${head.lineSequence().first()}")
    }
    return this
}
