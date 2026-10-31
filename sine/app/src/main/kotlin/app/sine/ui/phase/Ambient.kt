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
    class Colors(val fill: Color, val far: Color, val light: Color, val deep: Color)

    val Blue = Colors(Color(0xFF53A4FF), Color(0xFF3F8FF0), Color(0xFFB2D6FF), Color(0xFF0E4683))

    internal val fill = Animatable(Blue.fill)
    internal val far = Animatable(Blue.far)
    internal val light = Animatable(Blue.light)
    internal val deep = Animatable(Blue.deep)

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
        val a = Accents.accent(Accents.hues(pixels) ?: return null)
        return Colors(Color(a.fill), Color(a.far), Color(a.light), Color(a.deep))
    }
}

/** The accent as a gradient: for what's filled with it, a pill or a selected row. */
val PhaseColor.SineBrush: Brush get() = Brush.linearGradient(listOf(Sine, SineFar))

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

/** Works out the accent from the sources, and eases into it. Once, at the root. */
@Composable
fun AmbientHost() {
    val context = LocalContext.current.applicationContext
    val model = Ambient.sources.lastOrNull { !it.weak && it.model != null }?.model
        ?: Ambient.sources.lastOrNull { it.model != null }?.model
    LaunchedEffect(model) {
        val target = model?.let { runCatching { Ambient.colorsOf(context, it) }.getOrNull() } ?: Ambient.Blue
        coroutineScope {
            val ease = tween<Color>(EASE_MS)
            launch { Ambient.fill.animateTo(target.fill, ease) }
            launch { Ambient.far.animateTo(target.far, ease) }
            launch { Ambient.light.animateTo(target.light, ease) }
            launch { Ambient.deep.animateTo(target.deep, ease) }
        }
    }
}

private const val EASE_MS = 700
