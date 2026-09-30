package app.sine.core.play

import app.sine.core.persist.JsonFile
import kotlinx.serialization.Serializable
import java.io.File

/**
 * Plays are append-only timestamped events, not a counter (Q37). Recorded
 * locally the moment they happen — online or not — and submitted later with
 * their real time (Q70). Merging across devices is then a union.
 */
@Serializable
data class PlayEvent(val trackId: String, val playedAt: Long)

@Serializable
private data class PlayLogState(val pending: List<PlayEvent> = emptyList())

class PlayLog(file: File) {
    private val store = JsonFile(file, PlayLogState.serializer()) { PlayLogState() }

    @Synchronized
    fun record(event: PlayEvent) {
        store.write(PlayLogState(store.read().pending + event))
    }

    @Synchronized
    fun pending(): List<PlayEvent> = store.read().pending

    /**
     * Submits pending plays in order. Stops at the first failure and keeps the
     * rest, so an interrupted flush loses nothing and repeats nothing already sent.
     */
    suspend fun flush(submit: suspend (PlayEvent) -> Unit): Int {
        var sent = 0
        for (event in pending()) {
            try {
                submit(event)
            } catch (e: Exception) {
                break
            }
            acknowledge(event)
            sent++
        }
        return sent
    }

    @Synchronized
    private fun acknowledge(event: PlayEvent) {
        val current = store.read().pending
        val i = current.indexOf(event)
        if (i >= 0) store.write(PlayLogState(current.toMutableList().apply { removeAt(i) }))
    }

    companion object {
        /** Scrobbler convention: a play counts after half the track, or four minutes. */
        fun counts(listenedMs: Long, durationMs: Long): Boolean =
            durationMs > 0 && (listenedMs >= durationMs / 2 || listenedMs >= 4 * 60_000)
    }
}
