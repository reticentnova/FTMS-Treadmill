package com.example.ftmstreadmill.ui.run

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.ftmstreadmill.data.ConnectionState
import com.example.ftmstreadmill.data.HistoryRepository
import com.example.ftmstreadmill.data.SettingsRepository
import com.example.ftmstreadmill.data.TreadmillController
import com.example.ftmstreadmill.data.TreadmillState
import com.example.ftmstreadmill.health.ExportResult
import com.example.ftmstreadmill.health.HealthConnectAvailability
import com.example.ftmstreadmill.health.HealthConnectExporter
import com.example.ftmstreadmill.workout.WorkoutEngine
import com.example.ftmstreadmill.workout.WorkoutRunState
import com.example.ftmstreadmill.workout.model.SpeedUnit
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

data class RunUiState(
    val run: WorkoutRunState = WorkoutRunState.Idle,
    val treadmill: TreadmillState = TreadmillState(),
    val connection: ConnectionState = ConnectionState.Idle,
    val imperial: Boolean = false,
    val healthConnectAutoExport: Boolean = false,
) {
    val isConnected: Boolean get() = connection is ConnectionState.Ready
}

@HiltViewModel
class ActiveWorkoutViewModel @Inject constructor(
    private val engine: WorkoutEngine,
    controller: TreadmillController,
    settings: SettingsRepository,
    private val history: HistoryRepository,
    private val exporter: HealthConnectExporter,
) : ViewModel() {

    val uiState: StateFlow<RunUiState> = combine(
        engine.state,
        controller.treadmillState,
        controller.connectionState,
        settings.settings,
    ) { run, treadmill, connection, settings ->
        RunUiState(run, treadmill, connection, settings.useImperialUnits, settings.workout.healthConnectAutoExport)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), RunUiState(run = engine.state.value))

    val healthConnectAvailability: HealthConnectAvailability get() = exporter.availability

    fun pause() = engine.pause()

    fun resume() = engine.resume()

    fun end() = engine.end()

    fun skip() = engine.skip()

    fun resetToPlan() = engine.resetToPlan()

    fun adjustSpeed(steps: Int) = engine.nudgeSpeed(steps, if (uiState.value.imperial) SpeedUnit.MPH else SpeedUnit.KMH)

    fun adjustIncline(steps: Int) = engine.nudgeIncline(steps)

    fun done() = engine.dismiss()

    /** Removes the just-finished session from history, e.g. after a false start. */
    fun discard() {
        val finished = engine.state.value as? WorkoutRunState.Finished ?: return
        viewModelScope.launch {
            if (finished.session.id != 0L) history.delete(finished.session.id)
            engine.dismiss()
        }
    }

    suspend fun exportToHealthConnect(): ExportResult {
        val finished = engine.state.value as? WorkoutRunState.Finished ?: return ExportResult.NotAvailable
        return exporter.export(finished.session)
    }
}
