package app.sine.desktop.player

import app.sine.core.model.Track
import app.sine.core.persist.JsonFile
import app.sine.core.play.PlayEvent
import app.sine.core.play.PlayLog
import app.sine.core.play.PlaySource
import app.sine.core.session.AccountSession
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.nullable
import java.io.File

/** The player as the screens see it. */
data class PlayerState(
    val queue: PlayQueue = PlayQueue(),
    val isPlaying: Boolean = false,
    val positionMs: Long = 0,
    val durationMs: Long = 0,
    /** The account whose songs these are. */
    val account: String? = null,
    /** Playing a jam's songs (§4C): held to its clock, not this queue's own. */
    val jam: Long? = null,
    /** Why nothing plays, when something should: VLC missing, the song unreachable. */
    val problem: String? = null,
    val volume: Float = 1f,
) {
    val current: Track? get() = queue.current?.track
}

/** What played last, for Home's Now playing tile and to pick up from on the next start (§6.4). */
@Serializable
data class LastPlayed(
    val account: String,
    val queue: List<Track>,
    val index: Int,
    val positionMs: Long,
)

/**
 * Plays the queue through an [AudioEngine]: the desktop's PlayerConnection,
 * with the same handles, so the session reporter, the remote and the jam
 * work as they do on the phone. Every song played far enough goes in its
 * account's play log (§4.2).
 */
class DesktopPlayer(
    private val engine: AudioEngine?,
    private val scope: CoroutineScope,
    private val sessionOf: (String) -> AccountSession?,
    lastFile: File,
) {
    private val _state = MutableStateFlow(PlayerState(problem = if (engine == null) NO_VLC else null))
    val state: StateFlow<PlayerState> = _state.asStateFlow()

    private val lastStore = JsonFile(lastFile, LastPlayed.serializer().nullable) { null }
    private val _last = MutableStateFlow(lastStore.read())
    /** The last song played here, kept between runs. */
    val last: StateFlow<LastPlayed?> = _last.asStateFlow()

    // Listening time on the song playing, for the play log.
    private var listenedMs = 0L
    private var heardFrom = 0L

    init {
        engine?.onEnded = { scope.launch { ended() } }
        engine?.onError = { scope.launch { failed() } }
        scope.launch {
            while (true) {
                delay(TICK_MS)
                val e = engine ?: continue
                val s = _state.value
                if (s.current == null) continue
                _state.update { it.copy(positionMs = e.positionMs, durationMs = e.durationMs.takeIf { d -> d > 0 } ?: (it.current?.durationSec ?: 0) * 1000L) }
                if (s.isPlaying) remember()
            }
        }
    }

    val playingAccount: String? get() = _state.value.account

    fun positionMs(): Long = engine?.positionMs ?: _state.value.positionMs

    /** Plays [tracks] from [index], from [startMs] into it. */
    fun play(session: AccountSession, tracks: List<Track>, index: Int = 0, startMs: Long = 0) {
        if (tracks.isEmpty()) return
        logPlayed()
        _state.update { it.copy(queue = it.queue.start(tracks, index), account = session.account.id, jam = null) }
        load(startMs, play = true)
    }

    /** Shuffles [tracks] and plays them. */
    fun shuffle(session: AccountSession, tracks: List<Track>) {
        if (tracks.isEmpty()) return
        _state.update { it.copy(queue = it.queue.withShuffle(true)) }
        play(session, tracks, tracks.indices.random())
    }

    /** Adds songs to play next, after the ones added before (#37). */
    fun addToQueue(session: AccountSession, tracks: List<Track>) {
        if (tracks.isEmpty()) return
        val wasEmpty = _state.value.queue.isEmpty
        _state.update { it.copy(queue = it.queue.add(tracks), account = it.account ?: session.account.id) }
        if (wasEmpty) load(0, play = true)
    }

    fun togglePlay() = if (_state.value.isPlaying) pause() else resume()

    fun pause() {
        engine?.pause()
        _state.update { it.copy(isPlaying = false) }
        remember()
    }

    fun resume() {
        val s = _state.value
        if (s.current == null) return
        if (engine == null) return
        // Picked up from the last run: nothing loaded yet.
        if (engine.durationMs == 0L && !engine.isPlaying && s.positionMs > 0) load(s.positionMs, play = true)
        else engine.play()
        heardFrom = System.currentTimeMillis()
        _state.update { it.copy(isPlaying = true) }
    }

    fun next() {
        val q = _state.value.queue.next() ?: return
        logPlayed()
        _state.update { it.copy(queue = q) }
        load(0, play = true)
    }

    /** Back to this song's start a few seconds in; at its start, the song before. */
    fun previous() {
        if (positionMs() > RESTART_MS) return seekTo(0)
        logPlayed()
        _state.update { it.copy(queue = it.queue.previous()) }
        load(0, play = true)
    }

    fun seekTo(ms: Long) {
        engine?.seek(ms)
        _state.update { it.copy(positionMs = ms) }
    }

    /** Plays the song at [at], its place in the queue's order. */
    fun skipTo(at: Int) {
        logPlayed()
        _state.update { it.copy(queue = it.queue.skipTo(at)) }
        load(0, play = true)
    }

    /** Takes a song that's still to come out of the queue. */
    fun remove(at: Int) = _state.update { it.copy(queue = it.queue.remove(at)) }

    fun toggleShuffle() = _state.update { it.copy(queue = it.queue.withShuffle(!it.queue.shuffle)) }

    fun cycleRepeat() = _state.update { it.copy(queue = it.queue.withRepeat(it.queue.nextRepeat())) }

    fun setVolume(fraction: Float) {
        engine?.volume = fraction
        _state.update { it.copy(volume = fraction.coerceIn(0f, 1f)) }
    }

    /** Clears what's still to come; the song playing goes on. */
    fun clearAhead() = _state.update { s ->
        val q = s.queue
        s.copy(queue = q.copy(order = q.order.take(q.pos + 1)))
    }

    // ------------------------------------------------------------ the jam (§4C)

    /** Plays a jam's songs at its place, through the jam where they aren't in this library. */
    fun playJam(session: AccountSession, jamId: Long, tracks: List<Track>, index: Int, positionMs: Long, playing: Boolean) {
        logPlayed()
        _state.update { it.copy(queue = PlayQueue().start(tracks, index), account = session.account.id, jam = jamId) }
        load(positionMs, play = playing)
    }

    /** In a jam: the song at [at] from [ms], where the jam's clock has moved on to. */
    fun jumpTo(at: Int, ms: Long, play: Boolean) {
        val q = _state.value.queue
        if (at !in q.order.indices) return
        logPlayed()
        _state.update { it.copy(queue = q.copy(pos = at)) }
        load(ms, play)
    }

    /** Songs added to the jam: on the end, the song playing undisturbed. */
    fun appendJam(tracks: List<Track>) = _state.update { s ->
        val q = s.queue
        val first = q.entries.size
        val added = tracks.mapIndexed { i, t -> Entry(t, key = -(first + i + 1L)) }
        s.copy(queue = q.copy(entries = q.entries + added, order = q.order + added.indices.map { first + it }))
    }

    /** Out of a jam: the jam's songs stop, and the player is this device's own again. */
    fun leaveJam() {
        if (_state.value.jam == null) return
        engine?.stop()
        _state.update { it.copy(jam = null, isPlaying = false) }
    }

    // ------------------------------------------------------------ inside

    private fun load(startMs: Long, play: Boolean) {
        val s = _state.value
        val track = s.current ?: return
        val session = s.account?.let(sessionOf) ?: return
        if (engine == null) {
            _state.update { it.copy(isPlaying = false, problem = NO_VLC) }
            return
        }
        val source = if (s.jam != null) session.jamSource(track, s.jam) else session.playSource(track)
        val location = when (source) {
            is PlaySource.Local -> source.location
            is PlaySource.Remote -> source.url
            PlaySource.Unavailable -> {
                _state.update { it.copy(isPlaying = false, problem = "${track.title} isn't on this computer, and the server can't be reached.") }
                return
            }
        }
        engine.load(location, startMs, play)
        listenedMs = 0
        heardFrom = System.currentTimeMillis()
        _state.update {
            it.copy(isPlaying = play, positionMs = startMs, durationMs = track.durationSec * 1000L, problem = null)
        }
        remember()
    }

    private fun ended() {
        logPlayed()
        val s = _state.value
        if (s.jam != null) {
            // The jam's clock says what's next; the follower moves the player there.
            _state.update { it.copy(isPlaying = false) }
            return
        }
        if (s.queue.repeat == Repeat.ONE) return load(0, play = true)
        val q = s.queue.next()
        if (q == null) {
            _state.update { it.copy(isPlaying = false, positionMs = 0) }
            return
        }
        _state.update { it.copy(queue = q) }
        load(0, play = true)
    }

    private fun failed() {
        val title = _state.value.current?.title ?: return
        _state.update { it.copy(isPlaying = false, problem = "$title wouldn't play.") }
    }

    /** The song that was playing goes in the play log, if it was heard long enough (§4.2). */
    private fun logPlayed() {
        val s = _state.value
        val track = s.current ?: return
        if (s.isPlaying) listenedMs += System.currentTimeMillis() - heardFrom
        val session = s.account?.let(sessionOf) ?: return
        if (PlayLog.counts(listenedMs, track.durationSec * 1000L)) {
            session.plays.record(PlayEvent(track.id, System.currentTimeMillis()))
            scope.launch { runCatching { session.flushPlays() } }
        }
        listenedMs = 0
        heardFrom = System.currentTimeMillis()
    }

    private fun remember() {
        val s = _state.value
        val account = s.account ?: return
        if (s.jam != null || s.queue.isEmpty) return
        val tracks = s.queue.tracks
        val last = LastPlayed(account, tracks.take(MAX_KEPT), s.queue.pos.coerceAtMost(MAX_KEPT - 1), s.positionMs)
        _last.value = last
        runCatching { lastStore.write(last) }
    }

    /** Picks up where the last run stopped, paused, as the phone does after a restart. */
    fun restore() {
        val last = _last.value ?: return
        if (!_state.value.queue.isEmpty) return
        val session = sessionOf(last.account) ?: return
        _state.update {
            it.copy(queue = PlayQueue().start(last.queue, last.index), account = session.account.id, positionMs = last.positionMs, isPlaying = false)
        }
    }

    fun release() = engine?.release()

    companion object {
        const val NO_VLC = "Sine plays through VLC, which isn't installed here. Install VLC, then open Sine again."
        private const val TICK_MS = 500L
        private const val RESTART_MS = 3_000L
        private const val MAX_KEPT = 2_000
    }
}
