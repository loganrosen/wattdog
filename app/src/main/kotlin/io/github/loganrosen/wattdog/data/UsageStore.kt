package io.github.loganrosen.wattdog.data

import io.github.loganrosen.wattdog.batterystats.analysis.UsageLog
import io.github.loganrosen.wattdog.batterystats.analysis.UsagePoint
import io.github.loganrosen.wattdog.batterystats.model.BatteryStatsReport
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/**
 * A small running log of battery counters, recorded every time the app reads batterystats, so the
 * app can learn what's normal for this phone. Unlike snapshots, it survives stats resets.
 */
class UsageStore(private val file: File) {
    private val json = Json { ignoreUnknownKeys = true }
    private val mutex = Mutex()
    private val loaded = MutableStateFlow(false)
    private val _points = MutableStateFlow<List<UsagePoint>>(emptyList())
    val points: StateFlow<List<UsagePoint>> = _points.asStateFlow()

    suspend fun load() = io { ensureLoaded() }

    suspend fun record(report: BatteryStatsReport, nowMillis: Long = System.currentTimeMillis()) = io {
        ensureLoaded()
        val point = UsagePoint.of(report, nowMillis) ?: return@io
        val kept = _points.value.filter { nowMillis - it.capturedAtMillis <= UsageLog.KEEP.inWholeMilliseconds }
        val anchor = kept.getOrNull(kept.size - 2)
        // Opening the app repeatedly shouldn't fill the log with near-duplicates. Comparing with the
        // point before last keeps an anchor in place instead of sliding one point forward forever.
        val replaceLast = anchor != null && kept.last().session == point.session && anchor.session == point.session &&
            nowMillis - anchor.capturedAtMillis < MIN_GAP_MS
        val next = (if (replaceLast) kept.dropLast(1) else kept) + point
        file.parentFile?.mkdirs()
        file.writeText(json.encodeToString(ListSerializer(Stored.serializer()), next.map { Stored.of(it) }))
        _points.value = next
    }

    private fun ensureLoaded() {
        if (loaded.value) return
        _points.value = runCatching {
            json.decodeFromString(ListSerializer(Stored.serializer()), file.readText()).map { it.toPoint() }
        }.getOrDefault(emptyList()).sortedBy { it.capturedAtMillis }
        loaded.value = true
    }

    private suspend fun <T> io(block: () -> T): T = withContext(Dispatchers.IO) { mutex.withLock { block() } }

    @Serializable
    private data class Stored(
        val at: Long,
        val session: String?,
        val battery: Long,
        val onMs: Long,
        val onMah: Int,
        val offMs: Long,
        val offMah: Int,
        val capacity: Int,
    ) {
        fun toPoint() = UsagePoint(at, session, battery, onMs, onMah, offMs, offMah, capacity)

        companion object {
            fun of(p: UsagePoint) = Stored(
                p.capturedAtMillis,
                p.session,
                p.onBatteryMs,
                p.screenOnMs,
                p.screenOnMah,
                p.screenOffMs,
                p.screenOffMah,
                p.capacityMah,
            )
        }
    }

    private companion object {
        const val MIN_GAP_MS = 15 * 60_000L
    }
}
