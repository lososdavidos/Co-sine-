package app.sine.playback

import android.app.PendingIntent
import android.content.Intent
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaLibraryService.MediaLibrarySession
import androidx.media3.session.MediaSession
import app.sine.MainActivity
import app.sine.core.play.PlayEvent
import app.sine.core.play.PlayLog
import app.sine.core.play.shuffledFrom
import app.sine.graph
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Owns the player. MediaLibraryService gives the media notification, lockscreen
 * controls, Bluetooth metadata and headset buttons (§5.2a) without extra code,
 * and Android Auto its library to browse (CarLibrary).
 */
class PlaybackService : MediaLibraryService() {
    private var session: MediaLibrarySession? = null
    private var tail: ExoPlayer? = null
    private var crossfader: Crossfader? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    @OptIn(UnstableApi::class)
    override fun onCreate() {
        super.onCreate()
        // Streams are waited for as long as Sine's other calls (AppGraph),
        // not ExoPlayer's 8 s: a far-off or busy Cosine is slow, not gone.
        val http = DefaultHttpDataSource.Factory()
            .setConnectTimeoutMs(8_000)
            .setReadTimeoutMs(30_000)
        val sources = DefaultMediaSourceFactory(StreamCache.dataSource(DefaultDataSource.Factory(this, http)))
        fun build(focus: Boolean) = ExoPlayer.Builder(this)
            // Streams are kept as they play, up to the size set (StreamCache).
            .setMediaSourceFactory(sources)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                    .build(),
                /* handleAudioFocus = */ focus,
            )
            .setHandleAudioBecomingNoisy(focus)
            .setWakeMode(C.WAKE_MODE_NETWORK)
            .build()
        val player = build(focus = true)
        // The end of a song, fading out under the next (Crossfader): the player holds the focus for both.
        val tail = build(focus = false).also { this.tail = it }
        // Songs added to the queue go next, shuffled or not (QueueOrder, #37).
        player.setShuffleOrder(QueueOrder(IntArray(0)))
        // Shuffle turned on mid-queue starts its order with what's playing,
        // then the songs added to the queue, so everything else still plays
        // after them (shuffledFrom).
        player.addListener(object : Player.Listener {
            override fun onShuffleModeEnabledChanged(shuffleModeEnabled: Boolean) {
                if (shuffleModeEnabled && player.mediaItemCount > 1) {
                    val current = player.currentMediaItemIndex
                    // Off until now, so the list is the order they played in.
                    val queued = (current + 1 until player.mediaItemCount).takeWhile { player.getMediaItemAt(it).isQueued }
                    player.setShuffleOrder(QueueOrder(shuffledFrom(current, player.mediaItemCount, queued)))
                }
            }
        })

        val openApp = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        session = MediaLibrarySession.Builder(this, player, CarLibrary(applicationContext, scope))
            .setSessionActivity(openApp)
            .build()

        scope.launch { recordPlays(player) }
        val settings = graph.settings
        val fader = Crossfader(player, tail, seconds = { settings.state.value.crossfadeSec }, held = { graph.jam.jam.value != null })
        crossfader = fader
        scope.launch { fader.run() }
        // Turned off mid-fade: the fade stops, at full volume.
        scope.launch { settings.state.collect { if (it.crossfadeSec == 0) fader.cancel() } }
        scope.launch {
            Prefetcher(player) {
                val net = graph.network.state.value
                settings.state.value.prefetch && StreamCache.max > 0 && net.connected && !net.metered
            }.run()
        }
        // The home screen widget follows what plays (§6.19).
        player.addListener(object : Player.Listener {
            override fun onEvents(player: Player, events: Player.Events) = NowPlayingWidget.update(applicationContext, player)
        })
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaLibrarySession? = session

    override fun onTaskRemoved(rootIntent: Intent?) {
        val player = session?.player
        if (player == null || !player.playWhenReady || player.mediaItemCount == 0) stopSelf()
    }

    override fun onDestroy() {
        scope.cancel()
        crossfader?.cancel()
        tail?.release()
        tail = null
        session?.run {
            player.release()
            release()
        }
        session = null
        NowPlayingWidget.update(applicationContext, null)
        super.onDestroy()
    }

    /**
     * Records a play once enough of a track has actually been heard, online or
     * not (Q37, Q70). Submission happens separately, whenever the server is there.
     */
    private suspend fun recordPlays(player: Player) {
        var key: String? = null
        var listenedMs = 0L
        var startedAt = 0L
        var recorded = false
        while (true) {
            delay(TICK_MS)
            val item = player.currentMediaItem
            val itemKey = item?.let { "${player.currentMediaItemIndex}:${it.mediaId}" }
            if (itemKey != key) {
                key = itemKey
                listenedMs = 0
                startedAt = System.currentTimeMillis()
                recorded = false
            }
            if (item == null || !player.isPlaying) continue
            listenedMs += TICK_MS
            if (!recorded && PlayLog.counts(listenedMs, player.duration.coerceAtLeast(0))) {
                recorded = true
                val account = graph.activeSession.value ?: continue
                account.plays.record(PlayEvent(item.mediaId, startedAt))
                scope.launch(Dispatchers.IO) { runCatching { account.flushPlays() } }
            }
        }
    }

    private companion object {
        const val TICK_MS = 1_000L
    }
}
