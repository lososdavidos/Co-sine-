package app.sine.ui.phase

import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import app.sine.R

/** Phase's fonts on Android: res/font, which the desktop loads too. */
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
