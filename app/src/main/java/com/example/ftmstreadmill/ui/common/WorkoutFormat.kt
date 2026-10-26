package com.example.ftmstreadmill.ui.common

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.example.ftmstreadmill.R
import com.example.ftmstreadmill.workout.model.PlannedSegment
import com.example.ftmstreadmill.workout.model.SegmentKind
import com.example.ftmstreadmill.workout.model.SpeedUnit
import java.util.Locale

/** Formatting shared by the workout screens. Speeds arrive in km/h and leave in the user's units. */
object WorkoutFormat {
    fun units(imperial: Boolean) = if (imperial) SpeedUnit.MPH else SpeedUnit.KMH

    /** "30 min", "1 h 05 min" or "45 s" for list summaries. */
    fun approxDuration(seconds: Int): String = when {
        seconds < 60 -> "$seconds s"
        seconds < 3600 -> "${(seconds + 30) / 60} min"
        else -> "%d h %02d min".format(seconds / 3600, (seconds % 3600 + 30) / 60)
    }

    fun number(value: Double): String = "%.1f".format(Locale.getDefault(), value)

    /** Incline without a pointless ".0". */
    fun incline(percent: Double): String =
        if (percent % 1.0 == 0.0) "%.0f".format(Locale.getDefault(), percent) else number(percent)
}

@Composable
fun speedUnitLabel(imperial: Boolean): String = stringResource(if (imperial) R.string.unit_mph else R.string.unit_kmh)

@Composable
fun distanceUnitLabel(imperial: Boolean): String = stringResource(if (imperial) R.string.unit_mi else R.string.unit_km)

/** "Warmup", "Cooldown", the interval's name, or "Interval 3". */
@Composable
fun segmentTitle(segment: PlannedSegment): String = when (segment.kind) {
    SegmentKind.WARMUP -> segment.name ?: stringResource(R.string.segment_warmup)
    SegmentKind.COOLDOWN -> segment.name ?: stringResource(R.string.segment_cooldown)
    SegmentKind.INTERVAL -> segment.name ?: stringResource(R.string.segment_interval_n, segment.intervalNumber ?: 0)
}

/** "2:00 · 3.5 mph · 6%" */
@Composable
fun segmentSummary(durationSec: Int, speedKmh: Double, inclinePercent: Double, imperial: Boolean): String =
    stringResource(
        R.string.segment_summary,
        Units.formatDuration(durationSec),
        Units.formatSpeed(speedKmh, imperial),
        speedUnitLabel(imperial),
        WorkoutFormat.incline(inclinePercent),
    )
