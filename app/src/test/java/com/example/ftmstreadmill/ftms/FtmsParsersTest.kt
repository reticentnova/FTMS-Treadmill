package com.example.ftmstreadmill.ftms

import com.example.ftmstreadmill.ftms.model.ControlResult
import com.example.ftmstreadmill.ftms.model.MachineStatus
import com.example.ftmstreadmill.ftms.model.SupportedRange
import com.example.ftmstreadmill.ftms.model.TrainingStatus
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FtmsParsersTest {

    @Test
    fun `features decode target setting bits`() {
        val features = FtmsParsers.features(bytes(0x0F, 0x00, 0x00, 0x00, 0x02, 0x00, 0x00, 0x00))
        assertEquals(0x0FL, features.machineFeatures)
        assertFalse(features.speedTargetSupported)
        assertTrue(features.inclineTargetSupported)
    }

    @Test
    fun `speed range in hundredths of km per hour`() {
        val range = FtmsParsers.speedRange(bytes(0x64, 0x00, 0xD0, 0x07, 0x0A, 0x00))
        assertEquals(SupportedRange(min = 1.0, max = 20.0, step = 0.1), range)
    }

    @Test
    fun `incline range is signed`() {
        val range = FtmsParsers.inclineRange(bytes(0xCE, 0xFF, 0x96, 0x00, 0x05, 0x00))
        assertEquals(SupportedRange(min = -5.0, max = 15.0, step = 0.5), range)
    }

    @Test
    fun `machine status op codes`() {
        assertEquals(MachineStatus.StoppedByUser, FtmsParsers.machineStatus(bytes(0x02, 0x01)))
        assertEquals(MachineStatus.PausedByUser, FtmsParsers.machineStatus(bytes(0x02, 0x02)))
        assertEquals(MachineStatus.StoppedBySafetyKey, FtmsParsers.machineStatus(bytes(0x03)))
        assertEquals(MachineStatus.StartedOrResumedByUser, FtmsParsers.machineStatus(bytes(0x04)))
        assertEquals(MachineStatus.TargetSpeedChanged(6.5), FtmsParsers.machineStatus(bytes(0x05, 0x8A, 0x02)))
        assertEquals(MachineStatus.TargetInclineChanged(-2.0), FtmsParsers.machineStatus(bytes(0x06, 0xEC, 0xFF)))
        assertEquals(MachineStatus.ControlPermissionLost, FtmsParsers.machineStatus(bytes(0xFF)))
        assertEquals(MachineStatus.Other(0x10), FtmsParsers.machineStatus(bytes(0x10)))
    }

    @Test
    fun `training status`() {
        assertEquals(TrainingStatus.MANUAL_MODE, FtmsParsers.trainingStatus(bytes(0x00, 0x0D)))
        assertEquals(TrainingStatus.OTHER, FtmsParsers.trainingStatus(bytes(0x00, 0x7F)))
    }

    @Test
    fun `control point encoding`() {
        assertArrayEquals(bytes(0x00), ControlPointCodec.encode(ControlCommand.RequestControl))
        assertArrayEquals(bytes(0x07), ControlPointCodec.encode(ControlCommand.StartOrResume))
        assertArrayEquals(bytes(0x08, 0x01), ControlPointCodec.encode(ControlCommand.Stop))
        assertArrayEquals(bytes(0x08, 0x02), ControlPointCodec.encode(ControlCommand.Pause))
        assertArrayEquals(bytes(0x02, 0x8A, 0x02), ControlPointCodec.encode(ControlCommand.SetTargetSpeed(6.5)))
        assertArrayEquals(bytes(0x03, 0xEC, 0xFF), ControlPointCodec.encode(ControlCommand.SetTargetIncline(-2.0)))
    }

    @Test
    fun `control point response decoding`() {
        assertEquals(
            ControlResponse(0x02, ControlResult.INVALID_PARAMETER),
            ControlPointCodec.decodeResponse(bytes(0x80, 0x02, 0x03)),
        )
        assertNull(ControlPointCodec.decodeResponse(bytes(0x02, 0x01)))
    }

    @Test
    fun `range clamps and snaps to step`() {
        val range = SupportedRange(min = 1.0, max = 20.0, step = 0.5)
        assertEquals(1.0, range.clampAndSnap(0.0), 1e-9)
        assertEquals(20.0, range.clampAndSnap(25.0), 1e-9)
        assertEquals(6.5, range.clampAndSnap(6.4), 1e-9)
        assertEquals(6.0, range.clampAndSnap(6.2), 1e-9)
    }
}
