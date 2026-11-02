package app.sine.desktop.sync

import app.sine.core.cosine.RemoteCommand
import app.sine.core.server.Capability
import app.sine.core.session.AccountSession
import app.sine.desktop.DesktopGraph
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Lets another of the account's devices control this computer through
 * Cosine (§4B), as on the phone: a request held open there for commands
 * while Sine is open, backing off to a minute when the server is away.
 */
class RemoteListener(private val graph: DesktopGraph) {
    fun start(scope: CoroutineScope) = scope.launch {
        graph.activeSession.collectLatest { session ->
            if (session == null) return@collectLatest
            listen(session)
        }
    }

    private suspend fun listen(session: AccountSession) {
        var backoff = 5_000L
        var named = false
        while (true) {
            val cosine = session.cosine
            if (cosine == null || !session.can(Capability.SESSIONS)) return
            if (!graph.visible.value && !graph.player.state.value.isPlaying) {
                delay(10_000)
                continue
            }
            try {
                if (!named) {
                    cosine.seen(graph.device)
                    named = true
                }
                val commands = cosine.listen(graph.device.id)
                backoff = 5_000
                for (c in commands) apply(session, c)
                if (commands.isNotEmpty()) graph.reporter.sendNow()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                delay(backoff)
                backoff = (backoff * 2).coerceAtMost(60_000)
            }
        }
    }

    private suspend fun apply(session: AccountSession, c: RemoteCommand) {
        val player = graph.player
        when (c.type) {
            "play" -> player.resume()
            "pause" -> player.pause()
            "toggle" -> player.togglePlay()
            "next" -> player.next()
            "previous" -> player.previous()
            "seek" -> player.seekTo(c.positionMs)
            "skip" -> if (c.index in player.state.value.queue.order.indices) player.skipTo(c.index)
            "volume" -> player.setVolume(c.volume.toFloat())
            "queue" -> {
                // Another device's music, sent here: its songs as this library knows them.
                val tracks = withContext(Dispatchers.IO) {
                    c.queue.mapNotNull { id -> runCatching { session.track(id).value }.getOrNull() }
                }
                val start = c.queue.getOrNull(c.index)
                val index = tracks.indexOfFirst { it.id == start }.coerceAtLeast(0)
                if (tracks.isNotEmpty()) player.play(session, tracks, index, startMs = if (tracks[index].id == start) c.positionMs else 0)
            }
        }
    }
}
