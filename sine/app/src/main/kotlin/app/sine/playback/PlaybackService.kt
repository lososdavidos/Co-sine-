package app.sine.playback

import android.app.PendingIntent
import android.content.Intent
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import app.sine.MainActivity
import app.sine.core.play.PlayEvent
import app.sine.core.play.PlayLog
import app.sine.graph
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Owns the player. MediaSessionService gives the media notification, lockscreen
 * controls, Bluetooth metadata and headset buttons (§5.2a) without extra code.
 */
class PlaybackService : MediaSessionService() {
    private var session: MediaSession? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    override fun onCreate() {
        super.onCreate()
        val player = ExoPlayer.Builder(this)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                    .build(),
                /* handleAudioFocus = */ true,
            )
            .setHandleAudioBecomingNoisy(true)
            .setWakeMode(C.WAKE_MODE_NETWORK)
            .build()

        val openApp = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        session = MediaSession.Builder(this, player)
            .setCallback(Callback)
            .setSessionActivity(openApp)
            .build()

        scope.launch { recordPlays(player) }
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session

    override fun onTaskRemoved(rootIntent: Intent?) {
        val player = session?.player
        if (player == null || !player.playWhenReady || player.mediaItemCount == 0) stopSelf()
    }

    override fun onDestroy() {
        scope.cancel()
        session?.run {
            player.release()
            release()
        }
        session = null
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

    /**
     * Items arriving from a MediaController carry no URI (Media3 strips local
     * configuration across the session boundary). Sine passes it in the request
     * metadata and restores it here.
     */
    private object Callback : MediaSession.Callback {
        override fun onAddMediaItems(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            mediaItems: MutableList<MediaItem>,
        ): ListenableFuture<MutableList<MediaItem>> = Futures.immediateFuture(
            mediaItems.map { item ->
                val uri = item.requestMetadata.mediaUri
                if (uri == null) item else item.buildUpon().setUri(uri).build()
            }.toMutableList()
        )
    }

    private companion object {
        const val TICK_MS = 1_000L
    }
}
