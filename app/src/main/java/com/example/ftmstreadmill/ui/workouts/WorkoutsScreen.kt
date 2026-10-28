package com.example.ftmstreadmill.ui.workouts

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.outlined.FileOpen
import androidx.compose.material.icons.outlined.FitnessCenter
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.ftmstreadmill.R
import com.example.ftmstreadmill.ui.common.ProfileChart
import com.example.ftmstreadmill.ui.common.Units
import com.example.ftmstreadmill.ui.common.WorkoutFormat
import com.example.ftmstreadmill.ui.common.distanceUnitLabel
import com.example.ftmstreadmill.ui.nav.ImportResult
import com.example.ftmstreadmill.workout.io.WorkoutJson
import com.example.ftmstreadmill.workout.model.Workout
import com.example.ftmstreadmill.workout.model.WorkoutPlan
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WorkoutsScreen(
    onOpenSaved: (Long) -> Unit,
    onOpenPreset: (String) -> Unit,
    onNewWorkout: () -> Unit,
    onEdit: (Long) -> Unit,
    onOpenSettings: () -> Unit,
    viewModel: WorkoutsViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val scrollBehavior = TopAppBarDefaults.enterAlwaysScrollBehavior()

    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let(viewModel::import)
    }
    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(WorkoutJson.MIME_TYPE)) { uri ->
        viewModel.writeExport(uri)
    }
    fun export(ids: Collection<Long>?) = scope.launch {
        viewModel.prepareExport(ids)?.let { exportLauncher.launch(it.fileName) }
    }

    val resources = LocalResources.current
    LaunchedEffect(viewModel) {
        viewModel.messages.collect { message ->
            val res = resources
            when (message) {
                is WorkoutsMessage.Deleted -> {
                    val result = snackbar.showSnackbar(
                        res.getString(R.string.workout_deleted, message.name),
                        actionLabel = res.getString(R.string.undo),
                        withDismissAction = true,
                    )
                    if (result == SnackbarResult.ActionPerformed) viewModel.undoDelete()
                }
                is WorkoutsMessage.Imported -> snackbar.showSnackbar(
                    when (val r = message.result) {
                        is ImportResult.Imported -> res.getQuantityString(R.plurals.imported_workouts, r.count, r.count)
                        is ImportResult.Failed -> r.reason ?: res.getString(R.string.import_failed)
                    },
                    withDismissAction = message.result is ImportResult.Failed,
                )
                WorkoutsMessage.Exported -> snackbar.showSnackbar(res.getString(R.string.export_done))
                WorkoutsMessage.ExportFailed -> snackbar.showSnackbar(res.getString(R.string.export_failed))
                is WorkoutsMessage.Copied -> snackbar.showSnackbar(res.getString(R.string.workout_copied, message.name))
            }
        }
    }

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.tab_workouts)) },
                scrollBehavior = scrollBehavior,
                actions = {
                    IconButton(onClick = { importLauncher.launch(IMPORT_TYPES) }) {
                        Icon(Icons.Outlined.FileOpen, stringResource(R.string.import_workouts))
                    }
                    IconButton(onClick = onOpenSettings) {
                        Icon(Icons.Outlined.Settings, stringResource(R.string.workout_settings))
                    }
                    var menu by remember { mutableStateOf(false) }
                    Box {
                        IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, stringResource(R.string.menu)) }
                        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.export_all)) },
                                enabled = state.saved.isNotEmpty(),
                                onClick = {
                                    menu = false
                                    export(null)
                                },
                            )
                        }
                    }
                },
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = onNewWorkout,
                icon = { Icon(Icons.Default.Add, contentDescription = null) },
                text = { Text(stringResource(R.string.new_workout)) },
                expanded = scrollBehavior.state.collapsedFraction < 0.5f,
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
                SegmentedButton(
                    selected = !state.showPresets,
                    onClick = { viewModel.showPresets(false) },
                    shape = SegmentedButtonDefaults.itemShape(0, 2),
                ) { Text(stringResource(R.string.my_workouts_count, state.saved.size)) }
                SegmentedButton(
                    selected = state.showPresets,
                    onClick = { viewModel.showPresets(true) },
                    shape = SegmentedButtonDefaults.itemShape(1, 2),
                ) { Text(stringResource(R.string.presets)) }
            }
            when {
                state.loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                state.showPresets -> WorkoutList {
                    items(state.presets, key = { it.id }) { preset ->
                        WorkoutCard(
                            workout = preset.workout,
                            imperial = state.imperial,
                            onClick = { onOpenPreset(preset.id) },
                            menu = { close ->
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.add_to_my_workouts)) },
                                    onClick = { close(); viewModel.duplicate(preset.workout) },
                                )
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.export)) },
                                    onClick = {
                                        close()
                                        exportLauncher.launch(viewModel.preparePresetExport(preset.workout).fileName)
                                    },
                                )
                            },
                        )
                    }
                }
                state.saved.isEmpty() -> EmptyWorkouts(onCreate = onNewWorkout, onBrowsePresets = { viewModel.showPresets(true) })
                else -> WorkoutList {
                    items(state.saved, key = { it.id }) { item ->
                        WorkoutCard(
                            workout = item.workout,
                            imperial = state.imperial,
                            onClick = { onOpenSaved(item.id) },
                            menu = { close ->
                                DropdownMenuItem(text = { Text(stringResource(R.string.edit)) }, onClick = { close(); onEdit(item.id) })
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.duplicate)) },
                                    onClick = { close(); viewModel.duplicate(item.workout) },
                                )
                                DropdownMenuItem(text = { Text(stringResource(R.string.export)) }, onClick = { close(); export(listOf(item.id)) })
                                DropdownMenuItem(text = { Text(stringResource(R.string.delete)) }, onClick = { close(); viewModel.delete(item) })
                            },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun WorkoutList(content: LazyListScope.() -> Unit) {
    LazyColumn(
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 96.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier.fillMaxSize(),
        content = content,
    )
}

/** A workout at a glance: name, profile, length, interval count and estimated distance. */
@Composable
fun WorkoutCard(
    workout: Workout,
    imperial: Boolean,
    onClick: () -> Unit,
    menu: @Composable (close: () -> Unit) -> Unit,
) {
    val plan = remember(workout) { WorkoutPlan.of(workout) }
    ElevatedCard(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(start = 16.dp, bottom = 16.dp, end = 4.dp, top = 4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    workout.name,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                var expanded by remember { mutableStateOf(false) }
                Box {
                    IconButton(onClick = { expanded = true }) {
                        Icon(Icons.Default.MoreVert, stringResource(R.string.workout_options, workout.name))
                    }
                    DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) { menu { expanded = false } }
                }
            }
            Column(Modifier.padding(end = 12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                workout.description?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                ProfileChart(plan, Modifier.fillMaxWidth().height(44.dp))
                Text(workoutStats(plan, imperial), style = MaterialTheme.typography.labelLarge)
            }
        }
    }
}

/** "35 min · 9 intervals · ≈2.1 mi" */
@Composable
fun workoutStats(plan: WorkoutPlan, imperial: Boolean): String = listOf(
    WorkoutFormat.approxDuration(plan.totalSec),
    pluralStringResource(R.plurals.interval_count, plan.intervalCount, plan.intervalCount),
    stringResource(
        R.string.approx_distance,
        Units.formatDistance(plan.estimatedDistanceMeters.toInt(), imperial),
        distanceUnitLabel(imperial),
    ),
).joinToString(" · ")

@Composable
private fun EmptyWorkouts(onCreate: () -> Unit, onBrowsePresets: () -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            Icons.Outlined.FitnessCenter,
            contentDescription = null,
            modifier = Modifier.size(64.dp),
            tint = MaterialTheme.colorScheme.primary,
        )
        Spacer(Modifier.height(16.dp))
        Text(stringResource(R.string.empty_workouts_title), style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(8.dp))
        Text(
            stringResource(R.string.empty_workouts_body),
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(24.dp))
        Button(onClick = onCreate) { Text(stringResource(R.string.new_workout)) }
        Spacer(Modifier.height(8.dp))
        OutlinedButton(onClick = onBrowsePresets) { Text(stringResource(R.string.browse_presets)) }
    }
}

/** Some file managers don't tag .json files correctly, so accept text and unknown types too. */
private val IMPORT_TYPES = arrayOf(WorkoutJson.MIME_TYPE, "text/*", "application/octet-stream")
