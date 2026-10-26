package com.example.ftmstreadmill.data

import com.example.ftmstreadmill.ble.BleError
import com.example.ftmstreadmill.ble.ScannedDevice
import com.example.ftmstreadmill.ftms.model.ControlResult
import com.example.ftmstreadmill.ftms.model.FtmsFeatures
import com.example.ftmstreadmill.ftms.model.MachineStatus
import com.example.ftmstreadmill.ftms.model.SupportedRange
import com.example.ftmstreadmill.ftms.model.TrainingStatus
import com.example.ftmstreadmill.ftms.model.TreadmillData
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/**
 * The single API for driving a treadmill. The UI uses it today; a future workout engine will call
 * the same suspend commands on interval boundaries.
 */
interface TreadmillController {
    val connectionState: StateFlow<ConnectionState>
    val treadmillState: StateFlow<TreadmillState>
    val events: Flow<TreadmillEvent>

    /** Scans for FTMS devices. Collect for as long as the picker is open. */
    fun scan(): Flow<ScannedDevice>

    fun connect(device: DeviceInfo)
    fun disconnect()

    suspend fun start(): CommandOutcome
    suspend fun pause(): CommandOutcome
    suspend fun stop(): CommandOutcome
    suspend fun setSpeed(kmh: Double): CommandOutcome
    suspend fun setIncline(percent: Double): CommandOutcome
}

data class DeviceInfo(val address: String, val name: String?)

sealed interface ConnectionState {
    /** No connection wanted. */
    data object Idle : ConnectionState

    /** A connection is wanted but the adapter is off; resumes automatically when it comes back. */
    data class BluetoothOff(val device: DeviceInfo) : ConnectionState
    data class Connecting(val device: DeviceInfo, val attempt: Int) : ConnectionState
    data class Initializing(val device: DeviceInfo) : ConnectionState
    data class Ready(val device: DeviceInfo, val hasControl: Boolean) : ConnectionState
    data class Reconnecting(val device: DeviceInfo, val attempt: Int, val delayMs: Long) : ConnectionState
    data class Failed(val device: DeviceInfo?, val error: BleError) : ConnectionState

    /** True while a connection is wanted, i.e. the foreground service should keep the process alive. */
    val isSessionActive: Boolean
        get() = this is BluetoothOff || this is Connecting || this is Initializing || this is Ready || this is Reconnecting
}

data class TreadmillState(
    val data: TreadmillData = TreadmillData(),
    val features: FtmsFeatures = FtmsFeatures.UNKNOWN,
    val speedRange: SupportedRange = SupportedRange.DEFAULT_SPEED_KMH,
    val inclineRange: SupportedRange = SupportedRange.DEFAULT_INCLINE_PERCENT,
    val machineStatus: MachineStatus? = null,
    val trainingStatus: TrainingStatus? = null,
    val targetSpeedKmh: Double? = null,
    val targetInclinePercent: Double? = null,
) {
    val isBeltMoving: Boolean get() = (data.speedKmh ?: 0.0) > 0.0
}

sealed interface CommandOutcome {
    data object Success : CommandOutcome
    data object NotConnected : CommandOutcome
    data class Rejected(val result: ControlResult) : CommandOutcome
    data class Failed(val error: BleError) : CommandOutcome
}

sealed interface TreadmillEvent {
    /** The link dropped without the user asking. [beltWasMoving] means the treadmill may still be running. */
    data class ConnectionLost(val beltWasMoving: Boolean) : TreadmillEvent
    data object Reconnected : TreadmillEvent
    data object ControlNotGranted : TreadmillEvent
    data object StoppedBySafetyKey : TreadmillEvent
}
