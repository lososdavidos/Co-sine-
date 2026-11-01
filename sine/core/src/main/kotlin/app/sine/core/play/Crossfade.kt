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
     * Whether to get the fade ready now: it's on, there is a next song from
     * another album, and the fade is due within [ARM_AHEAD_MS]. The end of
     * this song is loaded in the second player ahead of time, so the fade
     * starts with it already playing: loaded at the moment it's needed, the
     * song went silent while it did, a cut. Too close to the fade to load in
     * time (a seek into the last seconds), and this song plays out unfaded.
     * A song shorter than two fades isn't faded at all.
     */
    fun armsNow(
        seconds: Int,
        positionMs: Long,
        durationMs: Long,
        hasNext: Boolean,
        sameAlbum: Boolean,
    ): Boolean {
        if (seconds <= 0 || !hasNext || sameAlbum || durationMs <= 0) return false
        val fadeMs = seconds * 1000L
        if (durationMs < fadeMs * 2) return false
        return durationMs - positionMs in (fadeMs + MIN_LEAD_MS + 1)..(fadeMs + ARM_AHEAD_MS)
    }

    /** Where in a song of [durationMs] the fade begins. */
    fun fadeFrom(seconds: Int, durationMs: Long): Long = (durationMs - seconds * 1000L).coerceAtLeast(0)

    /** How far through the fade the song going is, at [positionMs] of it: 0 to 1. */
    fun progress(positionMs: Long, fromMs: Long, durationMs: Long): Float {
        val length = durationMs - fromMs
        if (length <= 0) return 1f
        return ((positionMs - fromMs).toFloat() / length).coerceIn(0f, 1f)
    }

    /** How long before the fade its song's end is loaded. */
    const val ARM_AHEAD_MS = 10_000L

    /** The least time loading it may take: closer than this, no fade. */
    const val MIN_LEAD_MS = 1_000L

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
