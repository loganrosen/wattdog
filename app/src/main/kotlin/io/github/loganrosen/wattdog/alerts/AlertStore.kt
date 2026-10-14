package io.github.loganrosen.wattdog.alerts

import io.github.loganrosen.wattdog.batterystats.analysis.AlertRecord
import io.github.loganrosen.wattdog.batterystats.analysis.Severity
import java.io.File
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

/** Background-check state: which findings were already notified, access failures, the last result. */
class AlertStore(private val dir: File) {
    private val json = Json { ignoreUnknownKeys = true }
    private val mutex = Mutex()

    // Windows used to start from a single saved dump; they now start from automatic snapshots.
    private val legacyCheckpoint = File(dir, "checkpoint.batterystats.txt.gz")
    private val stateFile = File(dir, "state.json")
    private val _state = MutableStateFlow(AlertState())
    val state: StateFlow<AlertState> = _state.asStateFlow()

    suspend fun load() = io {
        legacyCheckpoint.delete()
        _state.value = read()
    }

    suspend fun update(block: (AlertState) -> AlertState) = io {
        val next = block(read())
        dir.mkdirs()
        stateFile.writeText(json.encodeToString(AlertState.serializer(), next))
        _state.value = next
    }

    /** Forget everything, so re-enabling starts a fresh window. */
    suspend fun clear() = io {
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
