package app.sine.ui.phase

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.staticCompositionLocalOf
import app.sine.core.color.Accents
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.awt.image.BufferedImage
import java.io.File
import java.io.InputStream
import javax.imageio.ImageIO

/** The HTTP client artwork is read with, for its colours: the app's. */
val LocalArtClient = staticCompositionLocalOf { OkHttpClient() }

/** Works out the accent from the sources, or the fixed colour, and eases into it. Once, at the root. */
@Composable
fun AmbientHost() {
    val http = LocalArtClient.current
    val (fixed, model) = Ambient.wanted()
    LaunchedEffect(model, fixed) {
        val target = fixed ?: model?.let { runCatching { colorsOf(http, it) }.getOrNull() } ?: Ambient.Blue
        Ambient.easeTo(target)
    }
}

/** The colours of [model]'s artwork, a file or a URL; null when it has none to give. */
internal suspend fun colorsOf(http: OkHttpClient, model: Any): Ambient.Colors? {
    val key = model.toString()
    synchronized(Ambient.known) { if (Ambient.known.containsKey(key)) return Ambient.known[key] }
    val colors = withContext(Dispatchers.IO) {
        val image = when (model) {
            is File -> model.inputStream().use(::read)
            is String -> http.newCall(Request.Builder().url(model).build()).execute().use { r ->
                if (r.isSuccessful) read(r.body.byteStream()) else null
            }
            else -> null
        } ?: return@withContext null
        pick(image)
    }
    synchronized(Ambient.known) { Ambient.known[key] = colors }
    return colors
}

private fun read(input: InputStream): BufferedImage? = ImageIO.read(input)

/** The same sum as Android's: the hues of a small copy of the picture (Accents). */
internal fun pick(image: BufferedImage): Ambient.Colors? {
    val small = BufferedImage(SIDE, SIDE, BufferedImage.TYPE_INT_ARGB)
    small.createGraphics().apply {
        drawImage(image, 0, 0, SIDE, SIDE, null)
        dispose()
    }
    val pixels = small.getRGB(0, 0, SIDE, SIDE, null, 0, SIDE)
    return Ambient.of(Accents.accent(Accents.hues(pixels) ?: return null))
}

private const val SIDE = 48
