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

/**
 * A Control Point indication answering a previously written command. [parameter] holds any bytes
 * after the result code; the spec defines none for most commands, but some treadmills echo the
 * request's parameter there.
 */
data class ControlResponse(val requestOpCode: Int, val result: ControlResult, val parameter: List<Int> = emptyList())

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
            parameter = bytes.drop(3).map { it.toInt() and 0xFF },
        )
    }

    /**
     * Whether [response] answers [command]. Normally that means the request op codes match, but
     * some treadmills label the reply to Set Target Incline as Set Target Speed (`80 02 01 0A 00`
     * for a 1.0 % request). Such a reply still echoes the incline parameter, which tells it apart
     * from a genuine speed reply. Commands are sent one at a time, so only one can be waiting.
     */
    fun answers(response: ControlResponse, command: ControlCommand): Boolean {
        if (response.requestOpCode == command.opCode) return true
        if (command !is ControlCommand.SetTargetIncline || response.requestOpCode != SET_TARGET_SPEED) return false
        val requestParameter = encode(command).drop(1).map { it.toInt() and 0xFF }
        return response.parameter == requestParameter
    }

    private const val SET_TARGET_SPEED = 0x02
}
