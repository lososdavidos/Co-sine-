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
)

/** The UI's handle on [PlaybackService], through a MediaController. */
class PlayerConnection(private val context: Context) {
    private var future: ListenableFuture<MediaController>? = null
    private var controller: MediaController? = null
    private val pending = mutableListOf<(MediaController) -> Unit>()

    private val _state = MutableStateFlow(PlayerState())
    val state: StateFlow<PlayerState> = _state.asStateFlow()

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
    fun play(session: AccountSession, tracks: List<Track>, startIndex: Int = 0, shuffle: Boolean = false) {
        val start = tracks.getOrNull(startIndex)
        val items = tracks.mapNotNull { t -> mediaItem(session, t)?.let { t to it } }
        if (items.isEmpty()) return
        val index = items.indexOfFirst { it.first == start }.coerceAtLeast(0)
        withController { c ->
            c.shuffleModeEnabled = shuffle
            c.setMediaItems(items.map { it.second }, if (shuffle) C.INDEX_UNSET else index, 0)
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
