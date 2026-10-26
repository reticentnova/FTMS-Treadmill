package com.example.ftmstreadmill.ui.control

import android.bluetooth.BluetoothAdapter
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings as AndroidSettings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.ftmstreadmill.R
import com.example.ftmstreadmill.ble.AndroidBleClient
import com.example.ftmstreadmill.data.ConnectionState
import com.example.ftmstreadmill.ftms.model.MachineStatus
import com.example.ftmstreadmill.ftms.model.TrainingStatus
import com.example.ftmstreadmill.ui.common.Units
import kotlinx.coroutines.flow.collectLatest

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ControlScreen(viewModel: ControlViewModel = hiltViewModel()) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val snackbarHostState = remember { SnackbarHostState() }
    var showPicker by rememberSaveable { mutableStateOf(false) }

    // Permissions are re-checked on every resume because the user can revoke them in Settings.
    var hasPermissions by remember { mutableStateOf(false) }
    LifecycleResumeEffect(Unit) {
        hasPermissions = AndroidBleClient.REQUIRED_PERMISSIONS.all {
            ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
        }
        onPauseOrDispose { }
    }
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        hasPermissions = AndroidBleClient.REQUIRED_PERMISSIONS.all { result[it] == true }
    }
    val enableBluetoothLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { }

    LaunchedEffect(hasPermissions) {
        if (hasPermissions) viewModel.onPermissionsGranted()
    }

    val messages = messageStrings()
    LaunchedEffect(viewModel) {
        viewModel.messages.collectLatest { snackbarHostState.showSnackbar(messages(it)) }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.app_name)) },
                actions = {
                    OverflowMenu(
                        useImperial = state.settings.useImperialUnits,
                        autoReconnect = state.settings.autoReconnect,
                        hasSavedDevice = state.settings.lastDevice != null,
                        onImperialChange = viewModel::setUseImperialUnits,
                        onAutoReconnectChange = viewModel::setAutoReconnect,
                        onForgetDevice = viewModel::forgetDevice,
                    )
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            if (!hasPermissions) {
                PermissionCard(
                    onGrant = { permissionLauncher.launch(AndroidBleClient.REQUIRED_PERMISSIONS) },
                    onOpenSettings = {
                        context.startActivity(
                            Intent(AndroidSettings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null)),
                        )
                    },
                )
                return@Column
            }

            if (state.showSafetyWarning) SafetyWarningCard(onDismiss = viewModel::dismissSafetyWarning)

            ConnectionCard(
                state = state,
                onConnect = { if (state.settings.lastDevice != null) viewModel.reconnectLast() else showPicker = true },
                onChooseDevice = { showPicker = true },
                onDisconnect = viewModel::disconnect,
                onEnableBluetooth = { enableBluetoothLauncher.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)) },
            )

            MetricsCard(state)

            ControlsCard(
                state = state,
                onSpeedStep = viewModel::adjustSpeed,
                onSpeedPreset = { viewModel.setSpeed(it) },
                onInclineStep = viewModel::adjustIncline,
                onStart = viewModel::start,
                onPause = viewModel::pause,
                onStop = viewModel::stop,
            )
        }
    }

    if (showPicker && hasPermissions) {
        DevicePickerSheet(
            scan = state.scan,
            onStartScan = viewModel::startScan,
            onStopScan = viewModel::stopScan,
            onSelect = {
                showPicker = false
                viewModel.connect(it)
            },
            onDismiss = {
                viewModel.stopScan()
                showPicker = false
            },
        )
    }
}

@Composable
private fun OverflowMenu(
    useImperial: Boolean,
    autoReconnect: Boolean,
    hasSavedDevice: Boolean,
    onImperialChange: (Boolean) -> Unit,
    onAutoReconnectChange: (Boolean) -> Unit,
    onForgetDevice: () -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val menuLabel = stringResource(R.string.menu)
    Box {
        IconButton(onClick = { expanded = true }, modifier = Modifier.semantics { contentDescription = menuLabel }) {
            Text("⋮", style = MaterialTheme.typography.titleLarge)
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.menu_imperial)) },
                trailingIcon = { Checkbox(checked = useImperial, onCheckedChange = null) },
                onClick = { onImperialChange(!useImperial) },
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.menu_auto_reconnect)) },
                trailingIcon = { Checkbox(checked = autoReconnect, onCheckedChange = null) },
                onClick = { onAutoReconnectChange(!autoReconnect) },
            )
            if (hasSavedDevice) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.menu_forget_device)) },
                    onClick = {
                        expanded = false
                        onForgetDevice()
                    },
                )
            }
        }
    }
}

@Composable
private fun PermissionCard(onGrant: () -> Unit, onOpenSettings: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(stringResource(R.string.permission_title), style = MaterialTheme.typography.titleMedium)
            Text(stringResource(R.string.permission_body))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onGrant) { Text(stringResource(R.string.permission_grant)) }
                TextButton(onClick = onOpenSettings) { Text(stringResource(R.string.permission_settings)) }
            }
        }
    }
}

@Composable
private fun SafetyWarningCard(onDismiss: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer,
            contentColor = MaterialTheme.colorScheme.onErrorContainer,
        ),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.safety_title), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Text(stringResource(R.string.safety_body))
            TextButton(onClick = onDismiss, modifier = Modifier.align(Alignment.End)) {
                Text(stringResource(R.string.dismiss))
            }
        }
    }
}

@Composable
private fun ConnectionCard(
    state: ControlUiState,
    onConnect: () -> Unit,
    onChooseDevice: () -> Unit,
    onDisconnect: () -> Unit,
    onEnableBluetooth: () -> Unit,
) {
    val connection = state.connection
    val deviceName = connectionDevice(connection)?.name ?: state.settings.lastDevice?.name
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                val busy = connection is ConnectionState.Connecting ||
                    connection is ConnectionState.Initializing ||
                    connection is ConnectionState.Reconnecting
                if (busy) CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                Column(Modifier.weight(1f)) {
                    Text(connectionLabel(connection), style = MaterialTheme.typography.titleMedium)
                    if (deviceName != null || connectionDevice(connection) != null) {
                        Text(
                            deviceName ?: stringResource(R.string.unknown_device),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                when (connection) {
                    ConnectionState.Idle, is ConnectionState.Failed -> {
                        Button(onClick = onConnect) {
                            Text(stringResource(if (connection is ConnectionState.Failed) R.string.retry else R.string.connect))
                        }
                        OutlinedButton(onClick = onChooseDevice) { Text(stringResource(R.string.choose_device)) }
                    }
                    is ConnectionState.BluetoothOff -> {
                        Button(onClick = onEnableBluetooth) { Text(stringResource(R.string.turn_on_bluetooth)) }
                        OutlinedButton(onClick = onDisconnect) { Text(stringResource(R.string.cancel)) }
                    }
                    is ConnectionState.Ready -> OutlinedButton(onClick = onDisconnect) { Text(stringResource(R.string.disconnect)) }
                    else -> OutlinedButton(onClick = onDisconnect) { Text(stringResource(R.string.cancel)) }
                }
            }
        }
    }
}

@Composable
private fun MetricsCard(state: ControlUiState) {
    val imperial = state.settings.useImperialUnits
    val data = state.treadmill.data
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Row(Modifier.fillMaxWidth()) {
                BigMetric(
                    label = stringResource(R.string.metric_speed),
                    value = Units.formatSpeed(data.speedKmh, imperial),
                    unit = stringResource(if (imperial) R.string.unit_mph else R.string.unit_kmh),
                    modifier = Modifier.weight(1f),
                )
                BigMetric(
                    label = stringResource(R.string.metric_incline),
                    value = Units.formatIncline(data.inclinePercent),
                    unit = stringResource(R.string.unit_percent),
                    modifier = Modifier.weight(1f),
                )
            }
            Row(Modifier.fillMaxWidth()) {
                SmallMetric(
                    stringResource(R.string.metric_distance),
                    Units.formatDistance(data.totalDistanceMeters, imperial),
                    stringResource(if (imperial) R.string.unit_mi else R.string.unit_km),
                    Modifier.weight(1f),
                )
                SmallMetric(stringResource(R.string.metric_time), Units.formatDuration(data.elapsedTimeSeconds), "", Modifier.weight(1f))
                SmallMetric(
                    stringResource(R.string.metric_pace),
                    Units.formatPace(data.speedKmh, imperial),
                    stringResource(if (imperial) R.string.unit_min_per_mi else R.string.unit_min_per_km),
                    Modifier.weight(1f),
                )
            }
            Row(Modifier.fillMaxWidth()) {
                SmallMetric(
                    stringResource(R.string.metric_calories),
                    data.totalEnergyKcal?.toString() ?: "–",
                    stringResource(R.string.unit_kcal),
                    Modifier.weight(1f),
                )
                SmallMetric(
                    stringResource(R.string.metric_heart_rate),
                    data.heartRateBpm?.toString() ?: "–",
                    stringResource(R.string.unit_bpm),
                    Modifier.weight(1f),
                )
                SmallMetric(stringResource(R.string.metric_status), statusLabel(state), "", Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun BigMetric(label: String, value: String, unit: String, modifier: Modifier = Modifier) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(label, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.displayMedium, fontWeight = FontWeight.SemiBold)
        Text(unit, style = MaterialTheme.typography.labelMedium)
    }
}

@Composable
private fun SmallMetric(label: String, value: String, unit: String, modifier: Modifier = Modifier) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center)
        if (unit.isNotEmpty()) Text(unit, style = MaterialTheme.typography.labelSmall)
    }
}

@Composable
private fun ControlsCard(
    state: ControlUiState,
    onSpeedStep: (Int) -> Unit,
    onSpeedPreset: (Double) -> Unit,
    onInclineStep: (Int) -> Unit,
    onStart: () -> Unit,
    onPause: () -> Unit,
    onStop: () -> Unit,
) {
    val enabled = state.isReady
    val imperial = state.settings.useImperialUnits
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            StepperRow(
                label = stringResource(R.string.metric_speed),
                target = Units.formatSpeed(state.displayedSpeedTargetKmh, imperial),
                enabled = enabled,
                onDecrease = { onSpeedStep(-SPEED_STEPS_PER_TAP) },
                onIncrease = { onSpeedStep(SPEED_STEPS_PER_TAP) },
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
            ) {
                val presets = if (imperial) listOf(2.0, 3.0, 4.0, 6.0) else listOf(3.0, 5.0, 8.0, 10.0)
                presets.forEach { preset ->
                    SuggestionChip(
                        onClick = { onSpeedPreset(Units.speedFromDisplay(preset, imperial)) },
                        label = { Text("%.0f".format(preset)) },
                        enabled = enabled,
                    )
                }
            }
            StepperRow(
                label = stringResource(R.string.metric_incline),
                target = Units.formatIncline(state.displayedInclineTargetPercent),
                enabled = enabled,
                onDecrease = { onInclineStep(-1) },
                onIncrease = { onInclineStep(1) },
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilledTonalButton(onClick = onStart, enabled = enabled, modifier = Modifier.weight(1f)) {
                    Text(stringResource(R.string.start))
                }
                FilledTonalButton(onClick = onPause, enabled = enabled, modifier = Modifier.weight(1f)) {
                    Text(stringResource(R.string.pause))
                }
            }
            Button(
                onClick = onStop,
                enabled = enabled,
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.error,
                    contentColor = MaterialTheme.colorScheme.onError,
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(72.dp),
            ) {
                Text(stringResource(R.string.stop), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
private fun StepperRow(label: String, target: String, enabled: Boolean, onDecrease: () -> Unit, onIncrease: () -> Unit) {
    val decrease = stringResource(R.string.decrease)
    val increase = stringResource(R.string.increase)
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        FilledTonalIconButton(
            onClick = onDecrease,
            enabled = enabled,
            modifier = Modifier.size(56.dp).semantics { contentDescription = "$decrease $label" },
        ) { Text("−", style = MaterialTheme.typography.headlineMedium) }
        Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(label, style = MaterialTheme.typography.labelLarge)
            Text(stringResource(R.string.target_value, target), style = MaterialTheme.typography.titleMedium)
        }
        FilledTonalIconButton(
            onClick = onIncrease,
            enabled = enabled,
            modifier = Modifier.size(56.dp).semantics { contentDescription = "$increase $label" },
        ) { Text("+", style = MaterialTheme.typography.headlineMedium) }
    }
    Spacer(Modifier.height(0.dp))
}

private fun connectionDevice(state: ConnectionState) = when (state) {
    is ConnectionState.BluetoothOff -> state.device
    is ConnectionState.Connecting -> state.device
    is ConnectionState.Initializing -> state.device
    is ConnectionState.Ready -> state.device
    is ConnectionState.Reconnecting -> state.device
    is ConnectionState.Failed -> state.device
    ConnectionState.Idle -> null
}

@Composable
private fun connectionLabel(state: ConnectionState): String = when (state) {
    ConnectionState.Idle -> stringResource(R.string.state_idle)
    is ConnectionState.BluetoothOff -> stringResource(R.string.state_bluetooth_off)
    is ConnectionState.Connecting ->
        if (state.attempt > 1) stringResource(R.string.state_connecting_attempt, state.attempt) else stringResource(R.string.state_connecting)
    is ConnectionState.Initializing -> stringResource(R.string.state_initializing)
    is ConnectionState.Ready ->
        stringResource(if (state.hasControl) R.string.state_connected else R.string.state_connected_no_control)
    is ConnectionState.Reconnecting ->
        stringResource(R.string.state_reconnecting_in, (state.delayMs / 1000).toInt().coerceAtLeast(1), state.attempt)
    is ConnectionState.Failed -> stringResource(R.string.state_failed, bleErrorText(state.error))
}

@Composable
private fun statusLabel(state: ControlUiState): String {
    when (state.treadmill.machineStatus) {
        MachineStatus.PausedByUser -> return stringResource(R.string.machine_paused)
        MachineStatus.StoppedByUser, MachineStatus.StoppedBySafetyKey -> return stringResource(R.string.machine_stopped)
        else -> Unit
    }
    return when (state.treadmill.trainingStatus) {
        null -> "–"
        TrainingStatus.IDLE -> stringResource(R.string.training_idle)
        TrainingStatus.WARMING_UP -> stringResource(R.string.training_warm_up)
        TrainingStatus.COOL_DOWN -> stringResource(R.string.training_cool_down)
        TrainingStatus.MANUAL_MODE -> stringResource(R.string.training_manual)
        TrainingStatus.PRE_WORKOUT -> stringResource(R.string.training_pre_workout)
        TrainingStatus.POST_WORKOUT -> stringResource(R.string.training_post_workout)
        TrainingStatus.LOW_INTENSITY_INTERVAL,
        TrainingStatus.HIGH_INTENSITY_INTERVAL,
        TrainingStatus.RECOVERY_INTERVAL -> stringResource(R.string.training_interval)
        else -> stringResource(R.string.training_other)
    }
}

/** 0.1 km/h or 0.1 mph per tap, matching typical treadmill consoles. Rapid taps are coalesced. */
private const val SPEED_STEPS_PER_TAP = 1
