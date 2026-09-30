package app.sine.core.download

import app.sine.core.model.Track
import app.sine.core.persist.JsonFile
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import java.io.File

/**
 * A pinned download: explicit, never evicted, lives in the user-visible folder (§2.5).
 * Keeps the full [Track] so the library stays browsable and playable offline (G1).
 */
@Serializable
data class DownloadedTrack(
    val track: Track,
    /** Where the file lives: a SAF document URI on Android, a path in tests. */
    val location: String,
    val sizeBytes: Long,
    val downloadedAt: Long,
    /** App-private cached cover, so artwork works offline too. */
    val coverFile: String? = null,
)

@Serializable
private data class IndexState(val tracks: Map<String, DownloadedTrack> = emptyMap())

/** One per account. */
class DownloadIndex(file: File) {
    private val store = JsonFile(file, IndexState.serializer()) { IndexState() }
    private val _tracks = MutableStateFlow(store.read().tracks)
    val tracks: StateFlow<Map<String, DownloadedTrack>> = _tracks.asStateFlow()

    operator fun get(trackId: String): DownloadedTrack? = _tracks.value[trackId]
    operator fun contains(trackId: String) = trackId in _tracks.value
    val totalBytes: Long get() = _tracks.value.values.sumOf { it.sizeBytes }

    @Synchronized
    fun put(entry: DownloadedTrack) = update { it + (entry.track.id to entry) }

    @Synchronized
    fun remove(trackIds: Collection<String>) = update { it - trackIds.toSet() }

    @Synchronized
    fun clear() = update { emptyMap() }

    private fun update(f: (Map<String, DownloadedTrack>) -> Map<String, DownloadedTrack>) {
        val next = f(_tracks.value)
        store.write(IndexState(next))
        _tracks.value = next
    }
}
