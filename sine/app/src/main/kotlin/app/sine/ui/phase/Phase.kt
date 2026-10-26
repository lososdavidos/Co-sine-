package app.sine.ui.phase

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import app.sine.R

/*
 * Phase, Sine's design language (§6). Two signals a quarter turn apart:
 * Sine is you, now (what's playing, what you've selected, the one action a
 * screen is for); Cosine is the server at work (fetching, matching, syncing,
 * waiting for your review). Red is for faults. Everything else is grey or
 * artwork. No glass: surfaces step up in lightness, so every Android version,
 * the widget and the web look the same.
 */

object PhaseColor {
    /** Every page. Not pure black: pixels that switch fully off smear when a list scrolls on OLED. */
    val Ground = Color(0xFF0B0B0C)
    /** Tiles, fields, the queue, a pressed row. */
    val Raised = Color(0xFF161618)
    /** The shelf, the now-playing sheet, menus: what stays put while content moves. */
    val Float = Color(0xFF202023)
    /** Secondary pills, and anything pressed on a floating surface. */
    val Lift = Color(0xFF2E2E33)
    /** Hairlines on the ground. */
    val Line = Color(0xFF2A2A2F)
    /** The edge of a floating surface. */
    val LineFloat = Color(0xFF34343A)
    /** Outlines of neutral tags and chips. */
    val LineStrong = Color(0xFF45454B)
    /** Chart bars and the unplayed part of the wave. */
    val Bar = Color(0xFF3A3A40)

    val Text1 = Color(0xFFF3F3F0)
    val Text2 = Color(0xFFA6A6AB)
    /** Labels, and what can't be played right now. 5.7:1 on the ground, 4.7:1 on Float. */
    val Text3 = Color(0xFF8A8A90)
    /** Secondary text over a scrimmed cover. */
    val OnScrim = Color(0xFFD9D9DC)

    /** You, now. oklch(0.91 0.19 118). */
    val Sine = Color(0xFFD7F144)
    /** The server at work: Sine's hue plus 90°. oklch(0.80 0.12 208). */
    val Cosine = Color(0xFF46D3E6)
    /** Broken or missing, and the button that removes or deletes. */
    val Fault = Color(0xFFF75E51)
    /** Text on any of the three signals. */
    val Ink = Color(0xFF0B0B0C)

    /** Over artwork, always at this strength; never adapted to the image. */
    val Scrim = Ground.copy(alpha = 0.64f)
}

object PhaseFont {
    val Display = FontFamily(Font(R.font.phase_display, FontWeight.Bold))
    val Title = FontFamily(Font(R.font.phase_title, FontWeight.SemiBold))
    val Sans = FontFamily(
        Font(R.font.phase_sans_regular, FontWeight.Normal),
        Font(R.font.phase_sans_medium, FontWeight.Medium),
        Font(R.font.phase_sans_semibold, FontWeight.SemiBold),
        Font(R.font.phase_sans_semibold, FontWeight.Bold),
    )
    val Mono = FontFamily(
        Font(R.font.phase_mono_regular, FontWeight.Normal),
        Font(R.font.phase_mono_medium, FontWeight.Medium),
        Font(R.font.phase_mono_semibold, FontWeight.SemiBold),
    )
}

/** Type carries the interface: names large and condensed, data small and mono. */
object PhaseType {
    /** Names and screen titles: Instrument Sans at width 75, bold. */
    fun display(size: TextUnit) = TextStyle(
        fontFamily = PhaseFont.Display, fontWeight = FontWeight.Bold,
        fontSize = size, lineHeight = size * 0.92f, letterSpacing = (-0.01).em,
    )

    /** Section heads and the now-playing title: width 85, semibold. */
    val Title = TextStyle(fontFamily = PhaseFont.Title, fontWeight = FontWeight.SemiBold, fontSize = 22.sp, lineHeight = 26.sp)

    /** Names in rows. */
    val Name = TextStyle(fontFamily = PhaseFont.Sans, fontWeight = FontWeight.Medium, fontSize = 15.sp, lineHeight = 20.sp)
    val Body = TextStyle(fontFamily = PhaseFont.Sans, fontWeight = FontWeight.Normal, fontSize = 15.sp, lineHeight = 21.sp)
    /** Second lines. */
    val Small = TextStyle(fontFamily = PhaseFont.Sans, fontWeight = FontWeight.Normal, fontSize = 13.sp, lineHeight = 18.sp)
    val Button = TextStyle(fontFamily = PhaseFont.Sans, fontWeight = FontWeight.Medium, fontSize = 15.sp, lineHeight = 20.sp)

    /** Kinds, sources, sections: Martian Mono, set in capitals by [PhaseLabel]. */
    val Label = TextStyle(
        fontFamily = PhaseFont.Mono, fontWeight = FontWeight.Medium,
        fontSize = 10.sp, lineHeight = 14.sp, letterSpacing = 0.05.em,
    )

    /** Lengths, counts, formats: tabular. */
    val Data = TextStyle(
        fontFamily = PhaseFont.Mono, fontWeight = FontWeight.Normal,
        fontSize = 12.sp, lineHeight = 16.sp, fontFeatureSettings = "tnum",
    )
}

/** Shape says what a thing is. Circles are people; see [androidx.compose.foundation.shape.CircleShape]. */
object PhaseShape {
    /** Something you read: a cover, a tag, a mark. */
    val Square = RoundedCornerShape(3.dp)
    /** Something you press. */
    val Pill = RoundedCornerShape(percent = 50)
    /** A surface that holds things: tiles, fields. */
    val Slab = RoundedCornerShape(16.dp)
    /** A floating surface: the shelf, sheets, dialogs. */
    val Floating = RoundedCornerShape(24.dp)
}

private val colors = darkColorScheme(
    primary = PhaseColor.Sine, onPrimary = PhaseColor.Ink,
    primaryContainer = PhaseColor.Sine, onPrimaryContainer = PhaseColor.Ink, inversePrimary = PhaseColor.Sine,
    secondary = PhaseColor.Cosine, onSecondary = PhaseColor.Ink,
    secondaryContainer = PhaseColor.Lift, onSecondaryContainer = PhaseColor.Text1,
    tertiary = PhaseColor.Cosine, onTertiary = PhaseColor.Ink,
    tertiaryContainer = PhaseColor.Lift, onTertiaryContainer = PhaseColor.Text1,
    background = PhaseColor.Ground, onBackground = PhaseColor.Text1,
    surface = PhaseColor.Ground, onSurface = PhaseColor.Text1,
    surfaceVariant = PhaseColor.Raised, onSurfaceVariant = PhaseColor.Text2,
    surfaceTint = PhaseColor.Ground,
    surfaceBright = PhaseColor.Lift, surfaceDim = PhaseColor.Ground,
    surfaceContainerLowest = PhaseColor.Ground, surfaceContainerLow = PhaseColor.Raised,
    surfaceContainer = PhaseColor.Raised, surfaceContainerHigh = PhaseColor.Float,
    surfaceContainerHighest = PhaseColor.Lift,
    inverseSurface = PhaseColor.Text1, inverseOnSurface = PhaseColor.Ink,
    error = PhaseColor.Fault, onError = PhaseColor.Ink,
    errorContainer = PhaseColor.Fault, onErrorContainer = PhaseColor.Ink,
    outline = PhaseColor.LineStrong, outlineVariant = PhaseColor.Line,
    scrim = Color.Black,
)

private val typography = Typography().let { t ->
    fun TextStyle.sans() = copy(fontFamily = PhaseFont.Sans)
    Typography(
        displayLarge = PhaseType.display(56.sp), displayMedium = PhaseType.display(44.sp), displaySmall = PhaseType.display(36.sp),
        headlineLarge = PhaseType.display(32.sp), headlineMedium = PhaseType.display(28.sp), headlineSmall = PhaseType.display(24.sp),
        titleLarge = PhaseType.Title, titleMedium = t.titleMedium.sans(), titleSmall = t.titleSmall.sans(),
        bodyLarge = PhaseType.Body, bodyMedium = PhaseType.Small, bodySmall = PhaseType.Small,
        labelLarge = PhaseType.Button, labelMedium = t.labelMedium.sans(), labelSmall = t.labelSmall.sans(),
    )
}

private val shapes = Shapes(
    extraSmall = PhaseShape.Square, small = RoundedCornerShape(8.dp),
    medium = PhaseShape.Slab, large = PhaseShape.Floating, extraLarge = PhaseShape.Floating,
)

/**
 * Phase is dark: Sine has no light theme. Material components that remain
 * (text selection, switches, the rare dialog) take Phase's colours from here.
 */
@Composable
fun PhaseTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = colors, typography = typography, shapes = shapes, content = content)
}
