package app.sine.playback

import android.content.ComponentName
import android.content.Context
import android.net.Uri
import androidx.core.content.ContextCompat
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import app.sine.core.model.Track
import app.sine.core.play.PlaySource
import app.sine.data.AccountSession
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class PlayerState(
    val current: MediaItem? = null,
    val isPlaying: Boolean = false,
    val durationMs: Long = 0,
    /** The queue in the order it plays: shuffled while shuffle is on. */
    val queue: List<Queued> = emptyList(),
    /** Where [current] is in [queue]. */
    val index: Int = -1,
    val shuffle: Boolean = false,
    /** [Player.REPEAT_MODE_OFF], [Player.REPEAT_MODE_ALL] (the queue, round again) or [Player.REPEAT_MODE_ONE] (this song). */
    val repeat: Int = Player.REPEAT_MODE_OFF,
)

/** A song in the queue, and its place in the player's own list. */
data class Queued(val item: MediaItem, val at: Int)

/**
 * What played last, for Home's Now playing tile when nothing is (§6.4):
 * whose song it was, and how far it got, to pick up from there.
 */
data class LastPlayed(
    val account: String,
    val trackId: String,
    val title: String,
    val artist: String,
    val art: String?,
    val positionMs: Long,
)

/** The UI's handle on [PlaybackService], through a MediaController. */
class PlayerConnection(private val context: Context) {
    private var future: ListenableFuture<MediaController>? = null
    private var controller: MediaController? = null
    private val pending = mutableListOf<(MediaController) -> Unit>()

    private val _state = MutableStateFlow(PlayerState())
    val state: StateFlow<PlayerState> = _state.asStateFlow()

    private val prefs = context.getSharedPreferences("player", Context.MODE_PRIVATE)
    /** The account whose songs are playing: the last one to start something. */
    private var playingFor: String? = prefs.getString("account", null)
    private val _last = MutableStateFlow(readLast())
    val last: StateFlow<LastPlayed?> = _last.asStateFlow()

    private val listener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) = refresh()
    }

    fun connect() {
        if (future != null) return
        val token = SessionToken(context, ComponentName(context, PlaybackService::class.java))
        val f = MediaController.Builder(context, token)
            .setListener(object : MediaController.Listener {
                override fun onDisconnected(controller: MediaController) = dropped(controller)
            })
            .buildAsync()
        future = f
        f.addListener({
            // Released or replaced while it connected (the app stopped and
            // started again): not this one's to keep. Kept, a released
            // controller says nothing is playing while the service plays on.
            if (future !== f) return@addListener
            val c = runCatching { f.get() }.getOrNull()?.takeIf { it.isConnected } ?: run { future = null; return@addListener }
            controller = c
            c.addListener(listener)
            c.repeatMode = prefs.getInt("repeat", Player.REPEAT_MODE_OFF)
            pending.forEach { it(c) }
            pending.clear()
            refresh()
        }, ContextCompat.getMainExecutor(context))
    }

    fun disconnect() {
        val f = future ?: return
        controller?.removeListener(listener)
        future = null
        controller = null
        MediaController.releaseFuture(f)
    }

    /**
     * The session went away under the controller: the service stopped, or
     * was stopped. Nothing plays any more; the next action connects again.
     */
    private fun dropped(c: MediaController) {
        if (controller !== c) return
        c.removeListener(listener)
        future?.let { MediaController.releaseFuture(it) }
        future = null
        controller = null
        _state.value = PlayerState()
    }

    private fun withController(action: (MediaController) -> Unit) {
        controller?.let { if (!it.isConnected) dropped(it) }
        val c = controller
        if (c != null) action(c) else {
            pending += action
            connect()
        }
    }

    /**
     * Plays [tracks] from [startIndex]. Tracks that cannot play right now (offline
     * and not downloaded) are left out of the queue rather than failing mid-way.
     */
    fun play(session: AccountSession, tracks: List<Track>, startIndex: Int = 0, shuffle: Boolean = false, startMs: Long = 0) {
        val start = tracks.getOrNull(startIndex)
        val items = tracks.mapNotNull { t -> mediaItem(session, t)?.let { t to it } }
        if (items.isEmpty()) return
        val index = items.indexOfFirst { it.first == start }.coerceAtLeast(0)
        playingFor = session.account.id
        prefs.edit().putString("account", session.account.id).apply()
        withController { c ->
            c.shuffleModeEnabled = shuffle
            // Shuffled, the queue starts at the first song of its shuffled
            // order, from the top. (C.INDEX_UNSET would keep the song before's
            // place and position: the new one started 0:52 in, and midway
            // through the order, so shuffle stopped early.)
            if (shuffle) c.setMediaItems(items.map { it.second }, /* resetPosition = */ true)
            else c.setMediaItems(items.map { it.second }, index, startMs)
            c.prepare()
            c.play()
        }
    }

    fun togglePlay() = withController { if (it.isPlaying) it.pause() else it.play() }
    fun next() = withController { it.seekToNextMediaItem() }
    fun previous() = withController { it.seekToPrevious() }
    fun seekTo(ms: Long) = withController { it.seekTo(ms) }
    /** Plays the queue's song at [at], its place in the player's list ([Queued.at]). */
    fun skipTo(at: Int) = withController { it.seekToDefaultPosition(at) }
    fun toggleShuffle() = withController { it.shuffleModeEnabled = !it.shuffleModeEnabled }

    /** Repeat off, then the whole queue, then the one song. */
    fun toggleRepeat() = withController {
        it.repeatMode = when (it.repeatMode) {
            Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL
            Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE
            else -> Player.REPEAT_MODE_OFF
        }
        prefs.edit().putInt("repeat", it.repeatMode).apply()
    }

    /**
     * Puts [tracks] at the end of the queue, after what's already there; with
     * nothing queued, plays them. Returns how many could be queued: a song
     * that can't play right now is left out, as in [play].
     */
    fun addToQueue(session: AccountSession, tracks: List<Track>): Int {
        val items = tracks.mapNotNull { mediaItem(session, it) }
        if (items.isEmpty()) return 0
        withController { c ->
            if (c.mediaItemCount > 0) return@withController c.addMediaItems(items)
            playingFor = session.account.id
            prefs.edit().putString("account", session.account.id).apply()
            c.setMediaItems(items)
            c.prepare()
            c.play()
        }
        return items.size
    }

    /**
     * Takes the songs at [at] out of the queue: their places in the player's
     * list ([Queued.at]), not in the order they play. Taking out the one
     * playing moves on to the next.
     */
    fun removeFromQueue(at: Collection<Int>) = withController { c ->
        for (i in at.sortedDescending()) if (i in 0 until c.mediaItemCount) c.removeMediaItem(i)
    }
    fun positionMs(): Long = controller?.currentPosition ?: 0

    private fun refresh() {
        val c = controller ?: return
        val order = playOrder(c.currentTimeline, c.shuffleModeEnabled)
        _state.value = PlayerState(
            current = c.currentMediaItem,
            isPlaying = c.isPlaying,
            durationMs = c.duration.takeIf { it != C.TIME_UNSET }?.coerceAtLeast(0L) ?: 0L,
            queue = order.map { Queued(c.getMediaItemAt(it), it) },
            index = order.indexOf(c.currentMediaItemIndex),
            shuffle = c.shuffleModeEnabled,
            repeat = c.repeatMode,
        )
        val item = c.currentMediaItem ?: return
        val account = playingFor ?: return
        // Every change of state is a moment to remember where it got to: a pause most of all.
        remember(LastPlayed(
            account, item.mediaId, item.mediaMetadata.title?.toString().orEmpty(),
            item.mediaMetadata.artist?.toString().orEmpty(), item.mediaMetadata.artworkUri?.toString(), c.currentPosition,
        ))
    }

    /** The player's list in the order it plays, shuffled or not. */
    private fun playOrder(timeline: Timeline, shuffle: Boolean): List<Int> {
        val out = ArrayList<Int>(timeline.windowCount)
        var i = timeline.getFirstWindowIndex(shuffle)
        while (i != C.INDEX_UNSET && out.size < timeline.windowCount) {
            out += i
            i = timeline.getNextWindowIndex(i, Player.REPEAT_MODE_OFF, shuffle)
        }
        return out
    }

    private fun remember(last: LastPlayed) {
        if (_last.value == last) return
        _last.value = last
        prefs.edit()
            .putString("last.account", last.account)
            .putString("last.track", last.trackId)
            .putString("last.title", last.title)
            .putString("last.artist", last.artist)
            .putString("last.art", last.art)
            .putLong("last.position", last.positionMs)
            .apply()
    }

    private fun readLast(): LastPlayed? {
        val account = prefs.getString("last.account", null) ?: return null
        val track = prefs.getString("last.track", null) ?: return null
        return LastPlayed(
            account, track, prefs.getString("last.title", "").orEmpty(), prefs.getString("last.artist", "").orEmpty(),
            prefs.getString("last.art", null), prefs.getLong("last.position", 0),
        )
    }

    private fun mediaItem(session: AccountSession, track: Track): MediaItem? {
        val uri = when (val source = session.playSource(track)) {
            is PlaySource.Local -> source.location
            is PlaySource.Remote -> source.url
            PlaySource.Unavailable -> return null
        }
        return MediaItem.Builder()
            .setMediaId(track.id)
            .setUri(uri)
            .setRequestMetadata(MediaItem.RequestMetadata.Builder().setMediaUri(Uri.parse(uri)).build())
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(track.title)
                    .setArtist(track.artist)
                    .setAlbumTitle(track.album)
                    .setArtworkUri(session.artworkUri(track.coverArt))
                    .build()
            )
            .build()
    }
}
