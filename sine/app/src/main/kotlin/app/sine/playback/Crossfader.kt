package app.sine.playback

import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import app.sine.core.play.Crossfade
import kotlinx.coroutines.delay

/**
 * Crossfade (Q69): the last seconds of a song go on in [tail], fading out,
 * while [player] has moved on to the next, fading in. The session only
 * ever sees [player], so the notification, the car and Now playing show
 * the next song from the moment it starts to rise. Off, the default, and
 * nothing here runs: songs play gapless.
 */
class Crossfader(
    private val player: ExoPlayer,
    private val tail: ExoPlayer,
    /** The fade's length now, in seconds; 0 is off. */
    private val seconds: () -> Int,
    /** Held to another clock (a jam, §4C): no fading. */
    private val held: () -> Boolean,
) {
    private var fadeMs = 0L
    private var doneMs = 0L

    suspend fun run() {
        while (true) {
            delay(TICK_MS)
            if (fadeMs > 0) step() else maybeStart()
        }
    }

    private fun maybeStart() {
        if (!player.isPlaying || held() || player.repeatMode == Player.REPEAT_MODE_ONE) return
        val next = player.nextMediaItemIndex.takeIf { it != androidx.media3.common.C.INDEX_UNSET } ?: return
        val current = player.currentMediaItem ?: return
        val following = player.getMediaItemAt(next)
        val same = Crossfade.sameAlbum(
            current.mediaMetadata.albumTitle?.toString(), current.mediaMetadata.artist?.toString(),
            following.mediaMetadata.albumTitle?.toString(), following.mediaMetadata.artist?.toString(),
        )
        val position = player.currentPosition
        val duration = player.duration
        if (!Crossfade.startsNow(seconds(), position, duration, hasNext = true, sameAlbum = same)) return
        // What's left of this song goes on in the tail; the player moves on, silent, and rises.
        tail.setMediaItem(current, position)
        tail.volume = 1f
        tail.prepare()
        tail.play()
        player.volume = 0f
        player.seekToNextMediaItem()
        fadeMs = (duration - position).coerceAtLeast(TICK_MS)
        doneMs = 0
    }

    private fun step() {
        // Paused mid-fade: both wait.
        if (!player.isPlaying) {
            if (tail.isPlaying) tail.pause()
            if (player.playbackState == Player.STATE_IDLE || player.mediaItemCount == 0) finish()
            return
        }
        if (!tail.isPlaying && tail.playbackState == Player.STATE_READY) tail.play()
        doneMs += TICK_MS
        val (out, inn) = Crossfade.gains(doneMs.toFloat() / fadeMs)
        tail.volume = out
        player.volume = inn
        if (doneMs >= fadeMs || tail.playbackState == Player.STATE_ENDED) finish()
    }

    private fun finish() {
        tail.stop()
        tail.clearMediaItems()
        player.volume = 1f
        fadeMs = 0
    }

    /** Stops a fade at once, at full volume: the player let go, or the setting turned off. */
    fun cancel() {
        if (fadeMs > 0) finish()
    }

    private companion object {
        const val TICK_MS = 50L
    }
}
