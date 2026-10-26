package app.sine.playback

import android.content.ComponentName
import android.content.Context
import android.net.Uri
import androidx.core.content.ContextCompat
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
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
    val queue: List<MediaItem> = emptyList(),
    val index: Int = -1,
    val shuffle: Boolean = false,
    /** [Player.REPEAT_MODE_OFF], [Player.REPEAT_MODE_ALL] (the queue, round again) or [Player.REPEAT_MODE_ONE] (this song). */
    val repeat: Int = Player.REPEAT_MODE_OFF,
)

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
        val f = MediaController.Builder(context, token).buildAsync()
        future = f
        f.addListener({
            val c = runCatching { f.get() }.getOrNull() ?: run { future = null; return@addListener }
            controller = c
            c.addListener(listener)
            pending.forEach { it(c) }
            pending.clear()
            refresh()
        }, ContextCompat.getMainExecutor(context))
    }

    fun disconnect() {
        controller?.removeListener(listener)
        future?.let { MediaController.releaseFuture(it) }
        future = null
        controller = null
    }

    private fun withController(action: (MediaController) -> Unit) {
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
            c.setMediaItems(items.map { it.second }, if (shuffle) C.INDEX_UNSET else index, startMs)
            c.prepare()
            c.play()
        }
    }

    fun togglePlay() = withController { if (it.isPlaying) it.pause() else it.play() }
    fun next() = withController { it.seekToNextMediaItem() }
    fun previous() = withController { it.seekToPrevious() }
    fun seekTo(ms: Long) = withController { it.seekTo(ms) }
    fun skipTo(index: Int) = withController { it.seekToDefaultPosition(index) }
    fun toggleShuffle() = withController { it.shuffleModeEnabled = !it.shuffleModeEnabled }

    /** Repeat goes off, the whole queue, this song, and off again. */
    fun cycleRepeat() = withController {
        it.repeatMode = when (it.repeatMode) {
            Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL
            Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE
            else -> Player.REPEAT_MODE_OFF
        }
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

    /** Takes the songs at [indices] out of the queue. Taking out the one playing moves on to the next. */
    fun removeFromQueue(indices: Collection<Int>) = withController { c ->
        for (i in indices.sortedDescending()) if (i in 0 until c.mediaItemCount) c.removeMediaItem(i)
    }
    fun positionMs(): Long = controller?.currentPosition ?: 0

    private fun refresh() {
        val c = controller ?: return
        _state.value = PlayerState(
            current = c.currentMediaItem,
            isPlaying = c.isPlaying,
            durationMs = c.duration.takeIf { it != C.TIME_UNSET }?.coerceAtLeast(0L) ?: 0L,
            queue = (0 until c.mediaItemCount).map { c.getMediaItemAt(it) },
            index = c.currentMediaItemIndex,
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
