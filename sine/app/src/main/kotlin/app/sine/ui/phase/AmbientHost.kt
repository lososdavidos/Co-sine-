package app.sine.ui.phase

import android.content.Context
import android.graphics.Bitmap
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.LocalContext
import app.sine.core.color.Accents
import coil3.imageLoader
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.request.allowHardware
import coil3.toBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Works out the accent from the sources, or the fixed colour, and eases into it. Once, at the root. */
@Composable
fun AmbientHost() {
    val context = LocalContext.current.applicationContext
    val (fixed, model) = Ambient.wanted()
    LaunchedEffect(model, fixed) {
        val target = fixed ?: model?.let { runCatching { colorsOf(context, it) }.getOrNull() } ?: Ambient.Blue
        Ambient.easeTo(target)
    }
}

/** The colours of [model]'s artwork; null when it has none to give. */
private suspend fun colorsOf(context: Context, model: Any): Ambient.Colors? {
    val key = model.toString()
    synchronized(Ambient.known) { if (Ambient.known.containsKey(key)) return Ambient.known[key] }
    val request = ImageRequest.Builder(context).data(model).size(48).allowHardware(false).build()
    val bitmap = (context.imageLoader.execute(request) as? SuccessResult)?.image?.toBitmap() ?: return null
    val colors = withContext(Dispatchers.Default) { pick(bitmap) }
    synchronized(Ambient.known) { Ambient.known[key] = colors }
    return colors
}

private fun pick(bitmap: Bitmap): Ambient.Colors? {
    val pixels = IntArray(bitmap.width * bitmap.height)
    bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
    return Ambient.of(Accents.accent(Accents.hues(pixels) ?: return null))
}
