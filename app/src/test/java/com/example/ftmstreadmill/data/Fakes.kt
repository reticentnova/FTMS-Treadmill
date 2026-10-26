package com.example.ftmstreadmill.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import com.example.ftmstreadmill.ble.BleClient
import com.example.ftmstreadmill.ble.BleError
import com.example.ftmstreadmill.ble.BleException
import com.example.ftmstreadmill.ble.CharacteristicNotification
import com.example.ftmstreadmill.ble.GattConnection
import com.example.ftmstreadmill.ble.ScannedDevice
import com.example.ftmstreadmill.ftms.FtmsUuids
import com.example.ftmstreadmill.ftms.model.ControlResult
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import java.util.UUID

class InMemoryPreferencesDataStore : DataStore<Preferences> {
    private val state = MutableStateFlow(emptyPreferences())
    override val data: Flow<Preferences> = state

    override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences =
        transform(state.value).also { state.value = it }
}

class FakeBleClient : BleClient {
    override val adapterEnabled = MutableStateFlow(true)
    var permissionsGranted = true

    /** Each connect() pops the next behaviour; when empty, connections succeed with a default fake. */
    val connectQueue = ArrayDeque<() -> FakeGattConnection>()
    val connections = mutableListOf<FakeGattConnection>()
    var connectCalls = 0
        private set

    override fun hasPermissions() = permissionsGranted

    override fun scan(serviceUuid: UUID): Flow<ScannedDevice> = emptyFlow()

    override suspend fun connect(address: String): GattConnection {
        connectCalls++
        if (!permissionsGranted) throw BleException(BleError.PermissionMissing)
        if (!adapterEnabled.value) throw BleException(BleError.AdapterOff)
        val connection = connectQueue.removeFirstOrNull()?.invoke() ?: FakeGattConnection(address)
        connections += connection
        return connection
    }

    fun failNextConnects(times: Int, error: BleError = BleError.GattStatus("connect", 133)) {
        repeat(times) { connectQueue.addLast { throw BleException(error) } }
    }
}

class FakeGattConnection(
    override val address: String = "AA:BB:CC:DD:EE:FF",
    private val characteristics: Set<UUID> = setOf(
        FtmsUuids.FEATURE,
        FtmsUuids.TREADMILL_DATA,
        FtmsUuids.TRAINING_STATUS,
        FtmsUuids.SUPPORTED_SPEED_RANGE,
        FtmsUuids.SUPPORTED_INCLINATION_RANGE,
        FtmsUuids.CONTROL_POINT,
        FtmsUuids.MACHINE_STATUS,
    ),
) : GattConnection {
    private val _disconnected = CompletableDeferred<Int>()
    override val disconnected: Deferred<Int> = _disconnected
    override val notifications = MutableSharedFlow<CharacteristicNotification>(extraBufferCapacity = 64)

    val enabled = mutableListOf<UUID>()
    val controlWrites = mutableListOf<ByteArray>()
    var closed = false
        private set

    /** Result returned for each control op code; queue entries are consumed first. */
    val controlResults = mutableMapOf<Int, ArrayDeque<ControlResult>>()
    var defaultControlResult = ControlResult.SUCCESS
    var respondToControl = true

    var readValues = mutableMapOf(
        FtmsUuids.FEATURE to byteArrayOf(0, 0, 0, 0, 0x03, 0, 0, 0),
        // 0.5 – 20.0 km/h, step 0.1
        FtmsUuids.SUPPORTED_SPEED_RANGE to byteArrayOf(0x32, 0x00, 0xD0.toByte(), 0x07, 0x0A, 0x00),
        // 0 – 15 %, step 0.5
        FtmsUuids.SUPPORTED_INCLINATION_RANGE to byteArrayOf(0x00, 0x00, 0x96.toByte(), 0x00, 0x05, 0x00),
    )

    override suspend fun discoverServices() = Unit
    override suspend fun requestMtu(mtu: Int) = mtu
    override fun hasCharacteristic(service: UUID, characteristic: UUID) = characteristic in characteristics

    override suspend fun read(service: UUID, characteristic: UUID): ByteArray {
        ensureOpen()
        return readValues[characteristic] ?: throw BleException(BleError.GattStatus("READ", 2))
    }

    override suspend fun write(service: UUID, characteristic: UUID, value: ByteArray) {
        ensureOpen()
        if (characteristic == FtmsUuids.CONTROL_POINT) {
            controlWrites += value
            if (respondToControl) {
                val opCode = value[0].toInt() and 0xFF
                val result = controlResults[opCode]?.removeFirstOrNull() ?: defaultControlResult
                notify(FtmsUuids.CONTROL_POINT, byteArrayOf(0x80.toByte(), opCode.toByte(), result.code.toByte()))
            }
        }
    }

    override suspend fun enableNotifications(service: UUID, characteristic: UUID, indicate: Boolean) {
        ensureOpen()
        enabled += characteristic
    }

    override fun close() {
        closed = true
        _disconnected.complete(0)
    }

    fun notify(characteristic: UUID, value: ByteArray) {
        notifications.tryEmit(CharacteristicNotification(characteristic, value))
    }

    /** Simulates the remote device dropping the link. */
    fun dropLink(status: Int = 8) {
        _disconnected.complete(status)
    }

    private fun ensureOpen() {
        if (closed || _disconnected.isCompleted) throw BleException(BleError.Disconnected)
    }
}
