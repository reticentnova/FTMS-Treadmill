package com.example.ftmstreadmill.ui.history

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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.outlined.History
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.ftmstreadmill.R
import com.example.ftmstreadmill.ui.common.Badge
import com.example.ftmstreadmill.ui.common.HealthConnectButton
import com.example.ftmstreadmill.ui.common.Units
import com.example.ftmstreadmill.ui.common.WorkoutFormat
import com.example.ftmstreadmill.ui.common.distanceUnitLabel
import com.example.ftmstreadmill.ui.run.SessionSegments
import com.example.ftmstreadmill.ui.run.SessionStats
import com.example.ftmstreadmill.ui.run.Stat
import com.example.ftmstreadmill.ui.run.endReasonText
import com.example.ftmstreadmill.workout.model.Session
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HistoryScreen(
    onOpen: (Long) -> Unit,
    onBrowseWorkouts: () -> Unit,
    viewModel: HistoryViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val scrollBehavior = TopAppBarDefaults.enterAlwaysScrollBehavior()
    val locale = LocalConfiguration.current.locales[0]
    val monthFormat = remember(locale) { DateTimeFormatter.ofPattern("LLLL yyyy", locale) }
    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = { TopAppBar(title = { Text(stringResource(R.string.tab_history)) }, scrollBehavior = scrollBehavior) },
    ) { padding ->
        when {
            state.loading -> Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            state.isEmpty -> EmptyHistory(Modifier.padding(padding), onBrowseWorkouts)
            else -> LazyColumn(
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = padding.calculateTopPadding() + 8.dp, bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                item(key = "week") { WeekCard(state) }
                state.months.forEach { (month, sessions) ->
                    item(key = "m$month") {
                        Text(
                            month.format(monthFormat),
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(top = 12.dp, bottom = 2.dp),
                        )
                    }
                    items(sessions, key = { it.id }) { SessionRow(it, state.imperial) { onOpen(it.id) } }
                }
            }
        }
    }
}

@Composable
private fun WeekCard(state: HistoryUiState) {
    val week = state.thisWeek
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(stringResource(R.string.this_week), style = MaterialTheme.typography.titleMedium)
            Row(Modifier.fillMaxWidth()) {
                Stat(stringResource(R.string.workouts_label), week.sessions.toString(), "", Modifier.weight(1f))
                Stat(stringResource(R.string.metric_time), WorkoutFormat.approxDuration(week.activeSec), "", Modifier.weight(1f))
                Stat(
                    stringResource(R.string.metric_distance),
                    Units.formatDistance(week.distanceMeters, state.imperial),
                    distanceUnitLabel(state.imperial),
                    Modifier.weight(1f),
                )
            }
        }
    }
}

@Composable
private fun SessionRow(session: Session, imperial: Boolean, onClick: () -> Unit) {
    ElevatedCard(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    session.workoutName,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Badge(
                    stringResource(if (session.completed) R.string.completed else R.string.ended_early),
                    emphasized = session.completed,
                )
            }
            Text(formatDateTime(session.startedAt), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(
                listOfNotNull(
                    Units.formatDuration(session.activeSec),
                    session.distanceMeters?.let { "${Units.formatDistance(it, imperial)} ${distanceUnitLabel(imperial)}" },
                    session.kcal?.let { "$it ${stringResource(R.string.unit_kcal)}" },
                ).joinToString(" · "),
                style = MaterialTheme.typography.labelLarge,
            )
        }
    }
}

@Composable
private fun EmptyHistory(modifier: Modifier, onBrowseWorkouts: () -> Unit) {
    Column(
        modifier.fillMaxSize().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(Icons.Outlined.History, contentDescription = null, modifier = Modifier.size(64.dp), tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.height(16.dp))
        Text(stringResource(R.string.empty_history_title), style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(8.dp))
        Text(
            stringResource(R.string.empty_history_body),
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(24.dp))
        Button(onClick = onBrowseWorkouts) { Text(stringResource(R.string.browse_workouts)) }
    }
}

private fun formatDateTime(epochMs: Long): String =
    Instant.ofEpochMilli(epochMs).atZone(ZoneId.systemDefault())
        .format(DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT))

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SessionDetailScreen(sessionId: Long, onBack: () -> Unit, onStarted: () -> Unit) {
    val viewModel = hiltViewModel<SessionDetailViewModel, SessionDetailViewModel.Factory>(
        key = "session-$sessionId",
        creationCallback = { it.create(sessionId) },
    )
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    var confirmDelete by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(state.session?.workoutName.orEmpty(), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back)) }
                },
                actions = {
                    IconButton(onClick = { confirmDelete = true }, enabled = state.session != null) {
                        Icon(Icons.Default.Delete, stringResource(R.string.delete))
                    }
                },
            )
        },
    ) { padding ->
        val session = state.session
        if (session == null) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                if (state.loading) CircularProgressIndicator() else Text(stringResource(R.string.session_not_found))
            }
            return@Scaffold
        }
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(formatDateTime(session.startedAt), style = MaterialTheme.typography.bodyLarge)
                    endReasonText(session.endReason)?.let {
                        Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                Badge(stringResource(if (session.completed) R.string.completed else R.string.ended_early), emphasized = session.completed)
            }
            SessionStats(session, state.imperial)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                if (state.canRunAgain) {
                    FilledTonalButton(onClick = { if (viewModel.runAgain()) onStarted() }) {
                        Icon(Icons.Default.PlayArrow, contentDescription = null)
                        Text(stringResource(R.string.run_again), modifier = Modifier.padding(start = 6.dp))
                    }
                }
                HealthConnectButton(session.healthConnectExported, viewModel.healthConnectAvailability, viewModel::export)
            }
            SessionSegments(session, state.imperial)
        }
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text(stringResource(R.string.delete_session_title)) },
            text = { Text(stringResource(R.string.delete_session_body)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    viewModel.delete(onBack)
                }) { Text(stringResource(R.string.delete)) }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}
