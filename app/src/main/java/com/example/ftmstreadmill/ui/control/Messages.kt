package com.example.ftmstreadmill.ui.control

import android.content.res.Resources
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalResources
import com.example.ftmstreadmill.R
import com.example.ftmstreadmill.ble.BleError
import com.example.ftmstreadmill.ftms.model.ControlResult

/** Returns a resolver from [UiMessage] to user-facing text, bound to the current resources. */
@Composable
fun messageStrings(): (UiMessage) -> String {
    val resources = LocalResources.current
    return { message -> resources.message(message) }
}

@Composable
fun bleErrorText(error: BleError): String = LocalResources.current.bleError(error)

private fun Resources.message(message: UiMessage): String = when (message) {
    UiMessage.NotConnected -> getString(R.string.msg_not_connected)
    UiMessage.ConnectionLost -> getString(R.string.msg_connection_lost)
    UiMessage.Reconnected -> getString(R.string.msg_reconnected)
    UiMessage.ControlNotGranted -> getString(R.string.msg_control_not_granted)
    UiMessage.SafetyKey -> getString(R.string.msg_safety_key)
    is UiMessage.CommandFailed -> getString(R.string.msg_command_failed, bleError(message.error))
    is UiMessage.ScanFailed -> getString(R.string.msg_scan_failed, bleError(message.error))
    is UiMessage.CommandRejected -> getString(
        when (message.result) {
            ControlResult.NOT_SUPPORTED -> R.string.msg_rejected_not_supported
            ControlResult.INVALID_PARAMETER -> R.string.msg_rejected_invalid
            ControlResult.CONTROL_NOT_PERMITTED -> R.string.msg_rejected_not_permitted
            ControlResult.OPERATION_FAILED, ControlResult.UNKNOWN, ControlResult.SUCCESS -> R.string.msg_rejected_failed
        },
    )
}

private fun Resources.bleError(error: BleError): String = when (error) {
    BleError.AdapterOff -> getString(R.string.error_adapter_off)
    BleError.PermissionMissing -> getString(R.string.error_permission)
    BleError.DeviceNotFound -> getString(R.string.error_not_found)
    BleError.Disconnected -> getString(R.string.error_disconnected)
    is BleError.Timeout -> getString(R.string.error_timeout)
    is BleError.ServiceNotFound, is BleError.CharacteristicNotFound -> getString(R.string.error_not_ftms)
    is BleError.GattStatus -> getString(R.string.error_gatt, error.status)
    is BleError.ScanFailed -> getString(R.string.error_gatt, error.errorCode)
    is BleError.OperationRejected -> getString(R.string.error_generic)
}
