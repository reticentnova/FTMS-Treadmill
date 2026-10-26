package com.example.ftmstreadmill.ui.history

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.ftmstreadmill.data.HistoryRepository
import com.example.ftmstreadmill.data.SettingsRepository
import com.example.ftmstreadmill.data.WorkoutRepository
import com.example.ftmstreadmill.health.ExportResult
import com.example.ftmstreadmill.health.HealthConnectAvailability
import com.example.ftmstreadmill.health.HealthConnectExporter
import com.example.ftmstreadmill.workout.WorkoutEngine
import com.example.ftmstreadmill.workout.WorkoutRunState
import com.example.ftmstreadmill.workout.model.Session
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.time.temporal.TemporalAdjusters
import javax.inject.Inject

data class WeekSummary(val sessions: Int, val activeSec: Int, val distanceMeters: Int)

data class HistoryUiState(
    val loading: Boolean = true,
    val months: List<Pair<YearMonth, List<Session>>> = emptyList(),
    val thisWeek: WeekSummary = WeekSummary(0, 0, 0),
    val imperial: Boolean = false,
) {
    val isEmpty: Boolean get() = !loading && months.isEmpty()
}

@HiltViewModel
class HistoryViewModel @Inject constructor(
    history: HistoryRepository,
    settings: SettingsRepository,
) : ViewModel() {
    val uiState: StateFlow<HistoryUiState> = combine(history.sessions, settings.settings) { sessions, settings ->
        val zone = ZoneId.systemDefault()
        val weekStart = LocalDate.now(zone).with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
            .atStartOfDay(zone).toInstant().toEpochMilli()
        val week = sessions.filter { it.startedAt >= weekStart }
        HistoryUiState(
            loading = false,
            months = sessions.groupBy { YearMonth.from(Instant.ofEpochMilli(it.startedAt).atZone(zone)) }.toList(),
            thisWeek = WeekSummary(week.size, week.sumOf { it.activeSec }, week.sumOf { it.distanceMeters ?: 0 }),
            imperial = settings.useImperialUnits,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), HistoryUiState())
}

data class SessionDetailUiState(
    val loading: Boolean = true,
    val session: Session? = null,
    val imperial: Boolean = false,
    val canRunAgain: Boolean = false,
)

@HiltViewModel(assistedFactory = SessionDetailViewModel.Factory::class)
class SessionDetailViewModel @AssistedInject constructor(
    @Assisted private val sessionId: Long,
    private val history: HistoryRepository,
    private val workouts: WorkoutRepository,
    private val engine: WorkoutEngine,
    private val exporter: HealthConnectExporter,
    settings: SettingsRepository,
) : ViewModel() {

    @AssistedFactory
    interface Factory {
        fun create(sessionId: Long): SessionDetailViewModel
    }

    val uiState: StateFlow<SessionDetailUiState> = combine(history.observe(sessionId), settings.settings, engine.state) { session, settings, run ->
        SessionDetailUiState(
            loading = false,
            session = session,
            imperial = settings.useImperialUnits,
            canRunAgain = session?.workout != null && run !is WorkoutRunState.Active,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SessionDetailUiState())

    val healthConnectAvailability: HealthConnectAvailability get() = exporter.availability

    suspend fun export(): ExportResult {
        val session = uiState.value.session ?: return ExportResult.NotAvailable
        return exporter.export(session)
    }

    /** Starts the same workout again (the version that was run, which may differ from a later edit). */
    fun runAgain(): Boolean {
        val session = uiState.value.session ?: return false
        val workout = session.workout ?: return false
        if (engine.state.value is WorkoutRunState.Active) return false
        // Link to the saved workout only if it still exists.
        viewModelScope.launch {
            val id = session.workoutId?.takeIf { workouts.get(it) != null }
            engine.start(id, workout)
            id?.let { workouts.markUsed(it) }
        }
        return true
    }

    fun delete(onDone: () -> Unit) {
        viewModelScope.launch {
            history.delete(sessionId)
            onDone()
        }
    }
}
