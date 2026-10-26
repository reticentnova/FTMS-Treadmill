package com.example.ftmstreadmill.ui.workouts

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.ftmstreadmill.R
import com.example.ftmstreadmill.data.ConnectionState
import com.example.ftmstreadmill.ui.common.ProfileChart
import com.example.ftmstreadmill.ui.common.segmentSummary
import com.example.ftmstreadmill.ui.nav.Route
import com.example.ftmstreadmill.workout.io.WorkoutJson
import com.example.ftmstreadmill.workout.model.Repeat
import com.example.ftmstreadmill.workout.model.Segment
import com.example.ftmstreadmill.workout.model.SpeedUnit
import com.example.ftmstreadmill.workout.model.Workout
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WorkoutDetailScreen(
    route: Route.WorkoutDetail,
    onBack: () -> Unit,
    onEdit: (Long) -> Unit,
    onCustomize: (String) -> Unit,
    onStarted: () -> Unit,
    onConnect: () -> Unit,
) {
    val viewModel = hiltViewModel<WorkoutDetailViewModel, WorkoutDetailViewModel.Factory>(
        key = route.toString(),
        creationCallback = { it.create(route) },
    )
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    val resources = LocalResources.current
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    var confirmDelete by remember { mutableStateOf(false) }
    var showBusy by remember { mutableStateOf(false) }

    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(WorkoutJson.MIME_TYPE)) { uri ->
        viewModel.writeExport(uri) { ok ->
            scope.launch { snackbar.showSnackbar(resources.getString(if (ok) R.string.export_done else R.string.export_failed)) }
        }
    }

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            LargeTopAppBar(
                title = { Text(state.workout?.name.orEmpty(), maxLines = 2, overflow = TextOverflow.Ellipsis) },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back)) }
                },
                actions = {
                    if (state.workout != null) {
                        if (state.isPreset) {
                            TextButton(onClick = { state.presetId?.let(onCustomize) }) { Text(stringResource(R.string.customize)) }
                        } else {
                            IconButton(onClick = { state.savedId?.let(onEdit) }) { Icon(Icons.Default.Edit, stringResource(R.string.edit)) }
                        }
                        var menu by remember { mutableStateOf(false) }
                        Box {
                            IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, stringResource(R.string.menu)) }
                            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                                DropdownMenuItem(
                                    text = { Text(stringResource(if (state.isPreset) R.string.add_to_my_workouts else R.string.duplicate)) },
                                    onClick = {
                                        menu = false
                                        scope.launch {
                                            viewModel.saveCopy()
                                            snackbar.showSnackbar(resources.getString(R.string.workout_copied, state.workout?.name.orEmpty()))
                                        }
                                    },
                                )
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.export)) },
                                    onClick = {
                                        menu = false
                                        exportLauncher.launch(viewModel.exportFileName())
                                    },
                                )
                                if (!state.isPreset) {
                                    DropdownMenuItem(
                                        text = { Text(stringResource(R.string.delete)) },
                                        onClick = {
                                            menu = false
                                            confirmDelete = true
                                        },
                                    )
                                }
                            }
                        }
                    }
                },
                scrollBehavior = scrollBehavior,
            )
        },
        bottomBar = {
            if (state.workout != null) {
                StartPanel(
                    state = state,
                    onStart = {
                        if (viewModel.start()) onStarted() else showBusy = true
                    },
                    onOpenRunning = onStarted,
                    onConnect = { scope.launch { if (!viewModel.connect()) onConnect() } },
                )
            }
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        val workout = state.workout
        val plan = state.plan
        when {
            state.loading -> Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            workout == null || plan == null -> Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                Text(stringResource(R.string.workout_not_found))
            }
            else -> LazyColumn(
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = padding.calculateTopPadding(), bottom = padding.calculateBottomPadding() + 16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                workout.description?.let { item { Text(it, style = MaterialTheme.typography.bodyLarge) } }
                item { Text(workoutStats(plan, state.imperial), style = MaterialTheme.typography.titleSmall) }
                item {
                    ProfileChart(
                        plan,
                        Modifier.fillMaxWidth().height(140.dp),
                        description = stringResource(R.string.profile_chart_description),
                    )
                }
                item {
                    Text(
                        stringResource(R.string.segments),
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
                item { WorkoutStructure(workout, state.imperial) }
            }
        }
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text(stringResource(R.string.delete_workout_title)) },
            text = { Text(stringResource(R.string.delete_workout_body, state.workout?.name.orEmpty())) },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    viewModel.delete(onBack)
                }) { Text(stringResource(R.string.delete)) }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text(stringResource(R.string.cancel)) } },
        )
    }
    if (showBusy) {
        AlertDialog(
            onDismissRequest = { showBusy = false },
            title = { Text(stringResource(R.string.already_running_title)) },
            text = { Text(stringResource(R.string.already_running_body)) },
            confirmButton = {
                TextButton(onClick = {
                    showBusy = false
                    onStarted()
                }) { Text(stringResource(R.string.open_workout)) }
            },
            dismissButton = { TextButton(onClick = { showBusy = false }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}

/** The bottom action area: Start when the treadmill is ready, otherwise what's needed to get there. */
@Composable
private fun StartPanel(
    state: WorkoutDetailUiState,
    onStart: () -> Unit,
    onOpenRunning: () -> Unit,
    onConnect: () -> Unit,
) {
    Surface(tonalElevation = 3.dp, shadowElevation = 3.dp) {
        Column(
            Modifier.fillMaxWidth().navigationBarsPadding().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            val connection = state.connection
            val buttonModifier = Modifier.fillMaxWidth().height(56.dp)
            when {
                state.isThisRunning -> Button(onClick = onOpenRunning, modifier = buttonModifier) {
                    Text(stringResource(R.string.open_workout), style = MaterialTheme.typography.titleMedium)
                }
                connection is ConnectionState.Ready -> {
                    if (!connection.hasControl) {
                        Text(
                            stringResource(R.string.monitor_only_warning),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                    Button(onClick = onStart, modifier = buttonModifier) {
                        Icon(Icons.Default.PlayArrow, contentDescription = null)
                        Text(
                            stringResource(R.string.start_workout),
                            style = MaterialTheme.typography.titleMedium,
                            modifier = Modifier.padding(start = 8.dp),
                        )
                    }
                }
                connection is ConnectionState.Connecting || connection is ConnectionState.Initializing ||
                    connection is ConnectionState.Reconnecting -> FilledTonalButton(onClick = {}, enabled = false, modifier = buttonModifier) {
                    CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                    Text(stringResource(R.string.state_connecting), modifier = Modifier.padding(start = 12.dp))
                }
                else -> {
                    Text(
                        stringResource(R.string.connect_to_start),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    FilledTonalButton(onClick = onConnect, modifier = buttonModifier) {
                        Icon(Icons.Default.Bluetooth, contentDescription = null)
                        Text(stringResource(R.string.connect_treadmill), modifier = Modifier.padding(start = 8.dp))
                    }
                }
            }
        }
    }
}

/** Warmup, intervals (with repeat blocks grouped) and cooldown as written. */
@Composable
fun WorkoutStructure(workout: Workout, imperial: Boolean) {
    val units = workout.units
    val maxIncline = remember(workout) { maxOf(10.0, maxIncline(workout)) }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        workout.warmup?.let { SegmentRow(stringResource(R.string.segment_warmup), it, units, imperial, null) }
        var number = 0
        workout.intervals.forEach { step ->
            when (step) {
                is Segment -> {
                    number++
                    SegmentRow(step.name ?: stringResource(R.string.segment_interval_n, number), step, units, imperial, step.incline / maxIncline)
                }
                is Repeat -> OutlinedCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Repeat, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                            Text(
                                stringResource(R.string.repeat_times, step.times),
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.SemiBold,
                                modifier = Modifier.padding(start = 8.dp),
                            )
                        }
                        val start = number
                        step.intervals.forEachIndexed { i, s ->
                            SegmentRow(s.name ?: stringResource(R.string.segment_interval_n, start + i + 1), s, units, imperial, s.incline / maxIncline)
                        }
                        number += step.times * step.intervals.size
                    }
                }
            }
        }
        workout.cooldown?.let { SegmentRow(stringResource(R.string.segment_cooldown), it, units, imperial, null) }
    }
}

@Composable
private fun SegmentRow(title: String, segment: Segment, units: SpeedUnit, imperial: Boolean, intensity: Double?) {
    val colors = MaterialTheme.colorScheme
    val accent = intensity?.let { lerp(colors.primary, colors.tertiary, it.toFloat().coerceIn(0f, 1f)) } ?: colors.secondary.copy(alpha = 0.55f)
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Box(Modifier.width(6.dp).height(40.dp).background(accent, RoundedCornerShape(3.dp)))
        Column(Modifier.padding(start = 12.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(
                segmentSummary(segment.durationSec, units.toKmh(segment.speed), segment.incline, imperial),
                style = MaterialTheme.typography.bodyMedium,
                color = colors.onSurfaceVariant,
            )
        }
    }
}

private fun maxIncline(workout: Workout): Double = workout.intervals.flatMap { step ->
    when (step) {
        is Segment -> listOf(step.incline)
        is Repeat -> step.intervals.map { it.incline }
    }
}.maxOrNull() ?: 0.0
