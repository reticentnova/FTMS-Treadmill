package com.example.ftmstreadmill.data

import android.util.Log
import com.example.ftmstreadmill.ble.BleClient
import com.example.ftmstreadmill.ble.BleError
import com.example.ftmstreadmill.ble.BleException
import com.example.ftmstreadmill.ble.CharacteristicNotification
import com.example.ftmstreadmill.ble.GattConnection
import com.example.ftmstreadmill.ble.ScannedDevice
import com.example.ftmstreadmill.di.ApplicationScope
import com.example.ftmstreadmill.ftms.ControlCommand
import com.example.ftmstreadmill.ftms.ControlPointCodec
import com.example.ftmstreadmill.ftms.ControlResponse
import com.example.ftmstreadmill.ftms.FtmsParseException
import com.example.ftmstreadmill.ftms.FtmsParsers
import com.example.ftmstreadmill.ftms.FtmsUuids
import com.example.ftmstreadmill.ftms.TreadmillDataParser
import com.example.ftmstreadmill.ftms.model.ControlResult
import com.example.ftmstreadmill.ftms.model.FtmsFeatures
import com.example.ftmstreadmill.ftms.model.MachineStatus
import com.example.ftmstreadmill.ftms.model.SupportedRange
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Owns the treadmill connection for the whole app process.
 *
 * Each [connect] starts a *session*: a coroutine that loops connect → initialize → wait for
 * disconnect → back off → reconnect until [disconnect] is called or a non-recoverable error occurs.
 * State writes carry the session id so a cancelled session can never overwrite a newer one.
 */
@Singleton
class TreadmillRepository @Inject constructor(
    private val ble: BleClient,
    private val settings: SettingsRepository,
    @ApplicationScope private val scope: CoroutineScope,
) : TreadmillController {

    private val _connectionState = MutableStateFlow<ConnectionState>(ConnectionState.Idle)
    override val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    private val _treadmillState = MutableStateFlow(TreadmillState())
    override val treadmillState: StateFlow<TreadmillState> = _treadmillState.asStateFlow()

    private val _events = MutableSharedFlow<TreadmillEvent>(extraBufferCapacity = 16)
    override val events: Flow<TreadmillEvent> = _events.asSharedFlow()

    private val controlResponses = MutableSharedFlow<ControlResponse>(extraBufferCapacity = 16)
    private val commandMutex = Mutex()
    private val autoReconnect = settings.settings.map { it.autoReconnect }
        .stateIn(scope, SharingStarted.Eagerly, initialValue = true)

    private val lock = Any()
    private var sessionId = 0L
    private var sessionJob: Job? = null

    @Volatile private var connection: GattConnection? = null

    override fun scan(): Flow<ScannedDevice> = ble.scan(FtmsUuids.SERVICE)

    override fun connect(device: DeviceInfo) {
        synchronized(lock) {
            sessionJob?.cancel()
            val session = ++sessionId
            _treadmillState.value = TreadmillState()
            _connectionState.value = ConnectionState.Connecting(device, attempt = 1)
            sessionJob = scope.launch { runSession(device, session) }
        }
        scope.launch {
            try {
                settings.setLastDevice(device)
            } catch (e: Exception) {
                Log.w(TAG, "Could not persist last device", e)
            }
        }
    }

    override fun disconnect() {
        synchronized(lock) {
            sessionId++
            sessionJob?.cancel()
            sessionJob = null
            connection = null
            _connectionState.value = ConnectionState.Idle
            _treadmillState.value = TreadmillState()
        }
    }

    override suspend fun start() = execute(ControlCommand.StartOrResume)

    override suspend fun pause() = execute(ControlCommand.Pause)

    override suspend fun stop() = execute(ControlCommand.Stop)

    override suspend fun setSpeed(kmh: Double): CommandOutcome {
        val target = _treadmillState.value.speedRange.clampAndSnap(kmh)
        return execute(ControlCommand.SetTargetSpeed(target)).also {
            if (it == CommandOutcome.Success) _treadmillState.update { s -> s.copy(targetSpeedKmh = target) }
        }
    }

    override suspend fun setIncline(percent: Double): CommandOutcome {
        val target = _treadmillState.value.inclineRange.clampAndSnap(percent)
        return execute(ControlCommand.SetTargetIncline(target)).also {
            if (it == CommandOutcome.Success) _treadmillState.update { s -> s.copy(targetInclinePercent = target) }
        }
    }

    private suspend fun execute(command: ControlCommand): CommandOutcome {
        val conn = connection ?: return CommandOutcome.NotConnected
        return sendCommand(conn, command, retryOnControlLoss = true)
    }

    // ---- Session loop --------------------------------------------------------------------------

    private fun setState(session: Long, state: ConnectionState) = synchronized(lock) {
        if (session == sessionId) _connectionState.value = state
    }

    private suspend fun runSession(device: DeviceInfo, session: Long) {
        var attempt = 1
        var everReady = false
        while (true) {
            if (!ble.adapterEnabled.value) {
                setState(session, ConnectionState.BluetoothOff(device))
                ble.adapterEnabled.first { it }
                delay(ADAPTER_SETTLE_MS) // The stack rejects connections for a moment after power-on.
            }

            var readyThisAttempt = false
            val error = try {
                connectAndRun(device, session, attempt) {
                    if (everReady) _events.tryEmit(TreadmillEvent.Reconnected)
                    readyThisAttempt = true
                    everReady = true
                }
                BleError.Disconnected
            } catch (e: BleException) {
                e.error
            }
            Log.i(TAG, "Session $session attempt $attempt ended: $error")

            if (readyThisAttempt) {
                _events.tryEmit(TreadmillEvent.ConnectionLost(_treadmillState.value.isBeltMoving))
                attempt = 1
            } else {
                attempt++
            }

            when {
                // Adapter switched off: go back to the top and wait for it.
                error == BleError.AdapterOff || !ble.adapterEnabled.value -> continue
                !error.isTransient -> return setState(session, ConnectionState.Failed(device, error))
                !everReady && attempt > MAX_INITIAL_ATTEMPTS -> return setState(session, ConnectionState.Failed(device, error))
                everReady && !autoReconnect.value -> return setState(session, ConnectionState.Failed(device, error))
            }

            val backoff = if (everReady) reconnectBackoffMs(attempt) else INITIAL_RETRY_DELAY_MS
            setState(session, ConnectionState.Reconnecting(device, attempt, backoff))
            delay(backoff)
        }
    }

    /** Connects, initializes FTMS and suspends until the link drops. Throws [BleException] on failure. */
    private suspend fun connectAndRun(device: DeviceInfo, session: Long, attempt: Int, onReady: () -> Unit) {
        setState(session, ConnectionState.Connecting(device, attempt))
        val conn = ble.connect(device.address)
        try {
            coroutineScope {
                setState(session, ConnectionState.Initializing(device))
                conn.discoverServices()
                try {
                    conn.requestMtu(DESIRED_MTU)
                } catch (e: BleException) {
                    Log.w(TAG, "MTU request failed; continuing with default", e)
                }

                val features = conn.readOptional(FtmsUuids.FEATURE, FtmsParsers::features) ?: FtmsFeatures.UNKNOWN
                val speedRange = conn.readOptional(FtmsUuids.SUPPORTED_SPEED_RANGE, FtmsParsers::speedRange)
                    ?: SupportedRange.DEFAULT_SPEED_KMH
                val inclineRange = conn.readOptional(FtmsUuids.SUPPORTED_INCLINATION_RANGE, FtmsParsers::inclineRange)
                    ?: SupportedRange.DEFAULT_INCLINE_PERCENT
                _treadmillState.update {
                    it.copy(features = features, speedRange = speedRange, inclineRange = inclineRange)
                }

                // Subscribe to the shared flow before enabling notifications so nothing is missed.
                launch(start = CoroutineStart.UNDISPATCHED) {
                    conn.notifications.collect { onNotification(it, conn, session, device) }
                }

                conn.enableNotifications(FtmsUuids.SERVICE, FtmsUuids.TREADMILL_DATA, indicate = false)
                conn.enableOptional(FtmsUuids.MACHINE_STATUS, indicate = false)
                conn.enableOptional(FtmsUuids.TRAINING_STATUS, indicate = false)
                val hasControlPoint = conn.enableOptional(FtmsUuids.CONTROL_POINT, indicate = true)

                val hasControl = hasControlPoint &&
                    sendCommand(conn, ControlCommand.RequestControl, retryOnControlLoss = false) == CommandOutcome.Success
                if (!hasControl) _events.tryEmit(TreadmillEvent.ControlNotGranted)

                connection = conn
                setState(session, ConnectionState.Ready(device, hasControl))
                onReady()

                // Close promptly when the adapter turns off rather than waiting for a supervision timeout.
                launch {
                    ble.adapterEnabled.first { !it }
                    conn.close()
                }
                conn.disconnected.await()
                coroutineContext.cancelChildren()
            }
        } finally {
            if (connection === conn) connection = null
            conn.close()
        }
    }

    private suspend fun <T> GattConnection.readOptional(characteristic: UUID, parse: (ByteArray) -> T): T? {
        if (!hasCharacteristic(FtmsUuids.SERVICE, characteristic)) return null
        return try {
            parse(read(FtmsUuids.SERVICE, characteristic))
        } catch (e: FtmsParseException) {
            Log.w(TAG, "Malformed $characteristic", e)
            null
        }
    }

    private suspend fun GattConnection.enableOptional(characteristic: UUID, indicate: Boolean): Boolean {
        if (!hasCharacteristic(FtmsUuids.SERVICE, characteristic)) return false
        enableNotifications(FtmsUuids.SERVICE, characteristic, indicate)
        return true
    }

    private fun CoroutineScope.onNotification(
        notification: CharacteristicNotification,
        conn: GattConnection,
        session: Long,
        device: DeviceInfo,
    ) {
        try {
            when (notification.characteristic) {
                FtmsUuids.TREADMILL_DATA -> {
                    val parsed = TreadmillDataParser.parse(notification.value)
                    _treadmillState.update { it.copy(data = it.data.mergedWith(parsed)) }
                }
                FtmsUuids.TRAINING_STATUS -> {
                    val status = FtmsParsers.trainingStatus(notification.value)
                    _treadmillState.update { it.copy(trainingStatus = status) }
                }
                FtmsUuids.MACHINE_STATUS -> onMachineStatus(FtmsParsers.machineStatus(notification.value), conn, session, device)
                FtmsUuids.CONTROL_POINT -> ControlPointCodec.decodeResponse(notification.value)?.let(controlResponses::tryEmit)
            }
        } catch (e: FtmsParseException) {
            Log.w(TAG, "Dropping malformed notification from ${notification.characteristic}", e)
        }
    }

    private fun CoroutineScope.onMachineStatus(status: MachineStatus, conn: GattConnection, session: Long, device: DeviceInfo) {
        _treadmillState.update {
            when (status) {
                is MachineStatus.TargetSpeedChanged -> it.copy(machineStatus = status, targetSpeedKmh = status.speedKmh)
                is MachineStatus.TargetInclineChanged -> it.copy(machineStatus = status, targetInclinePercent = status.inclinePercent)
                else -> it.copy(machineStatus = status)
            }
        }
        when (status) {
            MachineStatus.StoppedBySafetyKey -> _events.tryEmit(TreadmillEvent.StoppedBySafetyKey)
            MachineStatus.ControlPermissionLost -> launch {
                val regained = sendCommand(conn, ControlCommand.RequestControl, retryOnControlLoss = false) == CommandOutcome.Success
                setState(session, ConnectionState.Ready(device, regained))
                if (!regained) _events.tryEmit(TreadmillEvent.ControlNotGranted)
            }
            else -> Unit
        }
    }

    /**
     * Writes [command] to the control point and waits for its response indication. Commands are
     * serialized so responses can be matched to requests by op code.
     */
    private suspend fun sendCommand(conn: GattConnection, command: ControlCommand, retryOnControlLoss: Boolean): CommandOutcome {
        val outcome = commandMutex.withLock {
            coroutineScope {
                val response = async(start = CoroutineStart.UNDISPATCHED) {
                    controlResponses.first { it.requestOpCode == command.opCode }
                }
                try {
                    conn.write(FtmsUuids.SERVICE, FtmsUuids.CONTROL_POINT, ControlPointCodec.encode(command))
                } catch (e: BleException) {
                    response.cancel()
                    return@coroutineScope CommandOutcome.Failed(e.error)
                }
                val result = withTimeoutOrNull(CONTROL_RESPONSE_TIMEOUT_MS) { response.await() }?.result
                when (result) {
                    null -> {
                        response.cancel()
                        CommandOutcome.Failed(BleError.Timeout("control point response"))
                    }
                    ControlResult.SUCCESS -> CommandOutcome.Success
                    else -> CommandOutcome.Rejected(result)
                }
            }
        }
        if (retryOnControlLoss && outcome == CommandOutcome.Rejected(ControlResult.CONTROL_NOT_PERMITTED)) {
            Log.i(TAG, "Control not permitted for $command; requesting control and retrying once")
            if (sendCommand(conn, ControlCommand.RequestControl, retryOnControlLoss = false) == CommandOutcome.Success) {
                return sendCommand(conn, command, retryOnControlLoss = false)
            }
        }
        if (outcome != CommandOutcome.Success) Log.w(TAG, "$command -> $outcome")
        return outcome
    }

    internal companion object {
        private const val TAG = "TreadmillRepository"
        const val MAX_INITIAL_ATTEMPTS = 3
        const val INITIAL_RETRY_DELAY_MS = 1_000L
        const val ADAPTER_SETTLE_MS = 1_000L
        const val CONTROL_RESPONSE_TIMEOUT_MS = 3_000L
        private const val DESIRED_MTU = 185
        private const val MAX_BACKOFF_MS = 30_000L

        fun reconnectBackoffMs(attempt: Int): Long =
            (1_000L shl (attempt - 1).coerceIn(0, 5)).coerceAtMost(MAX_BACKOFF_MS)
    }
}
