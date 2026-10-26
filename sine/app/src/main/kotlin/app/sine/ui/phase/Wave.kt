package app.sine.ui.phase

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.unit.dp

/**
 * Where playback is, drawn as the product's name: a sine wave, Sine up to
 * the position and grey after it. This is the one place the wave appears;
 * everywhere else progress is a straight line. Drag or tap to seek.
 */
@Composable
fun WaveScrubber(
    fraction: Float,
    onScrub: (Float) -> Unit,
    onScrubEnd: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    description: String = "Position",
) {
    val scrub by rememberUpdatedState(onScrub)
    val end by rememberUpdatedState(onScrubEnd)
    Canvas(
        modifier
            .fillMaxWidth()
            .height(40.dp)
            .semantics {
                contentDescription = description
                progressBarRangeInfo = ProgressBarRangeInfo(fraction, 0f..1f)
                if (enabled) setProgress { v -> scrub(v.coerceIn(0f, 1f)); end(); true }
            }
            .pointerInput(enabled) {
                if (!enabled) return@pointerInput
                detectTapGestures { o -> scrub((o.x / size.width).coerceIn(0f, 1f)); end() }
            }
            .pointerInput(enabled) {
                if (!enabled) return@pointerInput
                detectHorizontalDragGestures(
                    onDragStart = { o -> scrub((o.x / size.width).coerceIn(0f, 1f)) },
                    onDragEnd = { end() },
                    onDragCancel = { end() },
                ) { change, _ -> scrub((change.position.x / size.width).coerceIn(0f, 1f)) }
            },
    ) {
        val mid = size.height / 2
        val amp = 8.dp.toPx()
        val halves = 12
        val half = size.width / halves
        // Cubic halves: control points at 36% in, 4/3 of the amplitude out, a close match for a sine.
        val path = Path().apply {
            moveTo(0f, mid)
            for (i in 0 until halves) {
                val x0 = half * i
                val dir = if (i % 2 == 0) -1 else 1
                val c = mid + dir * amp * 4f / 3f
                cubicTo(x0 + half * 0.3642f, c, x0 + half * (1 - 0.3642f), c, x0 + half, mid)
            }
        }
        val at = size.width * fraction.coerceIn(0f, 1f)
        clipRect(left = at, right = size.width) {
            drawPath(path, PhaseColor.Bar, style = Stroke(width = 2.5.dp.toPx(), cap = StrokeCap.Round))
        }
        clipRect(right = at) {
            drawPath(path, PhaseColor.Sine, style = Stroke(width = 3.5.dp.toPx(), cap = StrokeCap.Round))
        }
        val knob = 6.dp.toPx()
        drawRoundRect(
            PhaseColor.Sine,
            topLeft = Offset((at - knob / 2).coerceIn(0f, size.width - knob), 6.dp.toPx()),
            size = Size(knob, size.height - 12.dp.toPx()),
            cornerRadius = CornerRadius(knob / 2),
        )
    }
}
