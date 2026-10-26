package app.sine.ui.phase

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.unit.dp

/**
 * The few icons Phase uses. Words do most of the work; icons are for
 * transport, where everyone already knows them, and for marks too small
 * for a word. Drawn on a 24 grid, tinted where they're used.
 */
object PhaseIcons {
    val Play = filled("play", "M7 4.5v15l12.5-7.5z")
    val Pause = filled("pause", "M6 4.5h4v15h-4z M14 4.5h4v15h-4z")
    val Previous = filled("previous", "M19 5.5v13L9 12z M4.5 5.5h2.5v13h-2.5z")
    val Next = filled("next", "M5 5.5v13L15 12z M17 5.5h2.5v13h-2.5z")
    val Shuffle = stroked(
        "shuffle",
        "M3 7h3.5c2 0 3.2 1 4.3 2.7l2.4 4.6c1.1 1.7 2.3 2.7 4.3 2.7H21M18 14l3 3-3 3M3 17h3.5c1.4 0 2.4-.5 3.2-1.4M14.3 8.4c.8-.9 1.8-1.4 3.2-1.4H21M18 4l3 3-3 3",
        1.75f,
    )
    /** A local copy: on this phone. */
    val OnPhone = stroked("on-phone", "M12 4v11M7 10.5l5 5 5-5M5 20h14")
    val Plus = stroked("plus", "M12 5v14M5 12h14")
    val Back = stroked("back", "M15 5l-7 7 7 7")
    val Forward = stroked("forward", "M9 5l7 7-7 7")
    val Down = stroked("down", "M6 9l6 6 6-6")
    val Up = stroked("up", "M6 15l6-6 6 6")
    val Close = stroked("close", "M6 6l12 12M18 6L6 18")
    val Search = stroked("search", "M17.5 11a6.5 6.5 0 1 1-13 0a6.5 6.5 0 1 1 13 0z M16 16l4.5 4.5")
    val Device = stroked("device", "M9 3h6a2 2 0 0 1 2 2v14a2 2 0 0 1-2 2H9a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2z M11 18h2", 1.75f)
    val Check = stroked("check", "M5 12.5l4.5 4.5L19 7", 2.5f)
    /** A smart playlist: worked out, not chosen. */
    val Wave = stroked("wave", "M2 12C5 6 8 6 11 12S17 18 20 12", 1.75f)

    private fun build(name: String, d: String, stroke: Float?) = ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f).apply {
        addPath(
            pathData = PathParser().parsePathString(d).toNodes(),
            fill = if (stroke == null) SolidColor(Color.Black) else null,
            stroke = if (stroke != null) SolidColor(Color.Black) else null,
            strokeLineWidth = stroke ?: 0f,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round,
        )
    }.build()

    private fun filled(name: String, d: String) = build(name, d, null)
    private fun stroked(name: String, d: String, width: Float = 2f) = build(name, d, width)
}
