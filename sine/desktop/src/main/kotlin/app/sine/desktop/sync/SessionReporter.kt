package app.sine.desktop.sync

import app.sine.core.cosine.PlaySession
import app.sine.core.server.Capability
import app.sine.desktop.DesktopGraph
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Tells Cosine what this computer is playing (§4A), as the phone does: its
 * queue, the song and how far in, a moment after anything changes and every
 * half minute while playing, so another device can pick it up.
 */
class SessionReporter(private val graph: DesktopGraph) {
    private data class Shape(val queue: List<String>, val index: Int, val playing: Boolean)

    private var scope: CoroutineScope? = null

    /** Sends the session now: after a remote command, so the device that sent it sees it done. */
    fun sendNow() {
        scope?.launch {
            delay(300)
            val s = graph.player.state.value
            if (s.current != null) send(Shape(s.queue.tracks.map { it.id }, s.queue.pos, s.isPlaying))
        }
    }

    fun start(scope: CoroutineScope) = scope.also { this.scope = it }.launch {
        graph.player.state
            .map { s -> Shape(s.queue.tracks.map { it.id }, s.queue.pos, s.isPlaying) }
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
        if (graph.player.state.value.jam != null) return
        val account = graph.player.playingAccount ?: return
        val session = graph.sessionById(account) ?: return
        val cosine = session.cosine ?: return
        if (!session.can(Capability.SESSIONS)) return
        val sent = PlaySession(
            shape.queue.take(MAX_QUEUE), shape.index.coerceAtLeast(0), graph.player.positionMs(), shape.playing,
            volume = graph.player.state.value.volume.toDouble(),
        )
        withContext(Dispatchers.IO) { runCatching { cosine.saveSession(graph.device, sent) } }
    }

    private companion object {
        const val SETTLE_MS = 1_500L
        const val EVERY_MS = 30_000L
        const val MAX_QUEUE = 5_000
    }
}
