package com.example.ftmstreadmill.ui.settings

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.health.connect.client.PermissionController
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.example.ftmstreadmill.R
import com.example.ftmstreadmill.data.SegmentDefaults
import com.example.ftmstreadmill.data.Settings
import com.example.ftmstreadmill.data.SettingsRepository
import com.example.ftmstreadmill.health.HealthConnectAvailability
import com.example.ftmstreadmill.health.HealthConnectExporter
import com.example.ftmstreadmill.ui.common.DurationEditDialog
import com.example.ftmstreadmill.ui.common.NumberEditDialog
import com.example.ftmstreadmill.ui.common.Units
import com.example.ftmstreadmill.ui.common.ValueStepper
import com.example.ftmstreadmill.ui.common.WorkoutFormat
import com.example.ftmstreadmill.ui.common.openHealthConnectInstall
import com.example.ftmstreadmill.ui.common.speedUnitLabel
import com.example.ftmstreadmill.ui.editor.durationStep
import com.example.ftmstreadmill.ui.editor.round1
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class WorkoutSettingsViewModel @Inject constructor(
    private val repository: SettingsRepository,
    private val exporter: HealthConnectExporter,
) : ViewModel() {
    val settings: StateFlow<Settings> = repository.settings.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), Settings())

    val healthConnectAvailability: HealthConnectAvailability get() = exporter.availability

    suspend fun hasHealthConnectPermissions() = exporter.hasPermissions()

    fun setImperial(value: Boolean) = launch { repository.setUseImperialUnits(value) }
    fun setCueSound(value: Boolean) = launch { repository.setCueSound(value) }
    fun setCueVoice(value: Boolean) = launch { repository.setCueVoice(value) }
    fun setCueVibrate(value: Boolean) = launch { repository.setCueVibrate(value) }
    fun setAutoExport(value: Boolean) = launch { repository.setHealthConnectAutoExport(value) }
    fun setWarmup(value: SegmentDefaults) = launch { repository.setWarmupDefaults(value.sanitized()) }
    fun setCooldown(value: SegmentDefaults) = launch { repository.setCooldownDefaults(value.sanitized()) }

    private fun SegmentDefaults.sanitized() = copy(
        durationSec = durationSec.coerceIn(10, 3600),
        speedKmh = speedKmh.coerceIn(0.5, 20.0),
        inclinePercent = inclinePercent.coerceIn(0.0, 15.0),
    )

    private fun launch(block: suspend () -> Unit) {
        viewModelScope.launch { block() }
    }
}

private enum class DefaultField { DURATION, SPEED, INCLINE }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WorkoutSettingsScreen(onBack: () -> Unit, viewModel: WorkoutSettingsViewModel = hiltViewModel()) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val workout = settings.workout
    val imperial = settings.useImperialUnits
    val context = LocalContext.current
    var editing by remember { mutableStateOf<Pair<Boolean, DefaultField>?>(null) }

    // Re-check on resume: the user may have granted or revoked access in Health Connect itself.
    var hcGranted by remember { mutableStateOf(false) }
    var hcCheck by remember { mutableStateOf(0) }
    LifecycleResumeEffect(Unit) {
        hcCheck++
        onPauseOrDispose { }
    }
    LaunchedEffect(hcCheck) { hcGranted = viewModel.hasHealthConnectPermissions() }
    val permissionLauncher = rememberLauncherForActivityResult(PermissionController.createRequestPermissionResultContract()) { granted ->
        hcGranted = granted.containsAll(HealthConnectExporter.PERMISSIONS)
        if (hcGranted) viewModel.setAutoExport(true)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.workout_settings)) },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back)) }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Section(stringResource(R.string.settings_units)) {
                SwitchRow(stringResource(R.string.menu_imperial), null, imperial, viewModel::setImperial)
            }
            Section(stringResource(R.string.settings_cues)) {
                SwitchRow(stringResource(R.string.cue_sound), stringResource(R.string.cue_sound_desc), workout.cueSound, viewModel::setCueSound)
                SwitchRow(stringResource(R.string.cue_voice), stringResource(R.string.cue_voice_desc), workout.cueVoice, viewModel::setCueVoice)
                SwitchRow(stringResource(R.string.cue_vibrate), stringResource(R.string.cue_vibrate_desc), workout.cueVibrate, viewModel::setCueVibrate)
            }
            Section(stringResource(R.string.settings_defaults)) {
                Text(
                    stringResource(R.string.settings_defaults_desc),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                DefaultsEditor(stringResource(R.string.segment_warmup), workout.warmup, imperial, viewModel::setWarmup) { editing = true to it }
                DefaultsEditor(stringResource(R.string.segment_cooldown), workout.cooldown, imperial, viewModel::setCooldown) { editing = false to it }
            }
            Section(stringResource(R.string.health_connect)) {
                when (viewModel.healthConnectAvailability) {
                    HealthConnectAvailability.UNAVAILABLE -> Text(stringResource(R.string.hc_not_available), style = MaterialTheme.typography.bodyMedium)
                    HealthConnectAvailability.NEEDS_INSTALL_OR_UPDATE -> Column {
                        Text(stringResource(R.string.hc_needs_install), style = MaterialTheme.typography.bodyMedium)
                        TextButton(onClick = { openHealthConnectInstall(context) }) { Text(stringResource(R.string.hc_install)) }
                    }
                    HealthConnectAvailability.AVAILABLE -> SwitchRow(
                        stringResource(R.string.hc_auto_export),
                        stringResource(if (hcGranted) R.string.hc_auto_export_desc else R.string.hc_permission_needed),
                        workout.healthConnectAutoExport && hcGranted,
                    ) { on ->
                        if (on && !hcGranted) permissionLauncher.launch(HealthConnectExporter.PERMISSIONS) else viewModel.setAutoExport(on)
                    }
                }
            }
        }
    }

    editing?.let { (isWarmup, field) ->
        val current = if (isWarmup) workout.warmup else workout.cooldown
        val save: (SegmentDefaults) -> Unit = if (isWarmup) viewModel::setWarmup else viewModel::setCooldown
        val close = { editing = null }
        when (field) {
            DefaultField.DURATION -> DurationEditDialog(stringResource(R.string.duration), current.durationSec, close) {
                save(current.copy(durationSec = it)); close()
            }
            DefaultField.SPEED -> NumberEditDialog(
                stringResource(R.string.metric_speed),
                Units.formatSpeed(current.speedKmh, imperial),
                speedUnitLabel(imperial),
                close,
            ) { save(current.copy(speedKmh = Units.speedFromDisplay(it, imperial))); close() }
            DefaultField.INCLINE -> NumberEditDialog(
                stringResource(R.string.metric_incline),
                WorkoutFormat.incline(current.inclinePercent),
                stringResource(R.string.unit_percent),
                close,
            ) { save(current.copy(inclinePercent = it)); close() }
        }
    }
}

@Composable
private fun DefaultsEditor(
    title: String,
    value: SegmentDefaults,
    imperial: Boolean,
    onChange: (SegmentDefaults) -> Unit,
    onEdit: (DefaultField) -> Unit,
) {
    val displaySpeed = round1(Units.speedToDisplay(value.speedKmh, imperial))
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 8.dp))
        ValueStepper(
            label = stringResource(R.string.duration),
            value = Units.formatDuration(value.durationSec),
            unit = "",
            onDecrease = { onChange(value.copy(durationSec = value.durationSec - durationStep(value.durationSec, up = false))) },
            onIncrease = { onChange(value.copy(durationSec = value.durationSec + durationStep(value.durationSec, up = true))) },
            onEdit = { onEdit(DefaultField.DURATION) },
            compact = true,
        )
        ValueStepper(
            label = stringResource(R.string.metric_speed),
            value = WorkoutFormat.number(displaySpeed),
            unit = speedUnitLabel(imperial),
            onDecrease = { onChange(value.copy(speedKmh = Units.speedFromDisplay(displaySpeed - 0.1, imperial))) },
            onIncrease = { onChange(value.copy(speedKmh = Units.speedFromDisplay(displaySpeed + 0.1, imperial))) },
            onEdit = { onEdit(DefaultField.SPEED) },
            compact = true,
        )
        ValueStepper(
            label = stringResource(R.string.metric_incline),
            value = WorkoutFormat.incline(value.inclinePercent),
            unit = stringResource(R.string.unit_percent),
            onDecrease = { onChange(value.copy(inclinePercent = value.inclinePercent - 0.5)) },
            onIncrease = { onChange(value.copy(inclinePercent = value.inclinePercent + 0.5)) },
            onEdit = { onEdit(DefaultField.INCLINE) },
            compact = true,
        )
    }
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
            content()
        }
    }
}

@Composable
private fun SwitchRow(title: String, description: String?, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(role = Role.Switch) { onChange(!checked) }
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f).padding(end = 16.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            description?.let {
                Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Switch(checked = checked, onCheckedChange = null)
    }
}
