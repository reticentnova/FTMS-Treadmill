package com.example.ftmstreadmill.ui.common

import androidx.compose.foundation.Canvas
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.example.ftmstreadmill.workout.model.SegmentKind
import com.example.ftmstreadmill.workout.model.WorkoutPlan

/**
 * The workout's intensity profile: one bar per segment, as wide as its duration and as tall as its
 * speed, shading from primary to tertiary as the incline rises. Warmup and cooldown use a muted tone.
 *
 * With [progress] (0..1) the part already run is solid and the rest faded, with a marker at "now".
 * [actualSpeedsKmh] draws each segment's average speed as a line across its bar, for history.
 */
@Composable
fun ProfileChart(
    plan: WorkoutPlan,
    modifier: Modifier = Modifier,
    progress: Float? = null,
    actualSpeedsKmh: List<Double?> = emptyList(),
    description: String? = null,
) {
    val colors = MaterialTheme.colorScheme
    val low = colors.primary
    val high = colors.tertiary
    val muted = colors.secondary.copy(alpha = 0.55f)
    val marker = colors.onSurface
    val actualColor = colors.error
    val total = plan.totalSec.coerceAtLeast(1)
    val maxSpeed = maxOf(plan.maxSpeedKmh, actualSpeedsKmh.filterNotNull().maxOrNull() ?: 0.0).coerceAtLeast(1.0)
    val maxIncline = plan.maxInclinePercent.coerceAtLeast(INCLINE_FOR_FULL_TINT)

    Canvas(modifier.semantics { if (description != null) contentDescription = description }) {
        val gap = 2.dp.toPx()
        val radius = CornerRadius(3.dp.toPx())
        var x = 0f
        plan.segments.forEachIndexed { i, segment ->
            val w = size.width * segment.durationSec / total
            val h = size.height * (MIN_BAR + (1 - MIN_BAR) * (segment.speedKmh / maxSpeed).toFloat())
            val base = if (segment.kind == SegmentKind.INTERVAL) {
                lerp(low, high, (segment.inclinePercent / maxIncline).toFloat().coerceIn(0f, 1f))
            } else {
                muted
            }
            val barWidth = (w - gap).coerceAtLeast(1f)
            val top = size.height - h
            val color = if (progress == null) base else base.copy(alpha = base.alpha * FADED)
            drawRoundRect(color, Offset(x, top), Size(barWidth, h), radius)
            if (progress != null) {
                // Solid overlay for the elapsed part of this bar.
                val doneX = size.width * progress
                val doneWidth = (doneX - x).coerceIn(0f, barWidth)
                if (doneWidth > 0f) drawRoundRect(base, Offset(x, top), Size(doneWidth, h), radius)
            }
            actualSpeedsKmh.getOrNull(i)?.let { actual ->
                val y = size.height - size.height * (MIN_BAR + (1 - MIN_BAR) * (actual / maxSpeed).toFloat())
                drawLine(actualColor, Offset(x, y), Offset(x + barWidth, y), strokeWidth = 2.5.dp.toPx())
            }
            x += w
        }
        if (progress != null) {
            val px = size.width * progress.coerceIn(0f, 1f)
            drawLine(marker, Offset(px, 0f), Offset(px, size.height), strokeWidth = 2.dp.toPx())
            val tri = Path().apply {
                moveTo(px - 5.dp.toPx(), 0f)
                lineTo(px + 5.dp.toPx(), 0f)
                lineTo(px, 6.dp.toPx())
                close()
            }
            drawPath(tri, marker)
        }
    }
}

/** A circular countdown ring for the current interval. */
@Composable
fun ProgressRing(progress: Float, modifier: Modifier = Modifier, strokeWidth: Float = 14f) {
    val track = MaterialTheme.colorScheme.surfaceContainerHighest
    val fill = MaterialTheme.colorScheme.primary
    Canvas(modifier) {
        val stroke = strokeWidth.dp.toPx()
        val arcSize = Size(size.minDimension - stroke, size.minDimension - stroke)
        val topLeft = Offset((size.width - arcSize.width) / 2, (size.height - arcSize.height) / 2)
        drawArc(track, 0f, 360f, false, topLeft, arcSize, style = Stroke(stroke))
        // Remaining time shrinks clockwise from the top.
        drawArc(
            fill, -90f, 360f * (1f - progress.coerceIn(0f, 1f)), false, topLeft, arcSize,
            style = Stroke(stroke, cap = StrokeCap.Round),
        )
    }
}

private const val MIN_BAR = 0.22f
private const val FADED = 0.35f
private const val INCLINE_FOR_FULL_TINT = 10.0
