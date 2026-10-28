package com.example.ftmstreadmill.ui.run

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Flag
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.ftmstreadmill.R
import com.example.ftmstreadmill.health.ExportResult
import com.example.ftmstreadmill.health.HealthConnectAvailability
import com.example.ftmstreadmill.ui.common.Badge
import com.example.ftmstreadmill.ui.common.HealthConnectButton
import com.example.ftmstreadmill.ui.common.ProfileChart
import com.example.ftmstreadmill.ui.common.Units
import com.example.ftmstreadmill.ui.common.WorkoutFormat
import com.example.ftmstreadmill.ui.common.distanceUnitLabel
import com.example.ftmstreadmill.ui.common.segmentTitle
import com.example.ftmstreadmill.ui.common.speedUnitLabel
import com.example.ftmstreadmill.workout.model.EndReason
import com.example.ftmstreadmill.workout.model.Session
import com.example.ftmstreadmill.workout.model.WorkoutPlan

/** Shown when a workout ends: totals, how each interval went, and what to do next. */
@Composable
fun WorkoutSummary(
    session: Session,
    imperial: Boolean,
    healthConnect: HealthConnectAvailability,
    autoExport: Boolean,
    export: suspend () -> ExportResult,
    onDone: () -> Unit,
    onDiscard: () -> Unit,
) {
    Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Icon(
                if (session.completed) Icons.Default.CheckCircle else Icons.Default.Flag,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(top = 24.dp).size(72.dp),
            )
            Text(
                stringResource(if (session.completed) R.string.summary_complete else R.string.summary_ended),
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Text(session.workoutName, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            endReasonText(session.endReason)?.let {
                Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            SessionStats(session, imperial)
            SessionSegments(session, imperial)
            if (!autoExport || healthConnect != HealthConnectAvailability.AVAILABLE) {
                HealthConnectButton(session.healthConnectExported, healthConnect, export)
            }
        }
        Row(
            Modifier.fillMaxWidth().padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(onClick = onDiscard) { Text(stringResource(R.string.discard)) }
            Button(onClick = onDone, modifier = Modifier.weight(1f).height(52.dp)) { Text(stringResource(R.string.done)) }
        }
    }
}

@Composable
fun endReasonText(reason: EndReason): String? = when (reason) {
    EndReason.COMPLETED -> null
    EndReason.ENDED_IN_APP -> stringResource(R.string.end_reason_app)
    EndReason.STOPPED_ON_TREADMILL -> stringResource(R.string.end_reason_treadmill)
    EndReason.SAFETY_KEY -> stringResource(R.string.end_reason_safety)
}

/** Time, distance, calories and average speed in a card. */
@Composable
fun SessionStats(session: Session, imperial: Boolean) {
    val avgSpeed = if (session.activeSec > 0 && (session.distanceMeters ?: 0) > 0) {
        session.distanceMeters!! / 1000.0 / (session.activeSec / 3600.0)
    } else {
        null
    }
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh), modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().padding(vertical = 16.dp)) {
            Stat(stringResource(R.string.metric_time), Units.formatDuration(session.activeSec), "", Modifier.weight(1f))
            Stat(stringResource(R.string.metric_distance), Units.formatDistance(session.distanceMeters, imperial), distanceUnitLabel(imperial), Modifier.weight(1f))
            Stat(stringResource(R.string.avg_speed), Units.formatSpeed(avgSpeed, imperial), speedUnitLabel(imperial), Modifier.weight(1f))
            Stat(stringResource(R.string.metric_calories), session.kcal?.toString() ?: "–", stringResource(R.string.unit_kcal), Modifier.weight(1f))
        }
    }
}

/** The planned profile with actual average speeds drawn over it, then a row per interval. */
@Composable
fun SessionSegments(session: Session, imperial: Boolean) {
    val plan = remember(session) { session.workout?.let(WorkoutPlan::of) }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (plan != null) {
            ProfileChart(
                plan,
                Modifier.fillMaxWidth().height(96.dp),
                actualSpeedsKmh = session.segments.map { it.avgSpeedKmh },
                description = stringResource(R.string.planned_vs_actual_description),
            )
            Text(stringResource(R.string.planned_vs_actual_legend), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (session.segments.isEmpty()) return@Column
        Text(stringResource(R.string.segments), style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 8.dp))
        val unit = speedUnitLabel(imperial)
        session.segments.forEachIndexed { i, result ->
            if (i > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(segmentTitle(result.planned), style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        if (result.adjusted) Badge(stringResource(R.string.adjusted))
                        if (result.skipped) Badge(stringResource(R.string.skipped))
                    }
                    Text(
                        stringResource(
                            R.string.planned_line,
                            Units.formatDuration(result.planned.durationSec),
                            Units.formatSpeed(result.planned.speedKmh, imperial),
                            unit,
                            WorkoutFormat.incline(result.planned.inclinePercent),
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Column(horizontalAlignment = Alignment.End) {
                    Text(Units.formatDuration(result.activeSec), style = MaterialTheme.typography.bodyLarge)
                    Text(
                        result.avgSpeedKmh?.let { stringResource(R.string.avg_value, Units.formatSpeed(it, imperial), unit) } ?: "–",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}
