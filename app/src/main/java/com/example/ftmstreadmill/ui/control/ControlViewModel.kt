package com.example.ftmstreadmill.ui.control

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.ftmstreadmill.ble.BleError
import com.example.ftmstreadmill.ble.BleException
import com.example.ftmstreadmill.ble.ScannedDevice
import com.example.ftmstreadmill.data.CommandOutcome
import com.example.ftmstreadmill.data.ConnectionState
import com.example.ftmstreadmill.data.DeviceInfo
import com.example.ftmstreadmill.data.Settings
import com.example.ftmstreadmill.data.SettingsRepository
import com.example.ftmstreadmill.data.TreadmillController
import com.example.ftmstreadmill.data.TreadmillEvent
import com.example.ftmstreadmill.data.TreadmillState
import com.example.ftmstreadmill.ftms.model.ControlResult
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject
import kotlin.math.roundToInt

data class ControlUiState(
    val connection: ConnectionState = ConnectionState.Idle,
    val treadmill: TreadmillState = TreadmillState(),
    val settings: Settings = Settings(),
    /** Speed/incline the user has dialled in but which hasn't been sent yet (debounced). */
    val pendingSpeedKmh: Double? = null,
    val pendingInclinePercent: Double? = null,
    /** The link dropped while the belt was moving: the treadmill may still be running. */
    val showSafetyWarning: Boolean = false,
    val scan: ScanUiState = ScanUiState(),
) {
    val isReady: Boolean get() = connection is ConnectionState.Ready
    val displayedSpeedTargetKmh: Double?
        get() = pendingSpeedKmh ?: treadmill.targetSpeedKmh ?: treadmill.data.speedKmh
    val displayedInclineTargetPercent: Double?
        get() = pendingInclinePercent ?: treadmill.targetInclinePercent ?: treadmill.data.inclinePercent
}

data class ScanUiState(
    val isScanning: Boolean = false,
    val devices: List<ScannedDevice> = emptyList(),
)

sealed interface UiMessage {
    data class CommandRejected(val result: ControlResult) : UiMessage
    data class CommandFailed(val error: BleError) : UiMessage
    data object NotConnected : UiMessage
    data object ConnectionLost : UiMessage
    data object Reconnected : UiMessage
    data object ControlNotGranted : UiMessage
    data object SafetyKey : UiMessage
    data class ScanFailed(val error: BleError) : UiMessage
}

private data class LocalState(
    val pendingSpeedKmh: Double? = null,
    val pendingInclinePercent: Double? = null,
    val showSafetyWarning: Boolean = false,
    val scan: ScanUiState = ScanUiState(),
)

@HiltViewModel
class ControlViewModel @Inject constructor(
    private val controller: TreadmillController,
    private val settingsRepository: SettingsRepository,
) : ViewModel() {

    private val local = MutableStateFlow(LocalState())
    private val _messages = Channel<UiMessage>(Channel.BUFFERED)
    val messages: Flow<UiMessage> = _messages.receiveAsFlow()

    val uiState: StateFlow<ControlUiState> = combine(
        controller.connectionState,
        controller.treadmillState,
        settingsRepository.settings,
        local,
    ) { connection, treadmill, settings, local ->
        ControlUiState(
            connection = connection,
            treadmill = treadmill,
            settings = settings,
            pendingSpeedKmh = local.pendingSpeedKmh,
            pendingInclinePercent = local.pendingInclinePercent,
            showSafetyWarning = local.showSafetyWarning && connection !is ConnectionState.Ready,
            scan = local.scan,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ControlUiState())

    private var speedJob: Job? = null
    private var inclineJob: Job? = null
    private var scanJob: Job? = null
    private var autoConnectAttempted = false

    init {
        viewModelScope.launch {
            controller.events.collect { event ->
                when (event) {
                    is TreadmillEvent.ConnectionLost -> {
                        if (event.beltWasMoving) local.update { it.copy(showSafetyWarning = true) }
                        _messages.send(UiMessage.ConnectionLost)
                    }
                    TreadmillEvent.Reconnected -> {
                        local.update { it.copy(showSafetyWarning = false) }
                        _messages.send(UiMessage.Reconnected)
                    }
                    TreadmillEvent.ControlNotGranted -> _messages.send(UiMessage.ControlNotGranted)
                    TreadmillEvent.StoppedBySafetyKey -> _messages.send(UiMessage.SafetyKey)
                }
            }
        }
    }

    /** Called once permissions are granted; reconnects to the last treadmill if nothing is connected. */
    fun onPermissionsGranted() {
        if (autoConnectAttempted) return
        autoConnectAttempted = true
        viewModelScope.launch {
            val last = settingsRepository.settings.first().lastDevice ?: return@launch
            if (controller.connectionState.value is ConnectionState.Idle) controller.connect(last)
        }
    }

    fun connect(device: DeviceInfo) {
        stopScan()
        local.update { it.copy(showSafetyWarning = false) }
        controller.connect(device)
    }

    fun reconnectLast() {
        val last = uiState.value.settings.lastDevice ?: return
        connect(last)
    }

    fun disconnect() {
        speedJob?.cancel()
        inclineJob?.cancel()
        local.update { it.copy(pendingSpeedKmh = null, pendingInclinePercent = null, showSafetyWarning = false) }
        controller.disconnect()
    }

    fun dismissSafetyWarning() = local.update { it.copy(showSafetyWarning = false) }

    // ---- Scanning --------------------------------------------------------------------------

    fun startScan() {
        scanJob?.cancel()
        local.update { it.copy(scan = ScanUiState(isScanning = true)) }
        scanJob = viewModelScope.launch {
            try {
                withTimeoutOrNull(SCAN_TIMEOUT_MS) {
                    controller.scan().collect { device ->
                        local.update { state ->
                            val others = state.scan.devices.filterNot { it.address == device.address }
                            state.copy(scan = state.scan.copy(devices = (others + device).sortedByDescending { it.rssi }))
                        }
                    }
                }
            } catch (e: BleException) {
                _messages.send(UiMessage.ScanFailed(e.error))
            } finally {
                local.update { it.copy(scan = it.scan.copy(isScanning = false)) }
            }
        }
    }

    fun stopScan() {
        scanJob?.cancel()
        scanJob = null
    }

    // ---- Commands --------------------------------------------------------------------------

    fun start() = runCommand { controller.start() }

    fun pause() = runCommand { controller.pause() }

    fun stop() {
        // Stop always wins over any queued adjustment.
        speedJob?.cancel()
        inclineJob?.cancel()
        local.update { it.copy(pendingSpeedKmh = null, pendingInclinePercent = null) }
        runCommand { controller.stop() }
    }

    /** Nudges speed by [steps] display-unit tenths (0.1 km/h or 0.1 mph). Rapid taps are coalesced. */
    fun adjustSpeed(steps: Int) {
        // Read sources directly rather than uiState, which lags behind rapid taps.
        val treadmill = controller.treadmillState.value
        val base = local.value.pendingSpeedKmh ?: treadmill.targetSpeedKmh ?: treadmill.data.speedKmh ?: 0.0
        val imperial = uiState.value.settings.useImperialUnits
        val current = Units.speedToDisplay(base, imperial)
        val next = ((current * 10).roundToInt() + steps) / 10.0
        setSpeed(Units.speedFromDisplay(next, imperial), debounce = true)
    }

    fun setSpeed(kmh: Double, debounce: Boolean = false) {
        val target = controller.treadmillState.value.speedRange.clampAndSnap(kmh)
        local.update { it.copy(pendingSpeedKmh = target) }
        speedJob?.cancel()
        speedJob = viewModelScope.launch {
            if (debounce) delay(ADJUST_DEBOUNCE_MS)
            report(controller.setSpeed(target))
            local.update { it.copy(pendingSpeedKmh = null) }
        }
    }

    fun adjustIncline(steps: Int) {
        val treadmill = controller.treadmillState.value
        val range = treadmill.inclineRange
        val step = if (range.step > 0) range.step else DEFAULT_INCLINE_STEP
        val base = local.value.pendingInclinePercent ?: treadmill.targetInclinePercent ?: treadmill.data.inclinePercent ?: 0.0
        val target = range.clampAndSnap(base + steps * step)
        local.update { it.copy(pendingInclinePercent = target) }
        inclineJob?.cancel()
        inclineJob = viewModelScope.launch {
            delay(ADJUST_DEBOUNCE_MS)
            report(controller.setIncline(target))
            local.update { it.copy(pendingInclinePercent = null) }
        }
    }

    // ---- Settings --------------------------------------------------------------------------

    fun setUseImperialUnits(value: Boolean) {
        viewModelScope.launch { settingsRepository.setUseImperialUnits(value) }
    }

    fun setAutoReconnect(value: Boolean) {
        viewModelScope.launch { settingsRepository.setAutoReconnect(value) }
    }

    fun forgetDevice() {
        disconnect()
        viewModelScope.launch { settingsRepository.clearLastDevice() }
    }

    private fun runCommand(block: suspend () -> CommandOutcome) {
        viewModelScope.launch { report(block()) }
    }

    private suspend fun report(outcome: CommandOutcome) {
        when (outcome) {
            CommandOutcome.Success -> Unit
            CommandOutcome.NotConnected -> _messages.send(UiMessage.NotConnected)
            is CommandOutcome.Rejected -> _messages.send(UiMessage.CommandRejected(outcome.result))
            is CommandOutcome.Failed -> _messages.send(UiMessage.CommandFailed(outcome.error))
        }
    }

    private companion object {
        const val SCAN_TIMEOUT_MS = 15_000L
        const val ADJUST_DEBOUNCE_MS = 350L
        const val DEFAULT_INCLINE_STEP = 0.5
    }
}
