package app.sine.playback

import androidx.media3.common.C
import androidx.media3.common.Player
import kotlinx.coroutines.delay

/**
 * The prefetch (§2.5): on a network that isn't metered, the next few songs
 * are kept ahead of playing them, so a tunnel, a car park or a train
 * doesn't stop the music. On mobile data it waits, as downloads do: a
 * hotspot counts as metered. Off in Settings, or with nothing to be kept,
 * it does nothing.
 */
class Prefetcher(
    private val player: Player,
    /** Allowed now: on in Settings, a cache to keep songs in, and a network that isn't metered. */
    private val allowed: () -> Boolean,
) {
    suspend fun run() {
        while (true) {
            delay(CHECK_MS)
            if (!allowed() || !player.isPlaying) continue
            for ((uri, key) in upcoming()) {
                if (!allowed()) break
                if (StreamCache.has(key)) continue
                StreamCache.prefetch(uri, key, allowed)
            }
        }
    }

    /** The next songs in the order they'll play, the ones streamed, with the keys they're kept under. */
    private fun upcoming(): List<Pair<android.net.Uri, String>> {
        val timeline = player.currentTimeline
        if (timeline.isEmpty) return emptyList()
        val mode = if (player.repeatMode == Player.REPEAT_MODE_ALL) Player.REPEAT_MODE_ALL else Player.REPEAT_MODE_OFF
        val out = ArrayList<Pair<android.net.Uri, String>>()
        var i = player.currentMediaItemIndex
        repeat(AHEAD) {
            i = timeline.getNextWindowIndex(i, mode, player.shuffleModeEnabled)
            if (i == C.INDEX_UNSET) return out
            val item = player.getMediaItemAt(i)
            val uri = item.localConfiguration?.uri ?: return@repeat
            if (uri.scheme == "http" || uri.scheme == "https") out += uri to item.mediaId
        }
        return out
    }

    private companion object {
        const val CHECK_MS = 5_000L
        /** How far ahead: enough for a stretch without signal, not the whole queue. */
        const val AHEAD = 3
    }
}
