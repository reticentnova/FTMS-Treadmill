package com.example.ftmstreadmill.ftms

import com.example.ftmstreadmill.ftms.model.FtmsFeatures
import com.example.ftmstreadmill.ftms.model.MachineStatus
import com.example.ftmstreadmill.ftms.model.SupportedRange
import com.example.ftmstreadmill.ftms.model.TrainingStatus

/** Parsers for the smaller FTMS characteristics. */
object FtmsParsers {

    /** Fitness Machine Feature (0x2ACC): uint32 machine features + uint32 target setting features. */
    fun features(bytes: ByteArray): FtmsFeatures {
        val r = ByteReader(bytes)
        return FtmsFeatures(machineFeatures = r.uint32(), targetSettingFeatures = r.uint32())
    }

    /** Supported Speed Range (0x2AD4): uint16 min, max, increment in 0.01 km/h. */
    fun speedRange(bytes: ByteArray): SupportedRange {
        val r = ByteReader(bytes)
        return SupportedRange(min = r.uint16() / 100.0, max = r.uint16() / 100.0, step = r.uint16() / 100.0)
    }

    /** Supported Inclination Range (0x2AD5): sint16 min, sint16 max, uint16 increment in 0.1 %. */
    fun inclineRange(bytes: ByteArray): SupportedRange {
        val r = ByteReader(bytes)
        return SupportedRange(min = r.sint16() / 10.0, max = r.sint16() / 10.0, step = r.uint16() / 10.0)
    }

    /** Fitness Machine Status (0x2ADA): op code followed by an op-code-specific parameter. */
    fun machineStatus(bytes: ByteArray): MachineStatus {
        val r = ByteReader(bytes)
        return when (val opCode = r.uint8()) {
            0x01 -> MachineStatus.Reset
            0x02 -> if (r.remaining > 0 && r.uint8() == 0x02) MachineStatus.PausedByUser else MachineStatus.StoppedByUser
            0x03 -> MachineStatus.StoppedBySafetyKey
            0x04 -> MachineStatus.StartedOrResumedByUser
            0x05 -> MachineStatus.TargetSpeedChanged(r.uint16() / 100.0)
            0x06 -> MachineStatus.TargetInclineChanged(r.sint16() / 10.0)
            0xFF -> MachineStatus.ControlPermissionLost
            else -> MachineStatus.Other(opCode)
        }
    }

    /** Training Status (0x2AD3): uint8 flags, uint8 status, optional UTF-8 string. */
    fun trainingStatus(bytes: ByteArray): TrainingStatus {
        val r = ByteReader(bytes)
        r.uint8() // flags; the optional status string is not used
        return TrainingStatus.fromCode(r.uint8())
    }
}
