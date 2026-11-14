package com.example.ftmstreadmill.ftms

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SpeedEchoRepairTest {
    private val echo = SpeedEchoRepair()
    private val max = 20.0

    @Test
    fun `echo with the high byte zeroed maps back to the command`() {
        // Captured: sent 02 E0 01 (4.80), treadmill rounded to 3.0 mph (4.82) and sent 05 E2 00.
        echo.commanded(4.80)
        assertEquals(4.80, echo.repair(2.26, beltKmh = 0.8, maxKmh = max), 1e-9)
        assertTrue(echo.dropsHighByte)
        // The treadmill repeats the echo every few seconds; still the command.
        assertEquals(4.80, echo.repair(2.26, beltKmh = 4.82, maxKmh = max), 1e-9)
    }

    @Test
    fun `other captured echoes are repaired`() {
        echo.commanded(5.0) // 3.1 mph, echoed 05 F2 00
        assertEquals(5.0, echo.repair(2.42, beltKmh = 4.82, maxKmh = max), 1e-9)
        echo.commanded(5.1) // 3.2 mph, echoed 05 02 00
        assertEquals(5.1, echo.repair(0.02, beltKmh = 4.99, maxKmh = max), 1e-9)
        echo.commanded(3.2) // 2.0 mph, echoed 05 41 00
        assertEquals(3.2, echo.repair(0.65, beltKmh = 5.14, maxKmh = max), 1e-9)
    }

    @Test
    fun `console change is rebuilt from the belt speed once detected`() {
        echo.commanded(4.80)
        echo.repair(2.26, beltKmh = 0.8, maxKmh = max)
        // Console + to 3.1 mph = 4.99 (0x01F3) while the belt is still at 4.82.
        assertEquals(4.99, echo.repair(2.43, beltKmh = 4.82, maxKmh = max), 1e-9)
    }

    @Test
    fun `small values that fit in one byte are not mistaken for a dropped byte`() {
        // Treadmill start-up echoes 05 32 00 and 05 50 00 before any command.
        assertEquals(0.5, echo.repair(0.5, beltKmh = null, maxKmh = max), 1e-9)
        assertEquals(0.8, echo.repair(0.8, beltKmh = 0.5, maxKmh = max), 1e-9)
        assertFalse(echo.dropsHighByte)
    }

    @Test
    fun `spec compliant treadmill passes through`() {
        echo.commanded(4.80)
        assertEquals(4.80, echo.repair(4.82, beltKmh = 3.0, maxKmh = max), 1e-9)
        assertFalse(echo.dropsHighByte)
        // A console change to a low speed is not "repaired" upwards.
        assertEquals(2.0, echo.repair(2.0, beltKmh = 4.8, maxKmh = max), 1e-9)
    }

    @Test
    fun `reset forgets the quirk`() {
        echo.commanded(4.80)
        echo.repair(2.26, beltKmh = 0.8, maxKmh = max)
        echo.reset()
        assertEquals(2.26, echo.repair(2.26, beltKmh = 4.82, maxKmh = max), 1e-9)
    }
}
