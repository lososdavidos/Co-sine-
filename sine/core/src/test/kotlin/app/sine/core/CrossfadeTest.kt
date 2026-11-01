package app.sine.core

import app.sine.core.play.Crossfade
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CrossfadeTest {
    @Test
    fun offMeansNever() {
        assertFalse(Crossfade.startsNow(0, 199_000, 200_000, hasNext = true, sameAlbum = false))
    }

    @Test
    fun startsWhenWhatsLeftIsTheFade() {
        assertFalse(Crossfade.startsNow(6, 193_000, 200_000, hasNext = true, sameAlbum = false))
        assertTrue(Crossfade.startsNow(6, 194_000, 200_000, hasNext = true, sameAlbum = false))
        assertTrue(Crossfade.startsNow(6, 199_999, 200_000, hasNext = true, sameAlbum = false))
        assertFalse(Crossfade.startsNow(6, 200_000, 200_000, hasNext = true, sameAlbum = false))
    }

    @Test
    fun oneAlbumStaysGapless() {
        assertFalse(Crossfade.startsNow(6, 196_000, 200_000, hasNext = true, sameAlbum = true))
        assertTrue(Crossfade.sameAlbum("Tides", "Skeler", "Tides", "Skeler"))
        assertFalse(Crossfade.sameAlbum("Greatest Hits", "A", "Greatest Hits", "B"))
        assertFalse(Crossfade.sameAlbum("", "A", "", "A"))
    }

    @Test
    fun theLastSongAndShortOnesAreNotFaded() {
        assertFalse(Crossfade.startsNow(6, 196_000, 200_000, hasNext = false, sameAlbum = false))
        assertFalse(Crossfade.startsNow(6, 8_000, 11_000, hasNext = true, sameAlbum = false))
        assertFalse(Crossfade.startsNow(6, 0, 0, hasNext = true, sameAlbum = false))
    }

    @Test
    fun equalPowerHoldsTheLoudnessUp() {
        assertEquals(1f to 0f, Crossfade.gains(0f).let { (a, b) -> Math.round(a * 1000) / 1000f to Math.round(b * 1000) / 1000f })
        val (a, b) = Crossfade.gains(0.5f)
        assertEquals(1.0, (a * a + b * b).toDouble(), 1e-4)
        assertEquals(0f to 1f, Crossfade.gains(2f).let { (x, y) -> Math.round(x * 1000) / 1000f to Math.round(y * 1000) / 1000f })
    }
}
