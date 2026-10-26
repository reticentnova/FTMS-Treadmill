package com.example.ftmstreadmill.ui.run

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedIconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.ftmstreadmill.R
import com.example.ftmstreadmill.ui.common.Badge
import com.example.ftmstreadmill.ui.common.ProfileChart
import com.example.ftmstreadmill.ui.common.ProgressRing
import com.example.ftmstreadmill.ui.common.RepeatButton
import com.example.ftmstreadmill.ui.common.Units
import com.example.ftmstreadmill.ui.common.WorkoutFormat
import com.example.ftmstreadmill.ui.common.distanceUnitLabel
import com.example.ftmstreadmill.ui.common.segmentSummary
import com.example.ftmstreadmill.ui.common.segmentTitle
import com.example.ftmstreadmill.ui.common.speedUnitLabel
import com.example.ftmstreadmill.workout.PauseReason
import com.example.ftmstreadmill.workout.RunPhase
import com.example.ftmstreadmill.workout.WorkoutRunState
import com.example.ftmstreadmill.workout.model.SegmentKind
import kotlinx.coroutines.delay

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ActiveWorkoutScreen(
    onMinimize: () -> Unit,
    onDone: () -> Unit,
    viewModel: ActiveWorkoutViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    var confirmEnd by remember { mutableStateOf(false) }

    // The screen is glanced at from a distance mid-run; don't let it sleep.
    val view = LocalView.current
    DisposableEffect(view) {
        view.keepScreenOn = true
        onDispose { view.keepScreenOn = false }
    }

    when (val run = state.run) {
        is WorkoutRunState.Finished -> WorkoutSummary(
            session = run.session,
            imperial = state.imperial,
            healthConnect = viewModel.healthConnectAvailability,
            autoExport = state.healthConnectAutoExport,
            export = viewModel::exportToHealthConnect,
            onDone = {
                viewModel.done()
                onDone()
            },
            onDiscard = {
                viewModel.discard()
                onDone()
            },
        )
        WorkoutRunState.Idle -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            // The engine starts asynchronously, so give it a moment before saying nothing is running.
            var waited by remember { mutableStateOf(false) }
            LaunchedEffect(Unit) {
                delay(IDLE_GRACE_MS)
                waited = true
            }
            if (!waited) {
                CircularProgressIndicator()
            } else {
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    Text(stringResource(R.string.no_workout_running), style = MaterialTheme.typography.titleMedium)
                    Button(onClick = onDone) { Text(stringResource(R.string.back)) }
                }
            }
        }
        is WorkoutRunState.Active -> Scaffold(
            topBar = {
                TopAppBar(
                    title = {
                        Text(run.workout.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    },
                    navigationIcon = {
                        IconButton(onClick = onMinimize) { Icon(Icons.Default.KeyboardArrowDown, stringResource(R.string.minimize)) }
                    },
                )
            },
            bottomBar = {
                RunControls(
                    run = run,
                    connected = state.isConnected,
                    onPause = viewModel::pause,
                    onResume = viewModel::resume,
                    onSkip = viewModel::skip,
                    onEnd = { confirmEnd = true },
                )
            },
        ) { padding ->
            Box(Modifier.fillMaxSize().padding(padding)) {
                Column(
                    Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    OverallProgress(run)
                    if (run.phase == RunPhase.PAUSED) PausedBanner(run.pauseReason, state.isConnected)
                    IntervalHero(run)
                    TargetTiles(state, run, viewModel)
                    UpNext(run, state.imperial)
                    LiveStats(state, run)
                    Spacer(Modifier.height(8.dp))
                }
                AnimatedVisibility(run.phase == RunPhase.COUNTDOWN, enter = fadeIn(), exit = fadeOut()) {
                    CountdownOverlay(run.countdown)
                }
            }
        }
    }

    if (confirmEnd) {
        AlertDialog(
            onDismissRequest = { confirmEnd = false },
            title = { Text(stringResource(R.string.end_workout_title)) },
            text = { Text(stringResource(R.string.end_workout_body)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmEnd = false
                    viewModel.end()
                }) { Text(stringResource(R.string.end_workout)) }
            },
            dismissButton = { TextButton(onClick = { confirmEnd = false }) { Text(stringResource(R.string.keep_going)) } },
        )
    }
}

@Composable
private fun OverallProgress(run: WorkoutRunState.Active) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        ProfileChart(
            run.plan,
            Modifier.fillMaxWidth().height(56.dp),
            progress = run.planProgress,
            description = stringResource(R.string.overall_progress_description, (run.planProgress * 100).toInt()),
        )
        Row(Modifier.fillMaxWidth()) {
            Text(
                stringResource(R.string.elapsed_value, Units.formatDuration((run.elapsedMs / 1000).toInt())),
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.weight(1f),
            )
            Text(
                stringResource(R.string.remaining_value, Units.formatDuration(run.totalRemainingSec)),
                style = MaterialTheme.typography.labelLarge,
            )
        }
    }
}

@Composable
private fun PausedBanner(reason: PauseReason?, connected: Boolean) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = if (reason == PauseReason.DISCONNECTED) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.tertiaryContainer,
        ),
        modifier = Modifier.fillMaxWidth().semantics { liveRegion = LiveRegionMode.Polite },
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(stringResource(R.string.run_paused), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Text(
                stringResource(
                    when {
                        reason == PauseReason.DISCONNECTED && !connected -> R.string.paused_disconnected
                        reason == PauseReason.DISCONNECTED -> R.string.paused_reconnected
                        reason == PauseReason.TREADMILL -> R.string.paused_treadmill
                        else -> R.string.paused_user
                    },
                ),
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}

/** The big ring counting down the current interval. */
@Composable
private fun IntervalHero(run: WorkoutRunState.Active) {
    val segment = run.current
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        Box(Modifier.widthIn(max = 280.dp).fillMaxWidth(0.75f).aspectRatio(1f), contentAlignment = Alignment.Center) {
            ProgressRing(run.segmentProgress, Modifier.fillMaxSize())
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    when (segment.kind) {
                        SegmentKind.WARMUP -> stringResource(R.string.segment_warmup)
                        SegmentKind.COOLDOWN -> stringResource(R.string.segment_cooldown)
                        SegmentKind.INTERVAL -> stringResource(R.string.interval_of, segment.intervalNumber ?: 0, run.plan.intervalCount)
                    }.uppercase(),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                )
                AnimatedContent(run.segmentRemainingSec, transitionSpec = { fadeIn() togetherWith fadeOut() }, label = "remaining") {
                    Text(
                        Units.formatDuration(it),
                        style = MaterialTheme.typography.displayLarge,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
                if (segment.kind == SegmentKind.INTERVAL && segment.name != null) {
                    Text(segment.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                segment.repeat?.let {
                    Text(
                        stringResource(R.string.round_of, it.round, it.of),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

/** Speed and incline: actual reading, the target, and temporary −/+ adjustments for this interval. */
@Composable
private fun TargetTiles(state: RunUiState, run: WorkoutRunState.Active, viewModel: ActiveWorkoutViewModel) {
    val imperial = state.imperial
    val data = state.treadmill.data
    val enabled = run.phase != RunPhase.COUNTDOWN
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
            TargetTile(
                label = stringResource(R.string.metric_speed),
                actual = Units.formatSpeed(data.speedKmh, imperial),
                unit = speedUnitLabel(imperial),
                target = Units.formatSpeed(run.targetSpeedKmh, imperial),
                adjusted = run.speedOverrideKmh != null,
                enabled = enabled,
                onDecrease = { viewModel.adjustSpeed(-1) },
                onIncrease = { viewModel.adjustSpeed(1) },
                modifier = Modifier.weight(1f),
            )
            TargetTile(
                label = stringResource(R.string.metric_incline),
                actual = Units.formatIncline(data.inclinePercent),
                unit = stringResource(R.string.unit_percent),
                target = WorkoutFormat.incline(run.targetInclinePercent),
                adjusted = run.inclineOverridePercent != null,
                enabled = enabled,
                onDecrease = { viewModel.adjustIncline(-1) },
                onIncrease = { viewModel.adjustIncline(1) },
                modifier = Modifier.weight(1f),
            )
        }
        AnimatedVisibility(run.isAdjusted) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Text(
                    stringResource(R.string.adjusted_note),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = viewModel::resetToPlan) {
                    Icon(Icons.AutoMirrored.Filled.Undo, contentDescription = null, modifier = Modifier.size(18.dp))
                    Text(stringResource(R.string.reset_to_plan), modifier = Modifier.padding(start = 6.dp))
                }
            }
        }
    }
}

@Composable
private fun TargetTile(
    label: String,
    actual: String,
    unit: String,
    target: String,
    adjusted: Boolean,
    enabled: Boolean,
    onDecrease: () -> Unit,
    onIncrease: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
    ) {
        Column(Modifier.padding(12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(label, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (adjusted) Badge(stringResource(R.string.adjusted), emphasized = true)
            }
            Row(verticalAlignment = Alignment.Bottom) {
                Text(actual, style = MaterialTheme.typography.displaySmall, fontWeight = FontWeight.SemiBold)
                Text(unit, style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(start = 4.dp, bottom = 6.dp))
            }
            Text(
                stringResource(R.string.target_value, target),
                style = MaterialTheme.typography.bodyMedium,
                color = if (adjusted) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.onSurfaceVariant,
                fontWeight = if (adjusted) FontWeight.SemiBold else FontWeight.Normal,
            )
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                RepeatButton(Icons.Default.Remove, "${stringResource(R.string.decrease)} $label", onDecrease, enabled, 56.dp)
                RepeatButton(Icons.Default.Add, "${stringResource(R.string.increase)} $label", onIncrease, enabled, 56.dp)
            }
        }
    }
}

@Composable
private fun UpNext(run: WorkoutRunState.Active, imperial: Boolean) {
    val next = run.next
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer), modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.up_next), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                if (next == null) {
                    Text(stringResource(R.string.up_next_finish), style = MaterialTheme.typography.titleMedium)
                } else {
                    Text(segmentTitle(next), style = MaterialTheme.typography.titleMedium)
                    Text(
                        segmentSummary(next.durationSec, next.speedKmh, next.inclinePercent, imperial),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun LiveStats(state: RunUiState, run: WorkoutRunState.Active) {
    val imperial = state.imperial
    val data = state.treadmill.data
    Row(Modifier.fillMaxWidth()) {
        Stat(stringResource(R.string.metric_distance), Units.formatDistance(run.distanceMeters.toInt(), imperial), distanceUnitLabel(imperial), Modifier.weight(1f))
        Stat(
            stringResource(R.string.metric_pace),
            Units.formatPace(data.speedKmh, imperial),
            stringResource(if (imperial) R.string.unit_min_per_mi else R.string.unit_min_per_km),
            Modifier.weight(1f),
        )
        Stat(stringResource(R.string.metric_calories), run.kcal?.toString() ?: "–", stringResource(R.string.unit_kcal), Modifier.weight(1f))
        data.heartRateBpm?.let {
            Stat(stringResource(R.string.metric_heart_rate), it.toString(), stringResource(R.string.unit_bpm), Modifier.weight(1f))
        }
    }
}

@Composable
fun Stat(label: String, value: String, unit: String, modifier: Modifier = Modifier) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center)
        if (unit.isNotEmpty()) Text(unit, style = MaterialTheme.typography.labelSmall)
    }
}

@Composable
private fun RunControls(
    run: WorkoutRunState.Active,
    connected: Boolean,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onSkip: () -> Unit,
    onEnd: () -> Unit,
) {
    Surface(tonalElevation = 3.dp) {
        Row(
            Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 24.dp, vertical = 16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedIconButton(
                onClick = onEnd,
                modifier = Modifier.size(56.dp),
                colors = IconButtonDefaults.outlinedIconButtonColors(contentColor = MaterialTheme.colorScheme.error),
            ) { Icon(Icons.Default.Stop, stringResource(R.string.end_workout)) }

            val paused = run.phase == RunPhase.PAUSED
            Button(
                onClick = if (paused) onResume else onPause,
                enabled = run.phase != RunPhase.COUNTDOWN && (!paused || connected),
                shape = CircleShape,
                modifier = Modifier.size(84.dp),
                contentPadding = ButtonDefaults.TextButtonContentPadding,
            ) {
                Icon(
                    if (paused) Icons.Default.PlayArrow else Icons.Default.Pause,
                    contentDescription = stringResource(if (paused) R.string.resume else R.string.pause),
                    modifier = Modifier.size(40.dp),
                )
            }

            FilledTonalIconButton(onClick = onSkip, enabled = run.phase != RunPhase.COUNTDOWN, modifier = Modifier.size(56.dp)) {
                Icon(Icons.Default.SkipNext, stringResource(R.string.skip_interval))
            }
        }
    }
}

@Composable
private fun CountdownOverlay(seconds: Int) {
    Surface(color = MaterialTheme.colorScheme.surface.copy(alpha = 0.92f), modifier = Modifier.fillMaxSize()) {
        Box(contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(stringResource(R.string.get_ready), style = MaterialTheme.typography.headlineSmall)
                AnimatedContent(seconds, transitionSpec = { (scaleIn() + fadeIn()) togetherWith fadeOut() }, label = "countdown") {
                    Text(
                        it.coerceAtLeast(1).toString(),
                        fontSize = 140.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Assertive },
                    )
                }
            }
        }
    }
}

private const val IDLE_GRACE_MS = 1_500L
