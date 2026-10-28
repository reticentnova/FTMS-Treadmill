package com.example.ftmstreadmill.ui.nav

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.DirectionsRun
import androidx.compose.material.icons.automirrored.outlined.DirectionsRun
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Speed
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteScaffold
import androidx.compose.material3.adaptive.navigationsuite.rememberNavigationSuiteScaffoldState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.navigation3.rememberViewModelStoreNavEntryDecorator
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberNavBackStack
import androidx.navigation3.runtime.rememberSaveableStateHolderNavEntryDecorator
import androidx.navigation3.ui.NavDisplay
import com.example.ftmstreadmill.R
import com.example.ftmstreadmill.ui.common.Units
import com.example.ftmstreadmill.ui.common.segmentTitle
import com.example.ftmstreadmill.ui.control.ControlScreen
import com.example.ftmstreadmill.ui.editor.WorkoutEditorScreen
import com.example.ftmstreadmill.ui.history.HistoryScreen
import com.example.ftmstreadmill.ui.history.SessionDetailScreen
import com.example.ftmstreadmill.ui.run.ActiveWorkoutScreen
import com.example.ftmstreadmill.ui.settings.WorkoutSettingsScreen
import com.example.ftmstreadmill.ui.workouts.WorkoutDetailScreen
import com.example.ftmstreadmill.ui.workouts.WorkoutsScreen
import com.example.ftmstreadmill.workout.RunPhase
import com.example.ftmstreadmill.workout.WorkoutRunState

private data class Tab(val route: Route, val label: Int, val selected: ImageVector, val unselected: ImageVector)

private val tabs = listOf(
    Tab(Route.Control, R.string.tab_treadmill, Icons.Filled.Speed, Icons.Outlined.Speed),
    Tab(Route.Workouts, R.string.tab_workouts, Icons.AutoMirrored.Filled.DirectionsRun, Icons.AutoMirrored.Outlined.DirectionsRun),
    Tab(Route.History, R.string.tab_history, Icons.Filled.History, Icons.Outlined.History),
)

/**
 * Top-level layout: an adaptive navigation bar (rail on wide screens) for the three tabs, hidden on
 * full-screen destinations, plus a "now running" bar while a workout is active.
 */
@Composable
fun AppShell(appViewModel: AppViewModel) {
    val backStack = rememberNavBackStack(Route.Control)
    val navigator = remember(backStack) { Navigator(backStack) }
    val current = backStack.last() as Route
    val showNavigation = current.isTopLevel
    val runState by appViewModel.runState.collectAsStateWithLifecycle()
    val imperial by appViewModel.imperial.collectAsStateWithLifecycle()
    val suiteState = rememberNavigationSuiteScaffoldState()
    val snackbar = remember { SnackbarHostState() }

    LaunchedEffect(showNavigation) { if (showNavigation) suiteState.show() else suiteState.hide() }

    val importedText = importMessages()
    LaunchedEffect(appViewModel) {
        appViewModel.imports.collect { result ->
            if (result is ImportResult.Imported) navigator.selectTab(Route.Workouts)
            snackbar.showSnackbar(importedText(result))
        }
    }

    NavigationSuiteScaffold(
        navigationSuiteItems = {
            tabs.forEach { tab ->
                val selected = navigator.currentTab == tab.route
                item(
                    selected = selected,
                    onClick = { navigator.selectTab(tab.route) },
                    icon = { Icon(if (selected) tab.selected else tab.unselected, contentDescription = null) },
                    label = { Text(stringResource(tab.label)) },
                )
            }
        },
        state = suiteState,
    ) {
        Box(Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize()) {
                Box(
                    Modifier
                        .weight(1f)
                        // The navigation bar already sits above the system bar.
                        .then(if (showNavigation) Modifier.consumeWindowInsets(WindowInsets.navigationBars) else Modifier),
                ) {
                    NavDisplay(
                        backStack = backStack,
                        onBack = navigator::back,
                        entryDecorators = listOf(
                            rememberSaveableStateHolderNavEntryDecorator(),
                            rememberViewModelStoreNavEntryDecorator(),
                        ),
                        entryProvider = entryProvider {
                            entry<Route.Control> { ControlScreen() }
                            entry<Route.Workouts> {
                                WorkoutsScreen(
                                    onOpenSaved = { navigator.push(Route.WorkoutDetail(savedId = it)) },
                                    onOpenPreset = { navigator.push(Route.WorkoutDetail(presetId = it)) },
                                    onNewWorkout = { navigator.push(Route.Editor()) },
                                    onEdit = { navigator.push(Route.Editor(workoutId = it)) },
                                    onOpenSettings = { navigator.push(Route.WorkoutSettings) },
                                )
                            }
                            entry<Route.WorkoutDetail> { key ->
                                WorkoutDetailScreen(
                                    route = key,
                                    onBack = navigator::back,
                                    onEdit = { navigator.push(Route.Editor(workoutId = it)) },
                                    onCustomize = { navigator.push(Route.Editor(presetId = it)) },
                                    onStarted = navigator::openRun,
                                    onConnect = { navigator.selectTab(Route.Control) },
                                )
                            }
                            entry<Route.Editor> { key ->
                                WorkoutEditorScreen(
                                    route = key,
                                    onClose = navigator::back,
                                    onSaved = { id ->
                                        if (key.workoutId == null) navigator.replace(Route.WorkoutDetail(savedId = id)) else navigator.back()
                                    },
                                )
                            }
                            entry<Route.Run> {
                                ActiveWorkoutScreen(
                                    onMinimize = navigator::back,
                                    onDone = navigator::back,
                                )
                            }
                            entry<Route.History> {
                                HistoryScreen(
                                    onOpen = { navigator.push(Route.SessionDetail(it)) },
                                    onBrowseWorkouts = { navigator.selectTab(Route.Workouts) },
                                )
                            }
                            entry<Route.SessionDetail> { key ->
                                SessionDetailScreen(
                                    sessionId = key.sessionId,
                                    onBack = navigator::back,
                                    onStarted = navigator::openRun,
                                )
                            }
                            entry<Route.WorkoutSettings> { WorkoutSettingsScreen(onBack = navigator::back) }
                        },
                    )
                }
                if (showNavigation && runState !is WorkoutRunState.Idle) {
                    NowRunningBar(
                        state = runState,
                        imperial = imperial,
                        onOpen = navigator::openRun,
                        onPause = appViewModel.engine::pause,
                        onResume = appViewModel.engine::resume,
                    )
                }
            }
            SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).padding(bottom = 8.dp))
        }
    }
}

/** A compact "media player" style bar that leads back to the running workout. */
@Composable
private fun NowRunningBar(
    state: WorkoutRunState,
    imperial: Boolean,
    onOpen: () -> Unit,
    onPause: () -> Unit,
    onResume: () -> Unit,
) {
    Surface(
        color = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
        modifier = Modifier.fillMaxWidth().clickable(onClickLabel = stringResource(R.string.open_workout), onClick = onOpen),
    ) {
        Column {
            when (state) {
                is WorkoutRunState.Active -> LinearProgressIndicator(
                    progress = { state.planProgress },
                    modifier = Modifier.fillMaxWidth(),
                    drawStopIndicator = {},
                )
                else -> Unit
            }
            Row(
                Modifier.padding(start = 16.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    when (state) {
                        is WorkoutRunState.Active -> {
                            Text(state.workout.name, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            val detail = when (state.phase) {
                                RunPhase.COUNTDOWN -> stringResource(R.string.run_starting)
                                RunPhase.PAUSED -> stringResource(R.string.run_paused)
                                RunPhase.RUNNING -> stringResource(
                                    R.string.now_running_detail,
                                    segmentTitle(state.current),
                                    Units.formatDuration(state.segmentRemainingSec),
                                    Units.formatSpeed(state.targetSpeedKmh, imperial),
                                    stringResource(if (imperial) R.string.unit_mph else R.string.unit_kmh),
                                )
                            }
                            Text(detail, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        is WorkoutRunState.Finished -> {
                            Text(state.session.workoutName, style = MaterialTheme.typography.titleSmall, maxLines = 1)
                            Text(stringResource(R.string.now_running_finished), style = MaterialTheme.typography.bodySmall)
                        }
                        WorkoutRunState.Idle -> Unit
                    }
                }
                when {
                    state is WorkoutRunState.Active && state.phase == RunPhase.RUNNING ->
                        IconButton(onClick = onPause) { Icon(Icons.Default.Pause, stringResource(R.string.pause)) }
                    state is WorkoutRunState.Active && state.phase == RunPhase.PAUSED ->
                        IconButton(onClick = onResume) { Icon(Icons.Default.PlayArrow, stringResource(R.string.resume)) }
                    state is WorkoutRunState.Finished ->
                        Icon(Icons.Default.CheckCircle, contentDescription = null, modifier = Modifier.padding(12.dp).size(24.dp))
                    else -> Unit
                }
            }
        }
    }
}

@Composable
private fun importMessages(): (ImportResult) -> String {
    val res = LocalResources.current
    val failed = stringResource(R.string.import_failed)
    return { result ->
        when (result) {
            is ImportResult.Imported -> res.getQuantityString(R.plurals.imported_workouts, result.count, result.count)
            is ImportResult.Failed -> result.reason ?: failed
        }
    }
}
