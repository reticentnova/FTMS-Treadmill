package com.example.ftmstreadmill.ui.nav

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.ftmstreadmill.data.SettingsRepository
import com.example.ftmstreadmill.data.ThemeSettings
import com.example.ftmstreadmill.data.WorkoutRepository
import com.example.ftmstreadmill.workout.WorkoutEngine
import com.example.ftmstreadmill.workout.io.WorkoutFormatException
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException
import javax.inject.Inject

sealed interface ImportResult {
    data class Imported(val count: Int) : ImportResult
    data class Failed(val reason: String?) : ImportResult
}

/** Activity-wide state: the running workout for the mini bar, and file imports from other apps. */
@HiltViewModel
class AppViewModel @Inject constructor(
    val engine: WorkoutEngine,
    private val workouts: WorkoutRepository,
    settings: SettingsRepository,
    @ApplicationContext private val context: Context,
) : ViewModel() {
    val runState = engine.state

    val imperial = settings.settings.map { it.useImperialUnits }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    /** Null until the stored theme has loaded, so the first frame isn't drawn in the wrong colours. */
    val theme: StateFlow<ThemeSettings?> = settings.settings.map { it.theme }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    private val _imports = Channel<ImportResult>(Channel.BUFFERED)
    val imports: Flow<ImportResult> = _imports.receiveAsFlow()

    fun import(uri: Uri) {
        viewModelScope.launch { _imports.send(importFrom(context, workouts, uri)) }
    }
}

/** Reads a workout file through the content resolver and saves what it contains. */
suspend fun importFrom(context: Context, workouts: WorkoutRepository, uri: Uri): ImportResult = try {
    val text = withContext(Dispatchers.IO) {
        context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
    } ?: throw IOException("Could not open file")
    ImportResult.Imported(workouts.import(text))
} catch (e: WorkoutFormatException) {
    ImportResult.Failed(e.message)
} catch (e: IOException) {
    ImportResult.Failed(null)
} catch (e: SecurityException) {
    ImportResult.Failed(null)
}
