package app.sine.core

import app.sine.core.color.Accents
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AccentsTest {
    private fun fill(n: Int, argb: Int) = IntArray(n) { argb }

    @Test
    fun aMostlyRedCoverWithSomeTealGivesRedThenAStepTowardTeal() {
        // Teal is opposite red: a gradient between them goes grey, so the
        // far end stops 40 degrees along the way.
        val pixels = fill(600, 0xFFD02020.toInt()) + fill(250, 0xFF20A0A0.toInt()) + fill(400, 0xFF101010.toInt())
        val h = Accents.hues(pixels)!!
        assertTrue(Accents.distance(h.main, 0f) < 8f, "$h")
        assertEquals(40f, Accents.distance(h.main, h.far), 1f)
    }

    @Test
    fun aNearSecondHueIsTakenAsItIs() {
        val pixels = fill(600, 0xFF2040E0.toInt()) + fill(250, 0xFF8020E0.toInt())
        val h = Accents.hues(pixels)!!
        assertTrue(Accents.distance(h.far, 270f) < 8f, "$h")
    }

    @Test
    fun oneHueOnlyReachesForANeighbour() {
        val h = Accents.hues(fill(100, 0xFF2050E0.toInt()))!!
        assertEquals(30f, Accents.distance(h.main, h.far), 1f)
    }

    @Test
    fun blackAndWhiteKeepsTheBlue() {
        assertNull(Accents.hues(fill(50, 0xFF000000.toInt()) + fill(50, 0xFFFFFFFF.toInt()) + fill(50, 0xFF808080.toInt())))
        assertNull(Accents.hues(IntArray(0)))
    }

    @Test
    fun everyAccentIsAsBrightAsTheBlueSoInkReadsOnIt() {
        val blue = Accents.luminance(0xFF53A4FF.toInt())
        for (hue in 0 until 360 step 15) {
            val a = Accents.accent(Accents.Hues(hue.toFloat(), (hue + 40f) % 360f))
            assertEquals(blue, Accents.luminance(a.fill), 0.02f, "hue $hue")
            // Ink (#0A0B0C) on it: at least 7:1, as on the blue.
            val contrast = (Accents.luminance(a.fill) + 0.05f) / (Accents.luminance(0xFF0A0B0C.toInt()) + 0.05f)
            assertTrue(contrast >= 7f, "hue $hue: $contrast")
            assertTrue(Accents.luminance(a.deep) < Accents.luminance(a.fill) && Accents.luminance(a.fill) < Accents.luminance(a.light))
        }
    }
}
