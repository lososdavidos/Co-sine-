package app.sine.ui.phase

import android.content.Context
import android.graphics.Bitmap
import androidx.compose.animation.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.unit.dp
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import app.sine.core.color.Accents
import coil3.imageLoader
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.request.allowHardware
import coil3.toBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The accent follows what's on screen (#39): Sine takes its colour, a
 * gradient of two, from the artwork in view — the page's cover, else the
 * song playing — and goes back to the blue when there's none, or the art
 * is black and white. Colours with a fixed meaning (Fault, and Cosine for
 * the server at work and Review) never change. Every accent is as bright
 * as the blue, so ink reads on it as well (Accents).
 */
object Ambient {
    /** An accent in all its strengths (Accents.Accent). */
    class Colors(
        val fill: Color, val far: Color, val light: Color, val lightFar: Color, val deep: Color,
        val shelf: Color, val shelfLine: Color, val navTop: Color, val navBottom: Color,
    ) {
        internal val all get() = listOf(fill, far, light, lightFar, deep, shelf, shelfLine, navTop, navBottom)
    }

    private fun Accents.Accent.colors() = Colors(
        Color(fill), Color(far), Color(light), Color(lightFar), Color(deep),
        Color(shelf), Color(shelfLine), Color(navTop), Color(navBottom),
    )

    /** Phase's own blue, exact where it was set by hand, the rest worked out as for any hue. */
    val Blue: Colors = Accents.accent(Accents.fixed.first().second).let {
        Colors(
            Color(0xFF53A4FF), Color(0xFF3F8FF0), Color(0xFFB2D6FF), Color(it.lightFar), Color(0xFF0E4683),
            Color(it.shelf), Color(it.shelfLine), Color(it.navTop), Color(it.navBottom),
        )
    }

    /** One of the fixed colours by name (Settings, Appearance); null, the artwork's. */
    fun fixed(name: String?): Colors? {
        val hues = Accents.fixed.firstOrNull { it.first == name }?.second ?: return null
        return if (hues == Accents.fixed.first().second) Blue else Accents.accent(hues).colors()
    }

    private val shown = Blue.all.map { Animatable(it) }
    internal val fill get() = shown[0]
    internal val far get() = shown[1]
    internal val light get() = shown[2]
    internal val lightFar get() = shown[3]
    internal val deep get() = shown[4]
    internal val shelf get() = shown[5]
    internal val shelfLine get() = shown[6]
    internal val navTop get() = shown[7]
    internal val navBottom get() = shown[8]

    /** Eases every strength to [target]. */
    internal suspend fun easeTo(target: Colors) = coroutineScope {
        target.all.forEachIndexed { i, c -> launch { shown[i].animateTo(c, tween(EASE_MS)) } }
    }

    /** Set by Settings: a fixed colour instead of the artwork's. */
    var fixed by mutableStateOf<Colors?>(null)

    /** A source of colour: a page's artwork, or (weakly) the song playing in the shelf. */
    internal class Source(val weak: Boolean) {
        var model by mutableStateOf<Any?>(null)
    }

    // In the order they came: the latest page's art wins, then the latest weak one.
    internal val sources = mutableStateListOf<Source>()

    private val known = object : LinkedHashMap<String, Colors?>(32, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Colors?>?) = size > 64
    }

    /** The colours of [model]'s artwork; null when it has none to give. */
    internal suspend fun colorsOf(context: Context, model: Any): Colors? {
        val key = model.toString()
        synchronized(known) { if (known.containsKey(key)) return known[key] }
        val request = ImageRequest.Builder(context).data(model).size(48).allowHardware(false).build()
        val bitmap = (context.imageLoader.execute(request) as? SuccessResult)?.image?.toBitmap() ?: return null
        val colors = withContext(Dispatchers.Default) { pick(bitmap) }
        synchronized(known) { known[key] = colors }
        return colors
    }

    private fun pick(bitmap: Bitmap): Colors? {
        val pixels = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        return Accents.accent(Accents.hues(pixels) ?: return null).colors()
    }
}

/** The accent as a gradient: for what's filled with it, a pill or a selected row. */
val PhaseColor.SineBrush: Brush get() = Brush.linearGradient(listOf(Sine, SineFar))

/*
 * What wears the accent reads it as it's drawn, never as it's composed: a
 * colour easing in as a page slides repaints these, and rebuilds nothing.
 */

/** Filled with the accent's gradient, in [shape]. */
fun Modifier.accentFill(shape: Shape = RectangleShape): Modifier = drawBehind {
    drawOutline(shape.createOutline(size, layoutDirection, this), Brush.linearGradient(listOf(Ambient.fill.value, Ambient.far.value)))
}

/** The shelf's surface: the accent near black, with a hairline of it round [shape]. */
fun Modifier.shelfSurface(shape: Shape): Modifier = drawWithContent {
    val outline = shape.createOutline(size, layoutDirection, this)
    drawOutline(outline, Ambient.shelf.value)
    drawContent()
    // Drawn on the edge and half clipped away: a 1dp line inside.
    drawOutline(outline, Ambient.shelfLine.value, style = Stroke(2.dp.toPx()))
}

/**
 * Offers [model]'s artwork as the colour of the screen while this is shown.
 * [weak]: only when nothing on the page has art of its own (the shelf's song).
 */
@Composable
fun AmbientSource(model: Any?, weak: Boolean = false) {
    val source = remember { Ambient.Source(weak) }
    source.model = model
    DisposableEffect(source) {
        Ambient.sources += source
        onDispose { Ambient.sources -= source }
    }
}

/** Works out the accent from the sources, or the fixed colour, and eases into it. Once, at the root. */
@Composable
fun AmbientHost() {
    val context = LocalContext.current.applicationContext
    val fixed = Ambient.fixed
    val model = if (fixed != null) null else Ambient.sources.lastOrNull { !it.weak && it.model != null }?.model
        ?: Ambient.sources.lastOrNull { it.model != null }?.model
    LaunchedEffect(model, fixed) {
        val target = fixed ?: model?.let { runCatching { Ambient.colorsOf(context, it) }.getOrNull() } ?: Ambient.Blue
        Ambient.easeTo(target)
    }
}

private const val EASE_MS = 700
