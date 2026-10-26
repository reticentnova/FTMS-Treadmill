package com.example.ftmstreadmill.ble

import kotlinx.coroutines.Deferred
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import java.util.UUID

/** Entry point into Bluetooth LE. The Android implementation is [AndroidBleClient]; tests use a fake. */
interface BleClient {
    /** True while the Bluetooth adapter is switched on. */
    val adapterEnabled: StateFlow<Boolean>

    /** True when the runtime permissions needed to scan and connect are granted. */
    fun hasPermissions(): Boolean

    /** Emits advertising devices exposing [serviceUuid] until cancelled. Fails with [BleException]. */
    fun scan(serviceUuid: UUID): Flow<ScannedDevice>

    /** Opens a GATT connection to [address]. Throws [BleException] if the link cannot be established. */
    suspend fun connect(address: String): GattConnection
}

data class ScannedDevice(val address: String, val name: String?, val rssi: Int)

data class CharacteristicNotification(val characteristic: UUID, val value: ByteArray) {
    override fun equals(other: Any?) =
        other is CharacteristicNotification && characteristic == other.characteristic && value.contentEquals(other.value)

    override fun hashCode() = 31 * characteristic.hashCode() + value.contentHashCode()
}

/**
 * An open GATT link. All suspending operations are serialized (one in flight at a time) and time
 * out; they throw [BleException] on failure. Call [close] exactly when done; it is idempotent.
 */
interface GattConnection {
    val address: String

    /** Completes with the GATT status once the link is gone, whether closed locally or dropped remotely. */
    val disconnected: Deferred<Int>

    /** Notifications and indications from every subscribed characteristic. */
    val notifications: Flow<CharacteristicNotification>

    suspend fun discoverServices()
    suspend fun requestMtu(mtu: Int): Int
    fun hasCharacteristic(service: UUID, characteristic: UUID): Boolean
    suspend fun read(service: UUID, characteristic: UUID): ByteArray
    suspend fun write(service: UUID, characteristic: UUID, value: ByteArray)
    suspend fun enableNotifications(service: UUID, characteristic: UUID, indicate: Boolean)
    fun close()
}
