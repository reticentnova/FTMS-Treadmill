package com.example.ftmstreadmill.ui.workouts

import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.ftmstreadmill.ble.AndroidBleClient
import com.example.ftmstreadmill.data.ConnectionState
import com.example.ftmstreadmill.data.SettingsRepository
import com.example.ftmstreadmill.data.TreadmillController
import com.example.ftmstreadmill.data.WorkoutRepository
import com.example.ftmstreadmill.ui.nav.Route
import com.example.ftmstreadmill.workout.WorkoutEngine
import com.example.ftmstreadmill.workout.WorkoutRunState
import com.example.ftmstreadmill.workout.io.WorkoutJson
import com.example.ftmstreadmill.workout.model.Workout
import com.example.ftmstreadmill.workout.model.WorkoutPlan
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException

data class WorkoutDetailUiState(
    val loading: Boolean = true,
    val workout: Workout? = null,
    val plan: WorkoutPlan? = null,
    val savedId: Long? = null,
    val presetId: String? = null,
    val imperial: Boolean = false,
    val connection: ConnectionState = ConnectionState.Idle,
    val runState: WorkoutRunState = WorkoutRunState.Idle,
) {
    val isPreset: Boolean get() = presetId != null

    /** A workout (this or another) is already in progress. */
    val activeWorkout: WorkoutRunState.Active? get() = runState as? WorkoutRunState.Active
    val isThisRunning: Boolean
        get() = activeWorkout?.let { it.workoutId == savedId && it.workout.name == workout?.name } == true
}

@HiltViewModel(assistedFactory = WorkoutDetailViewModel.Factory::class)
class WorkoutDetailViewModel @AssistedInject constructor(
    @Assisted private val route: Route.WorkoutDetail,
    private val repository: WorkoutRepository,
    private val controller: TreadmillController,
    private val engine: WorkoutEngine,
    private val settings: SettingsRepository,
    @ApplicationContext private val context: Context,
) : ViewModel() {

    @AssistedFactory
    interface Factory {
        fun create(route: Route.WorkoutDetail): WorkoutDetailViewModel
    }

    private val source: Flow<Workout?> = when {
        route.savedId != null -> repository.observe(route.savedId).map { it?.workout }
        route.presetId != null -> flow { emit(repository.preset(route.presetId)?.workout) }
        else -> flow { emit(null) }
    }

    val uiState: StateFlow<WorkoutDetailUiState> = combine(
        source,
        settings.settings,
        controller.connectionState,
        engine.state,
    ) { workout, settings, connection, run ->
        WorkoutDetailUiState(
            loading = false,
            workout = workout,
            plan = workout?.let(WorkoutPlan::of),
            savedId = route.savedId,
            presetId = route.presetId,
            imperial = settings.useImperialUnits,
            connection = connection,
            runState = run,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), WorkoutDetailUiState())

    /** Starts this workout. Returns false if another workout is already running. */
    fun start(): Boolean {
        val state = uiState.value
        val workout = state.workout ?: return false
        if (engine.state.value is WorkoutRunState.Active) return false
        engine.start(route.savedId, workout)
        route.savedId?.let { viewModelScope.launch { repository.markUsed(it) } }
        return true
    }

    /** Reconnects to the last treadmill. Returns false when the Treadmill tab must handle it (pick a device, permissions). */
    suspend fun connect(): Boolean {
        val granted = AndroidBleClient.REQUIRED_PERMISSIONS.all {
            ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
        }
        val last = settings.settings.first().lastDevice
        if (!granted || last == null) return false
        controller.connect(last)
        return true
    }

    fun delete(onDone: () -> Unit) {
        val id = route.savedId ?: return
        viewModelScope.launch {
            repository.delete(id)
            onDone()
        }
    }

    /** Copies the workout into "My workouts" and returns the new id. */
    suspend fun saveCopy(): Long? = uiState.value.workout?.let { repository.saveCopy(it) }

    fun exportFileName(): String = WorkoutsViewModel.fileName(uiState.value.workout?.name ?: "workout")

    fun writeExport(uri: Uri?, onResult: (Boolean) -> Unit) {
        val workout = uiState.value.workout ?: return
        if (uri == null) return
        viewModelScope.launch {
            val ok = try {
                withContext(Dispatchers.IO) {
                    context.contentResolver.openOutputStream(uri, "wt")?.bufferedWriter()?.use { it.write(WorkoutJson.encode(workout)) }
                } != null
            } catch (e: IOException) {
                false
            } catch (e: SecurityException) {
                false
            }
            onResult(ok)
        }
    }
}
