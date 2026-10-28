package com.example.ftmstreadmill.ui.workouts

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.ftmstreadmill.data.PresetWorkout
import com.example.ftmstreadmill.data.SavedWorkout
import com.example.ftmstreadmill.data.SettingsRepository
import com.example.ftmstreadmill.data.WorkoutRepository
import com.example.ftmstreadmill.ui.nav.ImportResult
import com.example.ftmstreadmill.ui.nav.importFrom
import com.example.ftmstreadmill.workout.io.WorkoutJson
import com.example.ftmstreadmill.workout.model.Workout
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException
import javax.inject.Inject

data class WorkoutsUiState(
    val loading: Boolean = true,
    val saved: List<SavedWorkout> = emptyList(),
    val presets: List<PresetWorkout> = emptyList(),
    val imperial: Boolean = false,
    val showPresets: Boolean = false,
)

sealed interface WorkoutsMessage {
    data class Deleted(val name: String) : WorkoutsMessage
    data class Imported(val result: ImportResult) : WorkoutsMessage
    data object Exported : WorkoutsMessage
    data object ExportFailed : WorkoutsMessage
    data class Copied(val name: String) : WorkoutsMessage
}

/** A pending export: the JSON to write once the user has picked a file. */
data class PendingExport(val fileName: String, val json: String)

@HiltViewModel
class WorkoutsViewModel @Inject constructor(
    private val repository: WorkoutRepository,
    settings: SettingsRepository,
    @ApplicationContext private val context: Context,
) : ViewModel() {

    private val showPresets = MutableStateFlow<Boolean?>(null)

    val uiState: StateFlow<WorkoutsUiState> = combine(
        repository.workouts,
        flow { emit(repository.presets()) },
        settings.settings,
        showPresets,
    ) { saved, presets, settings, tab ->
        WorkoutsUiState(
            loading = false,
            saved = saved,
            presets = presets,
            imperial = settings.useImperialUnits,
            // Start on presets until the user has workouts of their own.
            showPresets = tab ?: saved.isEmpty(),
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), WorkoutsUiState())

    private val _messages = Channel<WorkoutsMessage>(Channel.BUFFERED)
    val messages: Flow<WorkoutsMessage> = _messages.receiveAsFlow()

    private var lastDeleted: Workout? = null
    private var pendingExport: PendingExport? = null

    fun showPresets(value: Boolean) {
        showPresets.value = value
    }

    fun delete(item: SavedWorkout) {
        viewModelScope.launch {
            repository.delete(item.id)
            lastDeleted = item.workout
            _messages.send(WorkoutsMessage.Deleted(item.workout.name))
        }
    }

    fun undoDelete() {
        val workout = lastDeleted ?: return
        lastDeleted = null
        viewModelScope.launch { repository.save(null, workout) }
    }

    fun duplicate(workout: Workout) {
        viewModelScope.launch {
            repository.saveCopy(workout)
            showPresets.value = false
            _messages.send(WorkoutsMessage.Copied(workout.name))
        }
    }

    fun import(uri: Uri) {
        viewModelScope.launch {
            val result = importFrom(context, repository, uri)
            if (result is ImportResult.Imported) showPresets.value = false
            _messages.send(WorkoutsMessage.Imported(result))
        }
    }

    /** Prepares an export and returns the suggested file name for the system file picker. */
    suspend fun prepareExport(ids: Collection<Long>?): PendingExport? {
        val saved = uiState.value.saved
        val json = if (ids == null) repository.exportAllJson() else repository.exportJson(ids)
        val name = when {
            ids != null && ids.size == 1 -> saved.firstOrNull { it.id == ids.first() }?.workout?.name ?: "workout"
            else -> "workouts"
        }
        return PendingExport(fileName(name), json).also { pendingExport = it }
    }

    fun preparePresetExport(workout: Workout): PendingExport =
        PendingExport(fileName(workout.name), WorkoutJson.encode(workout))
            .also { pendingExport = it }

    fun writeExport(uri: Uri?) {
        val export = pendingExport ?: return
        pendingExport = null
        if (uri == null) return
        viewModelScope.launch {
            val ok = try {
                withContext(Dispatchers.IO) {
                    context.contentResolver.openOutputStream(uri, "wt")?.bufferedWriter()?.use { it.write(export.json) }
                } != null
            } catch (e: IOException) {
                false
            } catch (e: SecurityException) {
                false
            }
            _messages.send(if (ok) WorkoutsMessage.Exported else WorkoutsMessage.ExportFailed)
        }
    }

    companion object {
        fun fileName(name: String): String =
            name.replace(Regex("[^A-Za-z0-9 _()-]"), "").trim().replace(' ', '_').ifEmpty { "workout" } + ".json"
    }
}
