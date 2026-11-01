package app.sine.playback

import app.sine.core.cosine.PlaySession
import app.sine.core.server.Capability
import app.sine.data.AppGraph
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * Tells Cosine what this phone is playing (§4A): its queue, the song it's
 * at and how far in, as this Device's session, so another device can pick
 * it up. Sent a moment after anything changes (the song, the queue, play or
 * pause), and every half minute while playing, so a phone that dies mid-song
 * is picked up near where it stopped.
 */
class SessionReporter(private val graph: AppGraph) {
    private data class Shape(val queue: List<String>, val index: Int, val playing: Boolean)

    fun start(scope: CoroutineScope) = scope.launch(Dispatchers.Main) {
        graph.player.state
            .map { s -> Shape(s.queue.map { it.item.mediaId }, s.index, s.isPlaying) }
            .distinctUntilChanged()
            .collectLatest { shape ->
                if (shape.queue.isEmpty()) return@collectLatest
                delay(SETTLE_MS)
                while (true) {
                    send(shape)
                    if (!shape.playing) break
                    delay(EVERY_MS)
                }
            }
    }

    private suspend fun send(shape: Shape) {
        val account = graph.player.playingAccount ?: return
        val session = graph.sessionById(account) ?: return
        val cosine = session.cosine ?: return
        if (!session.can(Capability.SESSIONS)) return
        val position = graph.player.positionMs()
        val sent = PlaySession(shape.queue.take(MAX_QUEUE), shape.index.coerceAtLeast(0), position, shape.playing)
        // Off the main thread for the network; a failure waits for the next change.
        kotlinx.coroutines.withContext(Dispatchers.IO) { runCatching { cosine.saveSession(graph.device, sent) } }
    }

    private companion object {
        const val SETTLE_MS = 1_500L
        const val EVERY_MS = 30_000L
        const val MAX_QUEUE = 5_000
    }
}
