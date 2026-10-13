package io.github.loganrosen.wattdog.alerts

import io.github.loganrosen.wattdog.batterystats.analysis.AlertRecord
import io.github.loganrosen.wattdog.batterystats.analysis.Severity
import io.github.loganrosen.wattdog.batterystats.model.BatteryStatsReport
import io.github.loganrosen.wattdog.batterystats.parse.BatteryStatsParser
import java.io.File
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
data class LastCheck(val atMillis: Long, val summary: String)

@Serializable
data class AlertState(
    val records: Map<String, StoredRecord> = emptyMap(),
    val accessFailures: Int = 0,
    val accessNoticeShown: Boolean = false,
    val lastCheck: LastCheck? = null,
) {
    @Serializable
    data class StoredRecord(val severity: String, val notifiedAtMillis: Long)

    fun alertRecords(): Map<String, AlertRecord> = records.mapNotNull { (key, r) ->
        Severity.entries.firstOrNull { it.name == r.severity }?.let { key to AlertRecord(it, r.notifiedAtMillis) }
    }.toMap()

    fun withRecords(records: Map<String, AlertRecord>) =
        copy(records = records.mapValues { (_, r) -> StoredRecord(r.severity.name, r.notifiedAtMillis) })
}

/**
 * Background-check state: the dump from the previous check (the start of the next window) and
 * which findings were already notified. Kept apart from user snapshots so it never shows in the
 * baseline picker.
 */
class AlertStore(private val dir: File) {
    private val json = Json { ignoreUnknownKeys = true }
    private val mutex = Mutex()
    private val checkpointFile = File(dir, "checkpoint.batterystats.txt.gz")
    private val stateFile = File(dir, "state.json")
    private val _state = MutableStateFlow(AlertState())
    val state: StateFlow<AlertState> = _state.asStateFlow()

    suspend fun load() = io { _state.value = read() }

    suspend fun checkpoint(): BatteryStatsReport? = io {
        if (!checkpointFile.exists()) return@io null
        runCatching {
            GZIPInputStream(checkpointFile.inputStream()).bufferedReader().useLines { BatteryStatsParser.parse(it) }
        }.getOrNull()
    }

    suspend fun saveCheckpoint(raw: String) = io {
        dir.mkdirs()
        val tmp = File(dir, checkpointFile.name + ".tmp")
        GZIPOutputStream(tmp.outputStream()).bufferedWriter().use { it.write(raw) }
        tmp.renameTo(checkpointFile)
    }

    suspend fun update(block: (AlertState) -> AlertState) = io {
        val next = block(read())
        dir.mkdirs()
        stateFile.writeText(json.encodeToString(AlertState.serializer(), next))
        _state.value = next
    }

    /** Forget everything, so re-enabling starts a fresh window. */
    suspend fun clear() = io {
        checkpointFile.delete()
        stateFile.delete()
        _state.value = AlertState()
    }

    private fun read(): AlertState = if (stateFile.exists()) {
        runCatching { json.decodeFromString(AlertState.serializer(), stateFile.readText()) }.getOrDefault(AlertState())
    } else {
        AlertState()
    }

    private suspend fun <T> io(block: () -> T): T = withContext(Dispatchers.IO) { mutex.withLock { block() } }
}
