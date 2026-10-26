package com.example.ftmstreadmill.ftms

import com.example.ftmstreadmill.ftms.model.ControlResult
import kotlin.math.roundToInt

/** A request written to the Fitness Machine Control Point (0x2AD9). */
sealed class ControlCommand(val opCode: Int) {
    data object RequestControl : ControlCommand(0x00)
    data object Reset : ControlCommand(0x01)
    data class SetTargetSpeed(val speedKmh: Double) : ControlCommand(0x02)
    data class SetTargetIncline(val inclinePercent: Double) : ControlCommand(0x03)
    data object StartOrResume : ControlCommand(0x07)
    data object Stop : ControlCommand(0x08)
    data object Pause : ControlCommand(0x08)
}

/** A Control Point indication answering a previously written command. */
data class ControlResponse(val requestOpCode: Int, val result: ControlResult)

object ControlPointCodec {
    private const val RESPONSE_OP_CODE = 0x80
    private const val STOP_PARAM = 0x01
    private const val PAUSE_PARAM = 0x02

    fun encode(command: ControlCommand): ByteArray = when (command) {
        is ControlCommand.SetTargetSpeed -> {
            val value = (command.speedKmh * 100).roundToInt().coerceIn(0, 0xFFFF)
            byteArrayOf(command.opCode.toByte(), value.toByte(), (value shr 8).toByte())
        }
        is ControlCommand.SetTargetIncline -> {
            val value = (command.inclinePercent * 10).roundToInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
            byteArrayOf(command.opCode.toByte(), value.toByte(), (value shr 8).toByte())
        }
        ControlCommand.Stop -> byteArrayOf(command.opCode.toByte(), STOP_PARAM.toByte())
        ControlCommand.Pause -> byteArrayOf(command.opCode.toByte(), PAUSE_PARAM.toByte())
        ControlCommand.RequestControl,
        ControlCommand.Reset,
        ControlCommand.StartOrResume -> byteArrayOf(command.opCode.toByte())
    }

    /** Returns the response, or null when [bytes] is not a Control Point response indication. */
    fun decodeResponse(bytes: ByteArray): ControlResponse? {
        if (bytes.size < 3 || (bytes[0].toInt() and 0xFF) != RESPONSE_OP_CODE) return null
        return ControlResponse(
            requestOpCode = bytes[1].toInt() and 0xFF,
            result = ControlResult.fromCode(bytes[2].toInt() and 0xFF),
        )
    }
}
