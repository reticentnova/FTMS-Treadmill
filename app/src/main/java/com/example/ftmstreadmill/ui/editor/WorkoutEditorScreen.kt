package com.example.ftmstreadmill.ui.editor

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.ftmstreadmill.R
import com.example.ftmstreadmill.ui.common.DurationEditDialog
import com.example.ftmstreadmill.ui.common.NumberEditDialog
import com.example.ftmstreadmill.ui.common.ProfileChart
import com.example.ftmstreadmill.ui.common.Units
import com.example.ftmstreadmill.ui.common.ValueStepper
import com.example.ftmstreadmill.ui.common.WorkoutFormat
import com.example.ftmstreadmill.ui.common.speedUnitLabel
import com.example.ftmstreadmill.ui.nav.Route
import com.example.ftmstreadmill.ui.workouts.workoutStats
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState

private enum class Field { DURATION, SPEED, INCLINE }

private data class EditTarget(val key: Long, val field: Field, val segment: DraftSegment)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WorkoutEditorScreen(route: Route.Editor, onClose: () -> Unit, onSaved: (Long) -> Unit) {
    val viewModel = hiltViewModel<WorkoutEditorViewModel, WorkoutEditorViewModel.Factory>(
        key = route.toString(),
        creationCallback = { it.create(route) },
    )
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val resources = LocalResources.current
    var confirmDiscard by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<EditTarget?>(null) }

    BackHandler(enabled = state.dirty) { confirmDiscard = true }

    LaunchedEffect(viewModel) {
        viewModel.messages.collect { message ->
            when (message) {
                is EditorMessage.Removed -> {
                    val result = snackbar.showSnackbar(
                        resources.getString(R.string.interval_removed),
                        actionLabel = resources.getString(R.string.undo),
                        withDismissAction = true,
                    )
                    if (result == SnackbarResult.ActionPerformed) viewModel.undoRemove()
                }
                EditorMessage.NameMissing -> snackbar.showSnackbar(resources.getString(R.string.editor_name_missing))
                EditorMessage.NeedsInterval -> snackbar.showSnackbar(resources.getString(R.string.editor_needs_interval))
                is EditorMessage.Invalid -> snackbar.showSnackbar(message.reason, withDismissAction = true)
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(if (state.isNew) R.string.new_workout else R.string.edit_workout)) },
                navigationIcon = {
                    IconButton(onClick = { if (state.dirty) confirmDiscard = true else onClose() }) {
                        Icon(Icons.Default.Close, stringResource(R.string.close))
                    }
                },
                actions = {
                    Button(
                        onClick = { viewModel.save(onSaved) },
                        enabled = !state.saving && state.draft != null,
                        modifier = Modifier.padding(end = 8.dp),
                    ) { Text(stringResource(R.string.save)) }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        val draft = state.draft
        if (draft == null) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            return@Scaffold
        }
        val imperial = state.imperial
        val plan = remember(draft) { state.plan }
        val unit = speedUnitLabel(imperial)
        val listState = rememberLazyListState()
        val haptics = LocalHapticFeedback.current
        val reorderState = rememberReorderableLazyListState(listState) { from, to ->
            val fromKey = from.key as? Long ?: return@rememberReorderableLazyListState
            val toKey = to.key as? Long ?: return@rememberReorderableLazyListState
            viewModel.move(fromKey, toKey)
            haptics.performHapticFeedback(HapticFeedbackType.SegmentFrequentTick)
        }

        Column(Modifier.fillMaxSize().padding(padding).imePadding()) {
            // Pinned summary so the shape of the workout is always visible while editing.
            Surface(tonalElevation = 1.dp) {
                Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
                    if (plan != null && plan.segments.isNotEmpty()) {
                        ProfileChart(plan, Modifier.fillMaxWidth().height(56.dp), description = stringResource(R.string.profile_chart_description))
                        Text(workoutStats(plan, imperial), style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 6.dp))
                    }
                }
            }
            LazyColumn(
                state = listState,
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.fillMaxSize(),
            ) {
                item(key = "name") {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            value = draft.name,
                            onValueChange = viewModel::setName,
                            label = { Text(stringResource(R.string.workout_name)) },
                            isError = state.nameError,
                            supportingText = if (state.nameError) ({ Text(stringResource(R.string.editor_name_missing)) }) else null,
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words),
                            modifier = Modifier.fillMaxWidth(),
                        )
                        OutlinedTextField(
                            value = draft.description,
                            onValueChange = viewModel::setDescription,
                            label = { Text(stringResource(R.string.workout_description)) },
                            maxLines = 3,
                            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
                item(key = "warmup") {
                    BookendCard(
                        title = stringResource(R.string.segment_warmup),
                        enabled = draft.warmupEnabled,
                        onEnabledChange = viewModel::setWarmupEnabled,
                        segment = draft.warmup,
                        expanded = state.expandedKey == draft.warmup.key,
                        imperial = imperial,
                        onToggle = { viewModel.toggleExpanded(draft.warmup.key) },
                    ) {
                        SegmentFields(draft.warmup, unit, viewModel, onEdit = { field -> editing = EditTarget(draft.warmup.key, field, draft.warmup) })
                    }
                }
                item(key = "intervals-header") {
                    Text(
                        stringResource(R.string.intervals),
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
                var number = 0
                val numbers = draft.steps.associate { step ->
                    val first = number + 1
                    number += when (step) {
                        is DraftInterval -> 1
                        is DraftRepeat -> step.intervals.size * step.times
                    }
                    step.key to first
                }
                items(draft.steps, key = { it.key }) { step ->
                    ReorderableItem(reorderState, key = step.key) { dragging ->
                        val handle = Modifier.draggableHandle(
                            onDragStarted = { haptics.performHapticFeedback(HapticFeedbackType.GestureThresholdActivate) },
                            onDragStopped = { haptics.performHapticFeedback(HapticFeedbackType.GestureEnd) },
                        )
                        SwipeToDelete(onDelete = { viewModel.remove(step.key) }) {
                            when (step) {
                                is DraftInterval -> IntervalCard(
                                    segment = step.segment,
                                    title = step.segment.name.ifBlank { stringResource(R.string.segment_interval_n, numbers[step.key] ?: 0) },
                                    expanded = state.expandedKey == step.key,
                                    imperial = imperial,
                                    maxIncline = state.inclineMax,
                                    dragging = dragging,
                                    handle = handle,
                                    onToggle = { viewModel.toggleExpanded(step.key) },
                                ) {
                                    NameField(step.segment, viewModel)
                                    SegmentFields(step.segment, unit, viewModel, onEdit = { editing = EditTarget(step.key, it, step.segment) })
                                    ItemActions(onDuplicate = { viewModel.duplicate(step.key) }, onDelete = { viewModel.remove(step.key) })
                                }
                                is DraftRepeat -> RepeatCard(
                                    repeat = step,
                                    firstNumber = numbers[step.key] ?: 1,
                                    state = state,
                                    unit = unit,
                                    dragging = dragging,
                                    handle = handle,
                                    viewModel = viewModel,
                                    onEdit = { key, field, seg -> editing = EditTarget(key, field, seg) },
                                )
                            }
                        }
                    }
                }
                item(key = "add") {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                        FilledTonalButton(onClick = viewModel::addInterval, modifier = Modifier.weight(1f).height(48.dp)) {
                            Icon(Icons.Default.Add, contentDescription = null)
                            Text(stringResource(R.string.add_interval), modifier = Modifier.padding(start = 6.dp))
                        }
                        OutlinedButton(onClick = viewModel::addRepeat, modifier = Modifier.weight(1f).height(48.dp)) {
                            Icon(Icons.Default.Repeat, contentDescription = null)
                            Text(stringResource(R.string.add_repeat), modifier = Modifier.padding(start = 6.dp))
                        }
                    }
                }
                item(key = "cooldown") {
                    BookendCard(
                        title = stringResource(R.string.segment_cooldown),
                        enabled = draft.cooldownEnabled,
                        onEnabledChange = viewModel::setCooldownEnabled,
                        segment = draft.cooldown,
                        expanded = state.expandedKey == draft.cooldown.key,
                        imperial = imperial,
                        onToggle = { viewModel.toggleExpanded(draft.cooldown.key) },
                    ) {
                        SegmentFields(draft.cooldown, unit, viewModel, onEdit = { field -> editing = EditTarget(draft.cooldown.key, field, draft.cooldown) })
                    }
                }
                item(key = "bottom") { Spacer(Modifier.height(48.dp)) }
            }
        }
    }

    editing?.let { target ->
        val close = { editing = null }
        val imperial = state.imperial
        when (target.field) {
            Field.DURATION -> DurationEditDialog(
                title = stringResource(R.string.duration),
                initialSec = target.segment.durationSec,
                onDismiss = close,
                onConfirm = { sec -> viewModel.updateSegment(target.key) { it.copy(durationSec = sec) }; close() },
            )
            Field.SPEED -> NumberEditDialog(
                title = stringResource(R.string.metric_speed),
                initial = WorkoutFormat.number(target.segment.speed),
                unit = speedUnitLabel(imperial),
                onDismiss = close,
                onConfirm = { v -> viewModel.updateSegment(target.key) { it.copy(speed = v) }; close() },
            )
            Field.INCLINE -> NumberEditDialog(
                title = stringResource(R.string.metric_incline),
                initial = WorkoutFormat.incline(target.segment.incline),
                unit = stringResource(R.string.unit_percent),
                onDismiss = close,
                onConfirm = { v -> viewModel.updateSegment(target.key) { it.copy(incline = v) }; close() },
            )
        }
    }

    if (confirmDiscard) {
        AlertDialog(
            onDismissRequest = { confirmDiscard = false },
            title = { Text(stringResource(R.string.discard_changes_title)) },
            text = { Text(stringResource(R.string.discard_changes_body)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmDiscard = false
                    onClose()
                }) { Text(stringResource(R.string.discard)) }
            },
            dismissButton = { TextButton(onClick = { confirmDiscard = false }) { Text(stringResource(R.string.keep_editing)) } },
        )
    }
}

/** Duration, speed and incline steppers for one segment. */
@Composable
private fun SegmentFields(segment: DraftSegment, speedUnit: String, viewModel: WorkoutEditorViewModel, onEdit: (Field) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        ValueStepper(
            label = stringResource(R.string.duration),
            value = Units.formatDuration(segment.durationSec),
            unit = "",
            onDecrease = { viewModel.stepDuration(segment.key, up = false) },
            onIncrease = { viewModel.stepDuration(segment.key, up = true) },
            onEdit = { onEdit(Field.DURATION) },
        )
        ValueStepper(
            label = stringResource(R.string.metric_speed),
            value = WorkoutFormat.number(segment.speed),
            unit = speedUnit,
            onDecrease = { viewModel.stepSpeed(segment.key, up = false) },
            onIncrease = { viewModel.stepSpeed(segment.key, up = true) },
            onEdit = { onEdit(Field.SPEED) },
        )
        ValueStepper(
            label = stringResource(R.string.metric_incline),
            value = WorkoutFormat.incline(segment.incline),
            unit = stringResource(R.string.unit_percent),
            onDecrease = { viewModel.stepIncline(segment.key, up = false) },
            onIncrease = { viewModel.stepIncline(segment.key, up = true) },
            onEdit = { onEdit(Field.INCLINE) },
        )
    }
}

@Composable
private fun NameField(segment: DraftSegment, viewModel: WorkoutEditorViewModel) {
    OutlinedTextField(
        value = segment.name,
        onValueChange = { name -> viewModel.updateSegment(segment.key) { it.copy(name = name) } },
        label = { Text(stringResource(R.string.interval_name)) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun ItemActions(onDuplicate: () -> Unit, onDelete: () -> Unit, extra: @Composable () -> Unit = {}) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
        extra()
        TextButton(onClick = onDuplicate) {
            Icon(Icons.Default.ContentCopy, contentDescription = null, modifier = Modifier.size(18.dp))
            Text(stringResource(R.string.duplicate), modifier = Modifier.padding(start = 6.dp))
        }
        TextButton(onClick = onDelete) {
            Icon(Icons.Default.Delete, contentDescription = null, modifier = Modifier.size(18.dp))
            Text(stringResource(R.string.delete), modifier = Modifier.padding(start = 6.dp))
        }
    }
}

/** Swipe left to delete; the parent shows an Undo snackbar. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SwipeToDelete(onDelete: () -> Unit, content: @Composable () -> Unit) {
    val dismissState = rememberSwipeToDismissBoxState()
    SwipeToDismissBox(
        state = dismissState,
        enableDismissFromStartToEnd = false,
        onDismiss = { if (it == SwipeToDismissBoxValue.EndToStart) onDelete() },
        backgroundContent = {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.errorContainer, RoundedCornerShape(12.dp))
                    .padding(horizontal = 24.dp),
                contentAlignment = Alignment.CenterEnd,
            ) {
                Icon(Icons.Default.Delete, stringResource(R.string.delete), tint = MaterialTheme.colorScheme.onErrorContainer)
            }
        },
    ) { content() }
}

/** Warmup or cooldown: a switch to include it plus the same fields as an interval. */
@Composable
private fun BookendCard(
    title: String,
    enabled: Boolean,
    onEnabledChange: (Boolean) -> Unit,
    segment: DraftSegment,
    expanded: Boolean,
    imperial: Boolean,
    onToggle: () -> Unit,
    content: @Composable () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth().animateContentSize(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
    ) {
        Row(
            Modifier.fillMaxWidth().clickable(enabled = enabled, onClick = onToggle).padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                Text(
                    if (enabled) draftSummary(segment, imperial) else stringResource(R.string.not_included),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(checked = enabled, onCheckedChange = onEnabledChange)
        }
        AnimatedVisibility(enabled && expanded) {
            Column(Modifier.padding(start = 16.dp, end = 16.dp, bottom = 16.dp)) { content() }
        }
    }
}

@Suppress("ModifierParameter")
@Composable
private fun IntervalCard(
    segment: DraftSegment,
    title: String,
    expanded: Boolean,
    imperial: Boolean,
    maxIncline: Double,
    dragging: Boolean,
    handle: Modifier?,
    onToggle: () -> Unit,
    nested: Boolean = false,
    content: @Composable () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val accent = lerp(colors.primary, colors.tertiary, (segment.incline / maxOf(maxIncline, 1.0)).toFloat().coerceIn(0f, 1f))
    val container = if (nested) colors.surfaceContainerLow else colors.surfaceContainerLowest
    ElevatedCard(
        modifier = Modifier.fillMaxWidth().animateContentSize(),
        colors = CardDefaults.elevatedCardColors(containerColor = container),
        elevation = CardDefaults.elevatedCardElevation(defaultElevation = if (dragging) 8.dp else if (nested) 0.dp else 1.dp),
    ) {
        Row(
            Modifier.fillMaxWidth().clickable(onClick = onToggle).padding(vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (handle != null) {
                val label = stringResource(R.string.reorder)
                Icon(
                    Icons.Default.DragHandle,
                    contentDescription = null,
                    tint = colors.onSurfaceVariant,
                    modifier = handle.padding(horizontal = 12.dp).size(24.dp).semantics { contentDescription = label },
                )
            } else {
                Spacer(Modifier.width(16.dp))
            }
            Box(Modifier.size(10.dp).background(accent, CircleShape))
            Column(Modifier.weight(1f).padding(start = 12.dp)) {
                Text(title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(draftSummary(segment, imperial), style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant)
            }
            Icon(
                if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                contentDescription = stringResource(if (expanded) R.string.collapse else R.string.expand),
                modifier = Modifier.padding(horizontal = 12.dp),
            )
        }
        AnimatedVisibility(expanded) {
            Column(Modifier.padding(start = 16.dp, end = 16.dp, bottom = 8.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                content()
            }
        }
    }
}

@Suppress("ModifierParameter")
@Composable
private fun RepeatCard(
    repeat: DraftRepeat,
    firstNumber: Int,
    state: EditorUiState,
    unit: String,
    dragging: Boolean,
    handle: Modifier,
    viewModel: WorkoutEditorViewModel,
    onEdit: (Long, Field, DraftSegment) -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    Card(
        modifier = Modifier.fillMaxWidth().animateContentSize(),
        colors = CardDefaults.cardColors(containerColor = colors.secondaryContainer.copy(alpha = 0.5f)),
        elevation = CardDefaults.cardElevation(defaultElevation = if (dragging) 8.dp else 0.dp),
    ) {
        Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            val label = stringResource(R.string.reorder)
            Icon(
                Icons.Default.DragHandle,
                contentDescription = null,
                tint = colors.onSurfaceVariant,
                modifier = handle.padding(horizontal = 12.dp).size(24.dp).semantics { contentDescription = label },
            )
            Icon(Icons.Default.Repeat, contentDescription = null, tint = colors.primary)
            Text(
                stringResource(R.string.repeat),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(start = 8.dp).weight(1f),
            )
            ValueStepper(
                label = stringResource(R.string.repetitions),
                showLabel = false,
                value = "×${repeat.times}",
                unit = "",
                onDecrease = { viewModel.setRepeatTimes(repeat.key, repeat.times - 1) },
                onIncrease = { viewModel.setRepeatTimes(repeat.key, repeat.times + 1) },
                compact = true,
                modifier = Modifier.width(160.dp),
            )
            IconButton(onClick = { viewModel.duplicate(repeat.key) }) {
                Icon(Icons.Default.ContentCopy, stringResource(R.string.duplicate))
            }
        }
        Column(Modifier.padding(start = 12.dp, end = 12.dp, bottom = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            repeat.intervals.forEachIndexed { i, segment ->
                IntervalCard(
                    segment = segment,
                    title = segment.name.ifBlank { stringResource(R.string.segment_interval_n, firstNumber + i) },
                    expanded = state.expandedKey == segment.key,
                    imperial = state.imperial,
                    maxIncline = state.inclineMax,
                    dragging = false,
                    handle = null,
                    onToggle = { viewModel.toggleExpanded(segment.key) },
                    nested = true,
                ) {
                    NameField(segment, viewModel)
                    SegmentFields(segment, unit, viewModel, onEdit = { onEdit(segment.key, it, segment) })
                    ItemActions(
                        onDuplicate = { viewModel.duplicate(segment.key) },
                        onDelete = { viewModel.remove(segment.key) },
                    ) {
                        IconButton(onClick = { viewModel.moveInRepeat(segment.key, -1) }, enabled = i > 0) {
                            Icon(Icons.Default.ArrowUpward, stringResource(R.string.move_up))
                        }
                        IconButton(onClick = { viewModel.moveInRepeat(segment.key, 1) }, enabled = i < repeat.intervals.lastIndex) {
                            Icon(Icons.Default.ArrowDownward, stringResource(R.string.move_down))
                        }
                    }
                }
            }
            HorizontalDivider(color = colors.outlineVariant)
            Row(horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                TextButton(onClick = { viewModel.addToRepeat(repeat.key) }) {
                    Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                    Text(stringResource(R.string.add_to_block), modifier = Modifier.padding(start = 6.dp))
                }
                TextButton(onClick = { viewModel.remove(repeat.key) }) {
                    Icon(Icons.Default.Delete, contentDescription = null, modifier = Modifier.size(18.dp))
                    Text(stringResource(R.string.delete_block), modifier = Modifier.padding(start = 6.dp))
                }
            }
        }
    }
}

/** "2:00 · 3.5 mph · 6%" for a draft, whose speed is already in display units. */
@Composable
private fun draftSummary(segment: DraftSegment, imperial: Boolean): String = stringResource(
    R.string.segment_summary,
    Units.formatDuration(segment.durationSec),
    WorkoutFormat.number(segment.speed),
    speedUnitLabel(imperial),
    WorkoutFormat.incline(segment.incline),
)
