package app.sine.core.color

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/**
 * The accent taken from what's on screen (#39): the cover's own hues, at
 * Sine's brightness, so ink on it and it on the ground read the same as
 * the blue they stand in for. Colours that mean something fixed (Fault,
 * Cosine and Review) never come from here.
 */
object Accents {
    /** Two hues, in degrees: the cover's strongest, and a second for the gradient's far end. */
    data class Hues(val main: Float, val far: Float)

    /** An accent in three strengths, ARGB: the fill, a lighter top and a deep edge, as icons are lit. */
    data class Accent(val fill: Int, val far: Int, val light: Int, val deep: Int)

    private const val BINS = 24
    private const val BIN = 360f / BINS

    /**
     * The hues of [pixels] (ARGB), weighted by how colourful each one is.
     * Null when there's next to no colour: black and white art keeps the blue.
     */
    fun hues(pixels: IntArray): Hues? {
        if (pixels.isEmpty()) return null
        val weight = FloatArray(BINS)
        val sumX = FloatArray(BINS)
        val sumY = FloatArray(BINS)
        var total = 0f
        for (p in pixels) {
            val (h, s, v) = hsv(p)
            if (s < 0.2f || v < 0.15f) continue
            val w = s * v
            val b = (h / BIN).toInt().coerceIn(0, BINS - 1)
            weight[b] += w
            val rad = Math.toRadians(h.toDouble())
            sumX[b] += (w * kotlin.math.cos(rad)).toFloat()
            sumY[b] += (w * kotlin.math.sin(rad)).toFloat()
            total += w
        }
        if (total / pixels.size < 0.04f) return null
        // A bin with its neighbours, so a hue on a boundary isn't split in two.
        val near = FloatArray(BINS) { weight[(it + BINS - 1) % BINS] * 0.5f + weight[it] + weight[(it + 1) % BINS] * 0.5f }
        val main = near.indices.maxBy { near[it] }
        fun hueOf(b: Int): Float {
            var x = 0f
            var y = 0f
            for (d in -1..1) { val i = (b + d + BINS) % BINS; x += sumX[i]; y += sumY[i] }
            return ((Math.toDegrees(kotlin.math.atan2(y.toDouble(), x.toDouble())).toFloat() + 360f) % 360f)
        }
        val mainHue = hueOf(main)
        // The far end: another hue the cover really has, else a neighbour of the first.
        val far = near.indices
            .filter { distance(it * BIN + BIN / 2, mainHue) >= 45f && near[it] >= near[main] * 0.25f }
            .maxByOrNull { near[it] }
        return Hues(mainHue, far?.let { toward(mainHue, hueOf(it)) } ?: ((mainHue + 30f) % 360f))
    }

    /**
     * The far end: [to] when it's near, else [STEP] degrees from [from]
     * toward it. Opposite hues blend through grey; neighbours stay colour.
     */
    private fun toward(from: Float, to: Float): Float {
        if (distance(from, to) <= STEP) return to
        val clockwise = (to - from + 360f) % 360f <= 180f
        return (from + if (clockwise) STEP else -STEP + 360f) % 360f
    }

    private const val STEP = 40f

    /** The accent for [hues], at the brightness of Sine's blue. */
    fun accent(hues: Hues): Accent = Accent(
        fill = atLuminance(hues.main, FILL),
        far = atLuminance(hues.far, FILL),
        light = atLuminance(hues.main, LIGHT, saturation = 0.9f),
        deep = atLuminance(hues.main, DEEP),
    )

    // The blue's own: #53A4FF, and its icon's lit top and deep edge.
    private const val FILL = 0.355f
    private const val LIGHT = 0.66f
    private const val DEEP = 0.055f

    /** The colour of [hue] whose relative luminance is [target]: found by halving. */
    fun atLuminance(hue: Float, target: Float, saturation: Float = 0.85f): Int {
        var lo = 0f
        var hi = 1f
        repeat(24) {
            val mid = (lo + hi) / 2
            if (luminance(hsl(hue, saturation, mid)) < target) lo = mid else hi = mid
        }
        return hsl(hue, saturation, (lo + hi) / 2)
    }

    fun luminance(argb: Int): Float {
        fun c(v: Int): Float { val s = v / 255f; return if (s <= 0.04045f) s / 12.92f else ((s + 0.055f) / 1.055f).pow(2.4f) }
        return 0.2126f * c(argb shr 16 and 0xFF) + 0.7152f * c(argb shr 8 and 0xFF) + 0.0722f * c(argb and 0xFF)
    }

    fun distance(a: Float, b: Float): Float { val d = abs(a - b) % 360f; return if (d > 180f) 360f - d else d }

    private fun hsv(p: Int): Triple<Float, Float, Float> {
        val r = (p shr 16 and 0xFF) / 255f
        val g = (p shr 8 and 0xFF) / 255f
        val b = (p and 0xFF) / 255f
        val mx = max(r, max(g, b))
        val mn = min(r, min(g, b))
        val d = mx - mn
        val h = when {
            d == 0f -> 0f
            mx == r -> 60f * (((g - b) / d) % 6f)
            mx == g -> 60f * ((b - r) / d + 2f)
            else -> 60f * ((r - g) / d + 4f)
        }
        return Triple((h + 360f) % 360f, if (mx == 0f) 0f else d / mx, mx)
    }

    private fun hsl(h: Float, s: Float, l: Float): Int {
        val c = (1 - abs(2 * l - 1)) * s
        val x = c * (1 - abs((h / 60f) % 2 - 1))
        val m = l - c / 2
        val (r, g, b) = when ((h / 60f).toInt() % 6) {
            0 -> Triple(c, x, 0f)
            1 -> Triple(x, c, 0f)
            2 -> Triple(0f, c, x)
            3 -> Triple(0f, x, c)
            4 -> Triple(x, 0f, c)
            else -> Triple(c, 0f, x)
        }
        fun byte(v: Float) = ((v + m) * 255f).toInt().coerceIn(0, 255)
        return (0xFF shl 24) or (byte(r) shl 16) or (byte(g) shl 8) or byte(b)
    }
}
