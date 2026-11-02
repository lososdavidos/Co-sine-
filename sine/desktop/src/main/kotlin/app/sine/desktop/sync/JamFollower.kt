package app.sine.desktop.sync

import app.sine.core.cosine.Jam
import app.sine.core.cosine.JamInvitation
import app.sine.core.cosine.at
import app.sine.core.model.Track
import app.sine.core.server.Capability
import app.sine.core.session.AccountSession
import app.sine.desktop.DesktopGraph
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlin.math.abs

/**
 * Keeps this computer in a jam (§4C), as the phone does: it hears every
 * change the moment Cosine has it and holds the player to the jam's clock,
 * the same song within about a second, playing or paused as the host has it.
 */
class JamFollower(private val graph: DesktopGraph) {
    private val _jam = MutableStateFlow<Jam?>(null)
    val jam: StateFlow<Jam?> = _jam.asStateFlow()

    private val _invitations = MutableStateFlow<List<JamInvitation>>(emptyList())
    val invitations: StateFlow<List<JamInvitation>> = _invitations.asStateFlow()

    @Volatile private var offset = 0L
    private var loaded: List<String> = emptyList()
    private val wake = MutableStateFlow(0)

    /** Looks again now: after starting, joining or leaving. */
    fun refresh() {
        wake.value++
    }

    fun start(scope: CoroutineScope) = scope.launch {
        launch {
            while (true) {
                delay(DRIFT_CHECK_MS)
                val j = _jam.value ?: continue
                val s = graph.activeSession.value ?: continue
                align(s, j)
            }
        }
        graph.activeSession.collectLatest { session ->
            _jam.value = null
            if (session == null) return@collectLatest
            wake.collectLatest { follow(session) }
        }
    }

    private suspend fun follow(session: AccountSession) {
        while (true) {
            val cosine = session.cosine ?: return
            if (!session.can(Capability.JAM)) return
            try {
                val mine = cosine.jams()
                _invitations.value = mine.invitations
                var j = mine.jam
                if (j == null) {
                    leave()
                    delay(if (graph.visible.value) 20_000 else 60_000)
                    continue
                }
                while (j != null && !j.ended) {
                    offset = j.serverTime - System.currentTimeMillis()
                    _jam.value = j
                    align(session, j)
                    j = cosine.jam(j.id, since = j.version)
                    if (j.ended || !j.you.present) j = null
                }
                leave()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                delay(5_000)
            }
        }
    }

    private fun leave() {
        if (_jam.value != null) {
            _jam.value = null
            loaded = emptyList()
            graph.player.leaveJam()
        }
    }

    /** Holds the player to where the jam is now. */
    private fun align(session: AccountSession, j: Jam) {
        val player = graph.player
        val ids = j.queue.map { it.track }
        if (ids.isEmpty()) {
            player.pause()
            return
        }
        val (index, position) = j.at(System.currentTimeMillis() + offset)
        val songs = j.queue.map { Track(it.track, it.title, it.artist, album = it.album, durationSec = it.durationSec, coverArt = it.coverArt) }
        val state = player.state.value
        when {
            state.jam == j.id && loaded.isNotEmpty() && ids.size > loaded.size && ids.take(loaded.size) == loaded -> {
                player.appendJam(songs.drop(loaded.size))
                loaded = ids
            }
            ids != loaded || state.jam != j.id -> {
                player.playJam(session, j.id, songs, index, position, j.playing)
                loaded = ids
                return
            }
        }
        val off = abs(player.positionMs() - position)
        if (state.queue.pos != index) return player.jumpTo(index, position, j.playing)
        if (off > DRIFT_MS) player.seekTo(position)
        if (j.playing && !state.isPlaying) player.resume()
        if (!j.playing && state.isPlaying) player.pause()
    }

    private companion object {
        const val DRIFT_MS = 900L
        const val DRIFT_CHECK_MS = 4_000L
    }
}
