package app.sine.playback

import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import app.sine.core.play.Crossfade
import app.sine.core.play.CrossfadePlan
import app.sine.core.play.CrossfadePlan.Action
import kotlinx.coroutines.delay

/**
 * Crossfade (Q69): the last seconds of a song go on in [tail], fading out,
 * while [player] has moved on to the next, fading in. The session only
 * ever sees [player], so the notification, the car and Now playing show
 * the next song from the moment it starts to rise. Off, the default, and
 * nothing here runs: songs play gapless.
 *
 * What to do when is [CrossfadePlan]'s: the song's end is loaded in the
 * tail ahead of time, starts silent under the player at the fade's start,
 * and the two swap only once it's truly playing, so the fade never starts
 * with a cut. This does what the plan says, and tells it how things are.
 */
class Crossfader(
    private val player: ExoPlayer,
    private val tail: ExoPlayer,
    /** The fade's length now, in seconds; 0 is off. */
    private val seconds: () -> Int,
    /** Held to another clock (a jam, §4C): no fading. */
    private val held: () -> Boolean,
) {
    private val plan = CrossfadePlan()

    suspend fun run() {
        while (true) {
            val now = now()
            delay(if (plan.close(now)) FINE_TICK_MS else TICK_MS)
            apply(plan.next(now()))
        }
    }

    private fun now(): CrossfadePlan.Now {
        val next = player.nextMediaItemIndex
        val current = player.currentMediaItem
        val following = if (next != C.INDEX_UNSET) player.getMediaItemAt(next) else null
        val same = current != null && following != null && Crossfade.sameAlbum(
            current.mediaMetadata.albumTitle?.toString(), current.mediaMetadata.artist?.toString(),
            following.mediaMetadata.albumTitle?.toString(), following.mediaMetadata.artist?.toString(),
        )
        return CrossfadePlan.Now(
            seconds = seconds(),
            index = player.currentMediaItemIndex,
            positionMs = player.currentPosition,
            durationMs = player.duration.takeIf { it != C.TIME_UNSET } ?: 0,
            playing = player.isPlaying,
            hasNext = following != null,
            sameAlbum = same,
            repeatOne = player.repeatMode == Player.REPEAT_MODE_ONE,
            held = held() || player.playerError != null,
            tailReady = tail.playbackState == Player.STATE_READY,
            tailPlaying = tail.isPlaying,
            tailPositionMs = tail.currentPosition,
            tailEnded = tail.playbackState == Player.STATE_ENDED,
            clockMs = System.currentTimeMillis(),
        )
    }

    private fun apply(actions: List<Action>) {
        for (a in actions) when (a) {
            is Action.Arm -> {
                // The song's end, loaded and waiting at the fade's start, silent.
                val item = player.currentMediaItem ?: continue
                tail.volume = 0f
                tail.playWhenReady = false
                tail.setMediaItem(item, a.fromMs)
                tail.prepare()
            }
            Action.StartTail -> tail.play()
            Action.Swap -> {
                tail.volume = 1f
                player.volume = 0f
                player.seekToNextMediaItem()
            }
            is Action.Levels -> {
                tail.volume = a.tail
                player.volume = a.player
            }
            Action.PauseTail -> tail.pause()
            Action.ResumeTail -> tail.play()
            Action.Drop -> {
                tail.stop()
                tail.clearMediaItems()
                player.volume = 1f
            }
        }
    }

    /** Stops at once, at full volume: the player let go, or the setting turned off. */
    fun cancel() = apply(plan.cancel())

    private companion object {
        const val TICK_MS = 50L
        /** Near the fade's start, and while the tail starts: watched closely, so the swap is on time. */
        const val FINE_TICK_MS = 10L
    }
}
