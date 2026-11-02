package app.sine.ui.phase

import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.platform.Font

/** Phase's fonts on the desktop: the Android app's res/font, the same files, from the classpath. */
object PhaseFont {
    val Display = FontFamily(Font("font/phase_display.ttf", FontWeight.Bold))
    val Title = FontFamily(Font("font/phase_title.ttf", FontWeight.SemiBold))
    val Sans = FontFamily(
        Font("font/phase_sans_regular.ttf", FontWeight.Normal),
        Font("font/phase_sans_medium.ttf", FontWeight.Medium),
        Font("font/phase_sans_semibold.ttf", FontWeight.SemiBold),
        Font("font/phase_sans_semibold.ttf", FontWeight.Bold),
    )
    val Mono = FontFamily(
        Font("font/phase_mono_regular.ttf", FontWeight.Normal),
        Font("font/phase_mono_medium.ttf", FontWeight.Medium),
        Font("font/phase_mono_semibold.ttf", FontWeight.SemiBold),
    )
}
