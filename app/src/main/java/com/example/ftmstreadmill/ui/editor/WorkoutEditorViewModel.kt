package com.example.ftmstreadmill.ui.editor

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.ftmstreadmill.data.SettingsRepository
import com.example.ftmstreadmill.data.TreadmillController
import com.example.ftmstreadmill.data.WorkoutRepository
import com.example.ftmstreadmill.ui.nav.Route
import com.example.ftmstreadmill.workout.io.WorkoutFormatException
import com.example.ftmstreadmill.workout.io.WorkoutJson
import com.example.ftmstreadmill.workout.model.SpeedUnit
import com.example.ftmstreadmill.workout.model.WorkoutPlan
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

data class EditorUiState(
    val loading: Boolean = true,
    val isNew: Boolean = true,
    val draft: EditorDraft? = null,
    val expandedKey: Long? = null,
    val dirty: Boolean = false,
    val nameError: Boolean = false,
    val saving: Boolean = false,
    val speedMin: Double = 0.5,
    val speedMax: Double = 20.0,
    val inclineMin: Double = 0.0,
    val inclineMax: Double = 15.0,
    val inclineStep: Double = 0.5,
) {
    val imperial: Boolean get() = draft?.units == SpeedUnit.MPH

    /** The live plan behind the chart and totals; null while the draft can't be expanded. */
    val plan: WorkoutPlan? get() = draft?.let { d -> runCatching { WorkoutPlan.of(d.toWorkout()) }.getOrNull() }
}

sealed interface EditorMessage {
    data class Invalid(val reason: String) : EditorMessage
    data object NameMissing : EditorMessage
    data object NeedsInterval : EditorMessage
    data class Removed(val label: String?) : EditorMessage
}

@HiltViewModel(assistedFactory = WorkoutEditorViewModel.Factory::class)
class WorkoutEditorViewModel @AssistedInject constructor(
    @Assisted private val route: Route.Editor,
    private val savedState: SavedStateHandle,
    private val repository: WorkoutRepository,
    private val settings: SettingsRepository,
    controller: TreadmillController,
) : ViewModel() {

    @AssistedFactory
    interface Factory {
        fun create(route: Route.Editor): WorkoutEditorViewModel
    }

    private val _state = MutableStateFlow(EditorUiState(isNew = route.workoutId == null))
    val state: StateFlow<EditorUiState> = _state.asStateFlow()

    private val _messages = Channel<EditorMessage>(Channel.BUFFERED)
    val messages: Flow<EditorMessage> = _messages.receiveAsFlow()

    private var nextKey = 1L
    private fun key() = nextKey++

    /** Last removed step and where it was, for Undo. */
    private var removed: Pair<Int, DraftStep>? = null
    private var removedFromRepeat: Triple<Long, Int, DraftSegment>? = null

    init {
        viewModelScope.launch { load(controller) }
    }

    private suspend fun load(controller: TreadmillController) {
        val prefs = settings.settings.first()
        val units = if (prefs.useImperialUnits) SpeedUnit.MPH else SpeedUnit.KMH
        val defaults = prefs.workout.warmup to prefs.workout.cooldown
        val restored = savedState.get<String>(KEY_DRAFT)?.let {
            try {
                json.decodeFromString(EditorDraft.serializer(), it)
            } catch (e: SerializationException) {
                null
            }
        }
        val draft = restored ?: when {
            route.workoutId != null -> repository.get(route.workoutId)?.workout?.let { EditorDraft.from(it, units, defaults, ::key) }
            route.presetId != null -> repository.preset(route.presetId)?.workout?.let { EditorDraft.from(it, units, defaults, ::key) }
            else -> null
        } ?: EditorDraft.blank(units, defaults, ::key)
        nextKey = draft.maxKey + 1

        // Limit steppers to what the connected treadmill can do (or sensible defaults).
        val treadmill = controller.treadmillState.value
        _state.value = EditorUiState(
            loading = false,
            isNew = route.workoutId == null,
            draft = draft,
            dirty = restored != null || route.presetId != null,
            expandedKey = if (route.workoutId == null && route.presetId == null) (draft.steps.firstOrNull()?.key) else null,
            speedMin = round1(units.fromKmh(maxOf(treadmill.speedRange.min, MIN_SPEED_KMH))),
            speedMax = round1(units.fromKmh(treadmill.speedRange.max)),
            inclineMin = treadmill.inclineRange.min,
            inclineMax = treadmill.inclineRange.max,
            inclineStep = treadmill.inclineRange.step.takeIf { it > 0 } ?: 0.5,
        )
    }

    private fun edit(transform: (EditorDraft) -> EditorDraft) {
        _state.update { s ->
            val draft = s.draft ?: return@update s
            val next = transform(draft)
            savedState[KEY_DRAFT] = json.encodeToString(EditorDraft.serializer(), next)
            s.copy(draft = next, dirty = true, nameError = s.nameError && next.name.isBlank())
        }
    }

    fun setName(value: String) = edit { it.copy(name = value) }

    fun setDescription(value: String) = edit { it.copy(description = value) }

    fun setWarmupEnabled(value: Boolean) = edit { it.copy(warmupEnabled = value) }

    fun setCooldownEnabled(value: Boolean) = edit { it.copy(cooldownEnabled = value) }

    fun toggleExpanded(key: Long) = _state.update { it.copy(expandedKey = if (it.expandedKey == key) null else key) }

    /** Applies [change] to the segment with [key], wherever it lives, clamping to the allowed ranges. */
    fun updateSegment(key: Long, change: (DraftSegment) -> DraftSegment) {
        val s = _state.value
        fun clamp(seg: DraftSegment) = change(seg).let {
            it.copy(
                durationSec = it.durationSec.coerceIn(MIN_DURATION_SEC, WorkoutJson.MAX_SEGMENT_SEC),
                speed = round1(it.speed.coerceIn(s.speedMin, s.speedMax)),
                incline = (it.incline.coerceIn(s.inclineMin, s.inclineMax) * 10).let { v -> Math.round(v) / 10.0 },
            )
        }
        edit { d ->
            d.copy(
                warmup = if (d.warmup.key == key) clamp(d.warmup) else d.warmup,
                cooldown = if (d.cooldown.key == key) clamp(d.cooldown) else d.cooldown,
                steps = d.steps.map { step ->
                    when (step) {
                        is DraftInterval -> if (step.key == key) DraftInterval(clamp(step.segment)) else step
                        is DraftRepeat -> step.copy(intervals = step.intervals.map { if (it.key == key) clamp(it) else it })
                    }
                },
            )
        }
    }

    fun stepDuration(key: Long, up: Boolean) = updateSegment(key) {
        val step = durationStep(it.durationSec, up)
        val snapped = if (up) (it.durationSec / step + 1) * step else ((it.durationSec - 1) / step) * step
        it.copy(durationSec = snapped)
    }

    fun stepSpeed(key: Long, up: Boolean) = updateSegment(key) { it.copy(speed = it.speed + if (up) 0.1 else -0.1) }

    fun stepIncline(key: Long, up: Boolean) {
        val step = _state.value.inclineStep
        updateSegment(key) { it.copy(incline = it.incline + if (up) step else -step) }
    }

    /** Adds an interval at the end, copying the last one so a series is quick to build. */
    fun addInterval() {
        val newKey = key()
        edit { d ->
            val template = d.steps.lastOrNull()?.let { last ->
                when (last) {
                    is DraftInterval -> last.segment
                    is DraftRepeat -> last.intervals.lastOrNull()
                }
            }
            val segment = template?.copy(key = newKey, name = "")
                ?: DraftSegment(newKey, durationSec = 120, speed = d.warmup.speed, incline = 0.0)
            d.copy(steps = d.steps + DraftInterval(segment))
        }
        _state.update { it.copy(expandedKey = newKey) }
    }

    /** Adds a work/rest pair repeated 4 times, the most common interval pattern. */
    fun addRepeat() {
        val repeatKey = key()
        edit { d ->
            val base = d.warmup.speed
            d.copy(
                steps = d.steps + DraftRepeat(
                    repeatKey, 4,
                    listOf(
                        DraftSegment(key(), "", 60, round1(base * 1.6), 1.0),
                        DraftSegment(key(), "", 90, base, 0.0),
                    ),
                ),
            )
        }
        _state.update { it.copy(expandedKey = repeatKey) }
    }

    fun addToRepeat(repeatKey: Long) {
        val newKey = key()
        edit { d ->
            d.copy(
                steps = d.steps.map { step ->
                    if (step is DraftRepeat && step.key == repeatKey) {
                        val template = step.intervals.last()
                        step.copy(intervals = step.intervals + template.copy(key = newKey, name = ""))
                    } else {
                        step
                    }
                },
            )
        }
        _state.update { it.copy(expandedKey = newKey) }
    }

    fun setRepeatTimes(repeatKey: Long, times: Int) = edit { d ->
        d.copy(
            steps = d.steps.map {
                if (it is DraftRepeat && it.key == repeatKey) it.copy(times = times.coerceIn(1, WorkoutJson.MAX_REPEATS)) else it
            },
        )
    }

    fun duplicate(key: Long) {
        edit { d ->
            val index = d.steps.indexOfFirst { it.key == key }
            if (index >= 0) {
                val copy = when (val step = d.steps[index]) {
                    is DraftInterval -> DraftInterval(step.segment.copy(key = key()))
                    is DraftRepeat -> step.copy(key = key(), intervals = step.intervals.map { it.copy(key = key()) })
                }
                d.copy(steps = d.steps.toMutableList().apply { add(index + 1, copy) })
            } else {
                d.copy(
                    steps = d.steps.map { step ->
                        if (step is DraftRepeat && step.intervals.any { it.key == key }) {
                            val i = step.intervals.indexOfFirst { it.key == key }
                            step.copy(intervals = step.intervals.toMutableList().apply { add(i + 1, this[i].copy(key = key())) })
                        } else {
                            step
                        }
                    },
                )
            }
        }
    }

    fun remove(key: Long) {
        val draft = _state.value.draft ?: return
        val index = draft.steps.indexOfFirst { it.key == key }
        if (index >= 0) {
            val step = draft.steps[index]
            removed = index to step
            removedFromRepeat = null
            edit { d -> d.copy(steps = d.steps.filterNot { it.key == key }) }
            viewModelScope.launch { _messages.send(EditorMessage.Removed((step as? DraftInterval)?.segment?.name)) }
            return
        }
        val repeat = draft.steps.filterIsInstance<DraftRepeat>().firstOrNull { r -> r.intervals.any { it.key == key } } ?: return
        if (repeat.intervals.size == 1) {
            remove(repeat.key)
            return
        }
        val i = repeat.intervals.indexOfFirst { it.key == key }
        removedFromRepeat = Triple(repeat.key, i, repeat.intervals[i])
        removed = null
        edit { d ->
            d.copy(steps = d.steps.map { if (it.key == repeat.key && it is DraftRepeat) it.copy(intervals = it.intervals.filterNot { s -> s.key == key }) else it })
        }
        viewModelScope.launch { _messages.send(EditorMessage.Removed(repeat.intervals[i].name)) }
    }

    fun undoRemove() {
        removed?.let { (index, step) ->
            edit { d -> d.copy(steps = d.steps.toMutableList().apply { add(index.coerceAtMost(size), step) }) }
        }
        removedFromRepeat?.let { (repeatKey, index, segment) ->
            edit { d ->
                d.copy(
                    steps = d.steps.map {
                        if (it is DraftRepeat && it.key == repeatKey) {
                            it.copy(intervals = it.intervals.toMutableList().apply { add(index.coerceAtMost(size), segment) })
                        } else {
                            it
                        }
                    },
                )
            }
        }
        removed = null
        removedFromRepeat = null
    }

    /** Drag-and-drop between top-level steps. */
    fun move(fromKey: Long, toKey: Long) = edit { d ->
        val from = d.steps.indexOfFirst { it.key == fromKey }
        val to = d.steps.indexOfFirst { it.key == toKey }
        if (from < 0 || to < 0) d else d.copy(steps = d.steps.toMutableList().apply { add(to, removeAt(from)) })
    }

    /** Moves an interval up or down inside its repeat block. */
    fun moveInRepeat(key: Long, delta: Int) = edit { d ->
        d.copy(
            steps = d.steps.map { step ->
                if (step is DraftRepeat && step.intervals.any { it.key == key }) {
                    val i = step.intervals.indexOfFirst { it.key == key }
                    val j = (i + delta).coerceIn(0, step.intervals.lastIndex)
                    step.copy(intervals = step.intervals.toMutableList().apply { add(j, removeAt(i)) })
                } else {
                    step
                }
            },
        )
    }

    /** Validates and saves. Calls [onSaved] with the workout id. */
    fun save(onSaved: (Long) -> Unit) {
        val s = _state.value
        val draft = s.draft ?: return
        if (draft.name.isBlank()) {
            _state.update { it.copy(nameError = true) }
            viewModelScope.launch { _messages.send(EditorMessage.NameMissing) }
            return
        }
        if (draft.steps.isEmpty()) {
            viewModelScope.launch { _messages.send(EditorMessage.NeedsInterval) }
            return
        }
        _state.update { it.copy(saving = true) }
        viewModelScope.launch {
            try {
                val id = repository.save(route.workoutId, draft.toWorkout())
                savedState.remove<String>(KEY_DRAFT)
                _state.update { it.copy(saving = false, dirty = false) }
                onSaved(id)
            } catch (e: WorkoutFormatException) {
                _state.update { it.copy(saving = false) }
                _messages.send(EditorMessage.Invalid(e.message.orEmpty()))
            }
        }
    }

    private companion object {
        const val KEY_DRAFT = "draft"
        const val MIN_DURATION_SEC = 5
        const val MIN_SPEED_KMH = 0.5
        val json = Json { ignoreUnknownKeys = true }
    }
}
