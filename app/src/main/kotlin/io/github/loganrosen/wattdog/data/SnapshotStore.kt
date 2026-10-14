package io.github.loganrosen.wattdog.data

import io.github.loganrosen.wattdog.batterystats.model.BatteryStatsReport
import io.github.loganrosen.wattdog.batterystats.parse.BatteryStatsParser
import java.io.File
import java.util.UUID
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class SnapshotMeta(
    val id: String,
    val label: String,
    val capturedAtMillis: Long,
    /** Ties the snapshot to a batterystats session; a different value means stats were reset since. */
    val startClockTime: String?,
    val onBatteryRealtimeMs: Long,
    val dischargeMah: Int,
    val batteryLevel: Int?,
    /** Saved by background checks rather than by the user; pruned automatically. */
    val auto: Boolean = false,
) {
    /** Whether a delta from this snapshot to [session] is possible, i.e. stats weren't reset in between. */
    fun belongsTo(session: StatsSession): Boolean =
        startClockTime == session.startClockTime && onBatteryRealtimeMs <= session.onBatteryRealtimeMs
}

/** Identifies the batterystats interval a dump came from, so snapshots can be checked without parsing them. */
data class StatsSession(val startClockTime: String?, val onBatteryRealtimeMs: Long) {
    companion object {
        fun of(report: BatteryStatsReport) = StatsSession(report.startClockTime, report.onBattery.realtime.inWholeMilliseconds)
    }
}

/**
 * Raw dumps are kept (gzipped) instead of parsed reports so parser improvements apply to old
 * snapshots. Everything stays in app-private storage; dumps contain device identifiers.
 */
class SnapshotStore(private val dir: File) {
    private val json = Json { ignoreUnknownKeys = true }
    private val mutex = Mutex()
    private val _snapshots = MutableStateFlow<List<SnapshotMeta>>(emptyList())
    val snapshots: StateFlow<List<SnapshotMeta>> = _snapshots.asStateFlow()

    suspend fun load() = io { _snapshots.value = readAll() }

    suspend fun save(
        label: String,
        batterystats: String,
        report: BatteryStatsReport,
        batteryLevel: Int?,
        auto: Boolean = false,
        capturedAtMillis: Long = System.currentTimeMillis(),
    ): SnapshotMeta = io {
        val meta = SnapshotMeta(
            id = UUID.randomUUID().toString(),
            label = label,
            capturedAtMillis = capturedAtMillis,
            startClockTime = report.startClockTime,
            onBatteryRealtimeMs = report.onBattery.realtime.inWholeMilliseconds,
            dischargeMah = report.discharge.totalMah,
            batteryLevel = batteryLevel,
            auto = auto,
        )
        dir.mkdirs()
        GZIPOutputStream(dumpFile(meta.id).outputStream()).bufferedWriter().use { it.write(batterystats) }
        metaFile(meta.id).writeText(json.encodeToString(SnapshotMeta.serializer(), meta))
        _snapshots.value = readAll()
        meta
    }

    suspend fun report(id: String): BatteryStatsReport = io {
        GZIPInputStream(dumpFile(id).inputStream()).bufferedReader().useLines { BatteryStatsParser.parse(it) }
    }

    suspend fun delete(id: String) = deleteWhere { it.id == id }

    /** Deletes automatic snapshots from other stats sessions or older than [maxAgeMillis]. */
    suspend fun pruneAuto(session: StatsSession, nowMillis: Long, maxAgeMillis: Long) =
        deleteWhere { it.auto && (!it.belongsTo(session) || nowMillis - it.capturedAtMillis > maxAgeMillis) }

    suspend fun deleteAuto() = deleteWhere { it.auto }

    private suspend fun deleteWhere(predicate: (SnapshotMeta) -> Boolean) = io {
        readAll().filter(predicate).forEach {
            dumpFile(it.id).delete()
            metaFile(it.id).delete()
        }
        _snapshots.value = readAll()
    }

    private fun readAll(): List<SnapshotMeta> = dir.listFiles { f -> f.name.endsWith(META_SUFFIX) }.orEmpty()
        .mapNotNull { runCatching { json.decodeFromString(SnapshotMeta.serializer(), it.readText()) }.getOrNull() }
        .filter { dumpFile(it.id).exists() }
        .sortedByDescending { it.capturedAtMillis }

    private fun dumpFile(id: String) = File(dir, "$id.batterystats.txt.gz")
    private fun metaFile(id: String) = File(dir, "$id$META_SUFFIX")

    private suspend fun <T> io(block: () -> T): T = withContext(Dispatchers.IO) { mutex.withLock { block() } }

    private companion object {
        const val META_SUFFIX = ".json"
    }
}
