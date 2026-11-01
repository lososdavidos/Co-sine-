package app.sine.core

import app.sine.core.play.Crossfade
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CrossfadeTest {
    @Test
    fun offMeansNever() {
        assertFalse(Crossfade.armsNow(0, 185_000, 200_000, hasNext = true, sameAlbum = false))
    }

    @Test
    fun itsEndIsLoadedAheadOfTheFade() {
        // A 6 s fade from 194 s: loaded from 184 s, not before, and not later than 193 s.
        assertFalse(Crossfade.armsNow(6, 183_999, 200_000, hasNext = true, sameAlbum = false))
        assertTrue(Crossfade.armsNow(6, 184_000, 200_000, hasNext = true, sameAlbum = false))
        assertTrue(Crossfade.armsNow(6, 192_999, 200_000, hasNext = true, sameAlbum = false))
        // Seeked into the last seconds: too late to load, played out unfaded rather than cut.
        assertFalse(Crossfade.armsNow(6, 193_000, 200_000, hasNext = true, sameAlbum = false))
        assertFalse(Crossfade.armsNow(6, 196_000, 200_000, hasNext = true, sameAlbum = false))
        assertEquals(194_000, Crossfade.fadeFrom(6, 200_000))
    }

    @Test
    fun oneAlbumStaysGapless() {
        assertFalse(Crossfade.armsNow(6, 188_000, 200_000, hasNext = true, sameAlbum = true))
        assertTrue(Crossfade.sameAlbum("Tides", "Skeler", "Tides", "Skeler"))
        assertFalse(Crossfade.sameAlbum("Greatest Hits", "A", "Greatest Hits", "B"))
        assertFalse(Crossfade.sameAlbum("", "A", "", "A"))
    }

    @Test
    fun theLastSongAndShortOnesAreNotFaded() {
        assertFalse(Crossfade.armsNow(6, 188_000, 200_000, hasNext = false, sameAlbum = false))
        assertFalse(Crossfade.armsNow(6, 0, 11_000, hasNext = true, sameAlbum = false))
        assertFalse(Crossfade.armsNow(6, 0, 0, hasNext = true, sameAlbum = false))
    }

    @Test
    fun progressFollowsTheSongGoing() {
        assertEquals(0f, Crossfade.progress(194_000, 194_000, 200_000))
        assertEquals(0.5f, Crossfade.progress(197_000, 194_000, 200_000))
        assertEquals(1f, Crossfade.progress(205_000, 194_000, 200_000))
        assertEquals(0f, Crossfade.progress(190_000, 194_000, 200_000))
    }

    @Test
    fun equalPowerHoldsTheLoudnessUp() {
        assertEquals(1f to 0f, Crossfade.gains(0f).let { (a, b) -> Math.round(a * 1000) / 1000f to Math.round(b * 1000) / 1000f })
        val (a, b) = Crossfade.gains(0.5f)
        assertEquals(1.0, (a * a + b * b).toDouble(), 1e-4)
        assertEquals(0f to 1f, Crossfade.gains(2f).let { (x, y) -> Math.round(x * 1000) / 1000f to Math.round(y * 1000) / 1000f })
    }
}
