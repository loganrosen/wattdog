package io.github.loganrosen.wattdog.data

import io.github.loganrosen.wattdog.batterystats.analysis.Experiment
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

/** The one change being tracked, if any. */
class ExperimentStore(private val file: File) {
    private val json = Json { ignoreUnknownKeys = true }
    private val mutex = Mutex()
    private val _current = MutableStateFlow<Experiment?>(null)
    val current: StateFlow<Experiment?> = _current.asStateFlow()

    suspend fun load() = io {
        _current.value = runCatching { json.decodeFromString(Stored.serializer(), file.readText()).toExperiment() }.getOrNull()
    }

    suspend fun set(experiment: Experiment?) = io {
        if (experiment == null) {
            file.delete()
        } else {
            file.parentFile?.mkdirs()
            file.writeText(json.encodeToString(Stored.serializer(), Stored.of(experiment)))
        }
        _current.value = experiment
    }

    private suspend fun <T> io(block: () -> T): T = withContext(Dispatchers.IO) { mutex.withLock { block() } }

    // Older files also carry the before rate the first version compared against; it's ignored now.
    @Serializable
    private data class Stored(val title: String, val startedAt: Long, val matchAod: Boolean = true) {
        fun toExperiment() = Experiment(title, startedAt, matchAod)

        companion object {
            fun of(e: Experiment) = Stored(e.title, e.startedAtMillis, e.matchAod)
        }
    }
}
