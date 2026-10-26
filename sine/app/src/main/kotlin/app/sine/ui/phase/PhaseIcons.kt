package app.sine.ui.phase

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.isSpecified
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Phase's icons (§6.1). Icons carry the actions, navigation and marks; words
 * carry names, status, questions and what a form does. Drawn on a 24 grid,
 * solid where they're things and in heavy lines where they're gestures, so
 * [PhaseIcon] has something to light.
 */
object PhaseIcons {
    // Transport
    val Play = icon("play", solid("M7.5 5v14l11.5-7z"))
    val Pause = icon("pause", solid("M6.5 5h3.5v14H6.5z M14 5h3.5v14H14z"))
    val Previous = icon("previous", solid("M18.5 5.5v13L9.5 12z M5 5.5h2.5v13H5z"))
    val Next = icon("next", solid("M5.5 5.5v13l9-6.5z M16.5 5.5H19v13h-2.5z"))
    val Shuffle = icon(
        "shuffle",
        line("M3 7h3.5c2 0 3.2 1 4.3 2.7l2.4 4.6c1.1 1.7 2.3 2.7 4.3 2.7H21M18 14l3 3-3 3M3 17h3.5c1.4 0 2.4-.5 3.2-1.4M14.3 8.4c.8-.9 1.8-1.4 3.2-1.4H21M18 4l3 3-3 3", 2.25f),
    )
    /** Repeat the queue. */
    val Repeat = icon("repeat", line(REPEAT, 2.25f))
    /** Repeat this song: the same loop, a one inside it. */
    val RepeatOne = icon("repeat-one", line(REPEAT, 2.25f), line("M11 10.6l1.4-1.1v5", 2f))

    // Where you go
    val Home = icon("home", solid("M4.5 10.4L12 4.5l7.5 5.9V19a1 1 0 0 1-1 1h-4v-4.5a1 1 0 0 0-1-1h-3a1 1 0 0 0-1 1V20h-4a1 1 0 0 1-1-1z"))
    val Search = icon("search", line("M16.5 10.5a6 6 0 1 1-12 0a6 6 0 1 1 12 0z M15.2 15.2l4.8 4.8", 2.6f))
    val Library = icon("library", solid("M4.5 4.5h3v15h-3z M9.75 4.5h3v15h-3z M14.9 5.7l2.9-.8 3.6 14-2.9.8z"))

    // What a thing is
    val Artist = icon("artist", solid("M12 4a3.75 3.75 0 1 1 0 7.5A3.75 3.75 0 1 1 12 4z M5 19.5c.4-3.6 3.3-5.75 7-5.75s6.6 2.15 7 5.75z"))
    val Album = icon("album", solid("M12 3.5a8.5 8.5 0 1 1 0 17a8.5 8.5 0 1 1 0-17z M12 9.4a2.6 2.6 0 1 0 0 5.2a2.6 2.6 0 1 0 0-5.2z", evenOdd = true))
    val Playlist = icon("playlist", line("M4.5 6.5h15M4.5 12h15M4.5 17.5h9", 2.5f))
    /** A smart playlist: worked out, not chosen. */
    val Wave = icon("wave", line("M2.5 12C5.3 6 8.2 6 11 12s8.2 6 11 0", 2.4f))
    val Device = icon("device", line("M9 3h6a2 2 0 0 1 2 2v14a2 2 0 0 1-2 2H9a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2z M11 18h2", 2f))
    val Offline = icon(
        "offline",
        line("M7.5 18.5h9.5a3.75 3.75 0 0 0 .5-7.47A5.5 5.5 0 0 0 7 9.6a4.5 4.5 0 0 0 .5 8.9z M4 4.5l16 15", 2.1f),
    )
    /** The review queue: what Cosine wasn't sure of, waiting for you. */
    val Review = icon(
        "review",
        line("M4.5 13.5l2.2-7a1.5 1.5 0 0 1 1.43-1h7.74a1.5 1.5 0 0 1 1.43 1l2.2 7V18a1.5 1.5 0 0 1-1.5 1.5H6A1.5 1.5 0 0 1 4.5 18z M4.5 13.5h4l1 2h5l1-2h4", 2.1f),
    )

    // What you do
    /** Download, and the mark for a local copy: on this phone. */
    val OnPhone = icon("on-phone", line("M12 4v10.5M7.5 10l4.5 4.5 4.5-4.5M5 19.5h14", 2.4f))
    val OffPhone = icon("off-phone", line("M12 4v6.5M7.5 10l4.5 4.5 1.2-1.2M5 19.5h14M4.5 4.5l15 15", 2.4f))
    val PlaylistAdd = icon("playlist-add", line("M4.5 6.5h11M4.5 11.5h11M4.5 16.5h6M17.5 13v7M14 16.5h7", 2.4f))
    /** Add to the end of what's playing. */
    val QueueAdd = icon("queue-add", line("M4.5 6.5h11M4.5 11.5h11M4.5 16.5h6", 2.4f), solid("M15 13.5v6l5-3z"))
    /** Out of the queue. */
    val QueueRemove = icon("queue-remove", line("M4.5 6.5h11M4.5 11.5h11M4.5 16.5h6M14 16.5h7", 2.4f))
    val PlaylistRemove = icon("playlist-remove", line("M4.5 6.5h11M4.5 11.5h11M4.5 16.5h6M14 16.5h7", 2.4f))
    /** Makes a list of songs your own: save as playlist. */
    val Liked = icon("liked", solid("M12 20l-1.3-1.2C6.1 14.7 3.5 12.3 3.5 9.2 3.5 6.7 5.5 4.8 8 4.8c1.5 0 2.9.7 4 1.9 1.1-1.2 2.5-1.9 4-1.9 2.5 0 4.5 1.9 4.5 4.4 0 3.1-2.6 5.5-7.2 9.6z"))
    val PlaylistKeep = icon("playlist-keep", line("M4.5 6.5h11M4.5 11.5h11M4.5 16.5h6M13.5 16.5l2.5 2.5 5-5", 2.4f))
    val Trash = icon("trash", solid("M7 8.5h10l-.8 10.2a1.5 1.5 0 0 1-1.5 1.3H9.3a1.5 1.5 0 0 1-1.5-1.3z"), line("M5 5.75h14M10 3.5h4", 2.3f))
    val Leave = icon("leave", line("M10 4.5H6.5A1.5 1.5 0 0 0 5 6v12a1.5 1.5 0 0 0 1.5 1.5H10M14.5 8l4 4-4 4M18.5 12H9.5", 2.3f))
    val Pin = icon("pin", solid("M9 4h6l-.9 4.8 3.4 3.4V14h-11v-1.8l3.4-3.4z"), line("M12 14v6", 2.4f))
    val Unpin = icon("unpin", solid("M9 4h6l-.9 4.8 3.4 3.4V14h-11v-1.8l3.4-3.4z"), line("M12 14v6M3.5 3.5l17 17", 2.4f))
    /** Share with another account: a person, and one more. */
    val Share = icon("share", solid("M10 4.5a3.5 3.5 0 1 1 0 7a3.5 3.5 0 1 1 0-7z M3.5 19.5c.4-3.4 3-5.3 6.5-5.3s6.1 1.9 6.5 5.3z"), line("M19 8v6M16 11h6", 2.4f))
    val Edit = icon("edit", solid("M15 5.2l3.8 3.8L9 18.8l-4.6 1 1-4.6z"))
    val Reorder = icon("reorder", line("M8 19.5v-15M4.5 8L8 4.5 11.5 8M16 4.5v15M12.5 16l3.5 3.5 3.5-3.5", 2.4f))
    /** A smart playlist's rule. */
    val Rule = icon("rule", line("M4 7.5h16M4 16.5h16", 2.3f), solid("M9 5a2.5 2.5 0 1 1 0 5a2.5 2.5 0 1 1 0-5z M15 14a2.5 2.5 0 1 1 0 5a2.5 2.5 0 1 1 0-5z"))
    val Retry = icon("retry", line("M19 12a7 7 0 1 1-2.05-4.95M19 4.5V8h-3.5", 2.4f))
    val Resize = icon("resize", line("M14 4.5h5.5V10M10 19.5H4.5V14M19.5 4.5l-6 6M4.5 19.5l6-6", 2.4f))
    val Earlier = icon("earlier", line("M19 12H5.5M11 6.5L5.5 12l5.5 5.5", 2.5f))
    val Later = icon("later", line("M5 12h13.5M13 6.5l5.5 5.5-5.5 5.5", 2.5f))
    /** A tile that opens what it shows, rather than playing it. */
    val Open = icon("open", line("M13.5 4.5h6v6M19.5 4.5l-8 8M18 14v4a1.5 1.5 0 0 1-1.5 1.5h-10A1.5 1.5 0 0 1 5 18V8a1.5 1.5 0 0 1 1.5-1.5h4", 2.3f))
    val Plus = icon("plus", line("M12 5v14M5 12h14", 2.6f))
    val Back = icon("back", line("M15 5l-7 7 7 7", 2.6f))
    val Forward = icon("forward", line("M9 5l7 7-7 7", 2.4f))
    val Down = icon("down", line("M6 9l6 6 6-6", 2.6f))
    val Up = icon("up", line("M6 15l6-6 6 6", 2.6f))
    val Close = icon("close", line("M6.5 6.5l11 11M17.5 6.5l-11 11", 2.6f))
    val Check = icon("check", line("M5 12.5l4.5 4.5L19 7", 2.8f))

    private const val REPEAT = "M17 3l3 3-3 3M4 11.5V10a4 4 0 0 1 4-4h12M7 21l-3-3 3-3M20 12.5V14a4 4 0 0 1-4 4H4"

    private class Part(val d: String, val solid: Boolean, val width: Float, val evenOdd: Boolean)

    /** A thing: filled, its corners softened by a hairline of the same fill. */
    private fun solid(d: String, evenOdd: Boolean = false) = Part(d, true, 1.4f, evenOdd)

    /** A gesture: a heavy round line. */
    private fun line(d: String, width: Float) = Part(d, false, width, false)

    private fun icon(name: String, vararg parts: Part) = ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f).apply {
        for (p in parts) {
            addPath(
                pathData = PathParser().parsePathString(p.d).toNodes(),
                pathFillType = if (p.evenOdd) PathFillType.EvenOdd else PathFillType.NonZero,
                fill = if (p.solid) SolidColor(Color.Black) else null,
                stroke = SolidColor(Color.Black),
                strokeLineWidth = p.width,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            )
        }
    }.build()
}

/**
 * How an icon is lit: a face lit from above, [top] fading to [bottom]; the
 * same shape again just below it in [edge], which is its depth; and, on
 * coloured faces, a hairline of [rim] along its upper edges where the light
 * catches. Slightly 3D: a millimetre of relief, never a render. [edge] is
 * chosen for the surface the icon sits on, so the relief reads on it.
 */
@Immutable
class IconTone(val top: Color, val bottom: Color, val edge: Color, val rim: Color = Color.Unspecified) {
    companion object {
        /** On the ground, raised and floating surfaces, and secondary pills. */
        val Light = IconTone(Color(0xFFFFFFFF), Color(0xFFB4B8BC), Color(0xFF3E4246))
        /** Quiet actions and places you aren't. */
        val Dim = IconTone(Color(0xFFCDD0D3), Color(0xFF8C9094), Color(0xFF303336))
        /** Marks beside a row: a chevron, a local copy. */
        val Faint = IconTone(Color(0xFFA9ACB0), Color(0xFF777B7F), Color(0xFF282B2E))
        val Sine = IconTone(Color(0xFFB2D6FF), Color(0xFF3F8FF0), Color(0xFF0E4683), Color(0xD9F2F8FF))
        val Cosine = IconTone(Color(0xFF63A8EE), Color(0xFF0F63AF), Color(0xFF062F57), Color(0xB3B5D7F7))
        val Fault = IconTone(Color(0xFFFFA59B), Color(0xFFE5483B), Color(0xFF6E1C16), Color(0xCCFFE1DC))
        /** Ink on a Sine pill: dark, its shadow a deeper blue. */
        val OnSine = IconTone(Color(0xFF26313E), Color(0xFF0A0B0C), Color(0xFF2A74C8), Color(0x99B9DAFF))
        /** Ink on a red pill. */
        val OnFault = IconTone(Color(0xFF36201E), Color(0xFF0A0B0C), Color(0xFFB13A2F), Color(0x80FFC4BD))
        /** Light on a Cosine pill. */
        val OnCosine = IconTone(Color(0xFFFFFFFF), Color(0xFFC4DCF3), Color(0xFF083A6B))
        /** Ink on the light pill: where you are. */
        val OnLight = IconTone(Color(0xFF2D3238), Color(0xFF0A0B0C), Color(0xFFA2A7AC), Color(0x99FFFFFF))
    }
}

/**
 * An icon, lit (see [IconTone]). Decorative unless it has a
 * [contentDescription]; a button that holds one names itself instead.
 */
@Composable
fun PhaseIcon(
    icon: ImageVector,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    size: Dp = 22.dp,
    tone: IconTone = IconTone.Light,
) {
    val painter = rememberVectorPainter(icon)
    Spacer(
        modifier
            .size(size)
            .then(
                if (contentDescription != null) Modifier.semantics {
                    this.contentDescription = contentDescription
                    role = Role.Image
                } else Modifier,
            )
            .drawWithCache {
                val depth = this.size.height * 0.06f
                val rim = this.size.height * 0.04f
                val face = Brush.verticalGradient(listOf(tone.top, tone.bottom))
                val edge = ColorFilter.tint(tone.edge)
                val light = if (tone.rim.isSpecified) ColorFilter.tint(tone.rim) else null
                // Room for the relief below the glyph and the rim's offset copy.
                val bounds = Rect(Offset(0f, -depth), Size(this.size.width, this.size.height + 2 * depth))
                val layer = Paint()
                val atop = Paint().apply { blendMode = BlendMode.SrcAtop }
                val cut = Paint().apply { blendMode = BlendMode.DstOut }
                onDrawBehind {
                    translate(top = depth) { with(painter) { draw(this@onDrawBehind.size, colorFilter = edge) } }
                    drawIntoCanvas { canvas ->
                        canvas.saveLayer(bounds, layer)
                        with(painter) { draw(this@onDrawBehind.size) }
                        drawRect(face, blendMode = BlendMode.SrcIn)
                        if (light != null) {
                            // The glyph in light, less itself moved down: its upper edges.
                            canvas.saveLayer(bounds, atop)
                            with(painter) { draw(this@onDrawBehind.size, colorFilter = light) }
                            canvas.saveLayer(bounds, cut)
                            translate(top = rim) { with(painter) { draw(this@onDrawBehind.size) } }
                            canvas.restore()
                            canvas.restore()
                        }
                        canvas.restore()
                    }
                }
            },
    )
}
