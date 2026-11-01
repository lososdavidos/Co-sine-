package app.sine.core.play

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * Crossfade (Q69): when it's on, a song fades into the next over a few
 * seconds. It's either that or gapless, never both: songs from one album
 * run into each other as they were made, a live record or a mix with no
 * dip between them, so they're never faded.
 */
object Crossfade {
    /**
     * Whether the fade into the next song starts now: it's on, there is a
     * next song from another album, and what's left of this one is the
     * fade's length. A song shorter than two fades isn't faded at all.
     */
    fun startsNow(
        seconds: Int,
        positionMs: Long,
        durationMs: Long,
        hasNext: Boolean,
        sameAlbum: Boolean,
    ): Boolean {
        if (seconds <= 0 || !hasNext || sameAlbum || durationMs <= 0) return false
        val fadeMs = seconds * 1000L
        if (durationMs < fadeMs * 2) return false
        return durationMs - positionMs in 1..fadeMs
    }

    /**
     * How loud each song is [fraction] of the way through the fade: equal
     * power, so the two together don't dip in the middle as a straight
     * line's would. First the song going, then the song coming.
     */
    fun gains(fraction: Float): Pair<Float, Float> {
        val f = fraction.coerceIn(0f, 1f) * (PI / 2)
        return cos(f).toFloat() to sin(f).toFloat()
    }

    /** Two songs are from one album when both name it and it's the same, by the same artist. */
    fun sameAlbum(albumA: String?, artistA: String?, albumB: String?, artistB: String?): Boolean =
        !albumA.isNullOrBlank() && albumA == albumB && artistA == artistB
}
