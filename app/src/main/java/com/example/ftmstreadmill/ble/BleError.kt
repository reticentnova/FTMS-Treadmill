package com.example.ftmstreadmill.ble

import java.util.UUID

/** Typed reasons a BLE operation can fail, so callers decide on retries without parsing messages. */
sealed interface BleError {
    data object AdapterOff : BleError
    data object PermissionMissing : BleError
    data object DeviceNotFound : BleError
    data object Disconnected : BleError
    data class ScanFailed(val errorCode: Int) : BleError
    data class GattStatus(val operation: String, val status: Int) : BleError
    data class Timeout(val operation: String) : BleError
    data class ServiceNotFound(val service: UUID) : BleError
    data class CharacteristicNotFound(val characteristic: UUID) : BleError
    data class OperationRejected(val operation: String) : BleError

    /** Errors where trying the same thing again soon has a realistic chance of succeeding. */
    val isTransient: Boolean
        get() = when (this) {
            Disconnected, DeviceNotFound, is GattStatus, is Timeout, is OperationRejected, is ScanFailed -> true
            AdapterOff, PermissionMissing, is ServiceNotFound, is CharacteristicNotFound -> false
        }
}

class BleException(val error: BleError) : Exception(error.toString())
