package com.example.ftmstreadmill.ftms

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Raw values below are captured `05 xx 00` echoes from a treadmill that zeroes the high byte. */
class SpeedEchoRepairTest {
    private val echo = SpeedEchoRepair()
    private val max = 20.0
    private var now = 0L

    private fun repair(raw: Double, belt: Double?) = echo.repair(raw, belt, max, now)

    /** Feeds steady belt readings every 100 ms for [ms]; returns the last settle result. */
    private fun beltSteady(kmh: Double, ms: Long): Double? {
        var result: Double? = null
        repeat((ms / 100).toInt() + 1) {
            echo.settle(kmh, now)?.let { result = it }
            now += 100
        }
        return result
    }

    /** Sent 4.80 (02 E0 01); treadmill rounded to 3.0 mph (4.82) and echoed 05 E2 00. */
    private fun latch() {
        echo.commanded(4.80, now)
        assertEquals(4.80, repair(2.26, belt = 0.8), 1e-9)
    }

    @Test
    fun `echo with the high byte zeroed maps back to the command`() {
        latch()
        assertTrue(echo.dropsHighByte)
        now += 3_000
        assertEquals("periodic re-send", 4.80, repair(2.26, belt = 4.82), 1e-9)
    }

    @Test
    fun `other captured command echoes are repaired`() {
        latch()
        echo.commanded(4.96, now) // 3.1 mph, echoed 05 F2 00
        assertEquals(4.96, repair(2.42, belt = 4.82), 1e-9)
        echo.commanded(5.12, now) // 3.2 mph, echoed 05 02 00
        assertEquals(5.12, repair(0.02, belt = 4.98), 1e-9)
        echo.commanded(3.20, now) // 2.0 mph, echoed 05 41 00
        assertEquals(3.20, repair(0.65, belt = 5.14), 1e-9)
    }

    @Test
    fun `scroll wheel run is followed across byte wraps`() {
        latch()
        echo.commanded(3.20, now)
        repair(0.65, belt = 3.2)
        // Wheel up from 2.0 mph in 0.1 mph steps, much faster than the belt follows.
        val raws = listOf(0.81, 0.97, 1.14, 1.30, 1.46, 1.62, 1.78, 1.94, 2.10, 2.26, 2.42, 0.02, 0.18, 0.35, 0.51, 0.67, 0.83, 0.99)
        val expected = listOf(3.37, 3.53, 3.70, 3.86, 4.02, 4.18, 4.34, 4.50, 4.66, 4.82, 4.98, 5.14, 5.30, 5.47, 5.63, 5.79, 5.95, 6.11)
        raws.zip(expected).forEach { (raw, want) ->
            now += 40
            assertEquals("raw $raw", want, repair(raw, belt = 3.3), 1e-9)
        }
    }

    @Test
    fun `scroll wheel down wraps the other way`() {
        latch()
        echo.commanded(3.20, now)
        repair(0.65, belt = 3.2)
        val raws = listOf(0.49, 0.33, 0.17, 0.01, 2.41, 2.25, 2.09)
        val expected = listOf(3.05, 2.89, 2.73, 2.57, 2.41, 2.25, 2.09)
        raws.zip(expected).forEach { (raw, want) -> assertEquals("raw $raw", want, repair(raw, belt = 3.1), 1e-9) }
    }

    @Test
    fun `treadmill start-up ramp is followed`() {
        latch()
        // New workout on the same connection: belt stopped, treadmill starts at 0.5 then 0.8 km/h.
        now += 60_000
        echo.commanded(4.80, now)
        assertEquals(0.5, repair(0.5, belt = null), 1e-9)
        assertEquals(0.8, repair(0.8, belt = 0.0), 1e-9)
        // Seconds later it ramps its own target up in 0.1 mph steps to ours.
        now += 5_000
        val raws = listOf(0.96, 1.12, 1.28, 1.44, 1.60, 1.76, 1.93, 2.09, 2.25, 2.41, 0.01, 0.17, 0.33)
        val expected = listOf(0.96, 1.12, 1.28, 1.44, 1.60, 1.76, 1.93, 2.09, 2.25, 2.41, 2.57, 2.73, 2.89)
        raws.zip(expected).forEach { (raw, want) -> assertEquals("raw $raw", want, repair(raw, belt = 0.8), 1e-9) }
    }

    @Test
    fun `start-up ramp on a fresh connection detects the quirk at the byte wrap`() {
        // Captured order: Start, our 4.80 lost in the treadmill's countdown, then its ramp.
        echo.commanded(4.80, now)
        assertEquals(0.5, repair(0.5, belt = null), 1e-9)
        now += 4_000
        assertEquals(0.8, repair(0.8, belt = 0.0), 1e-9)
        now += 3_000
        listOf(0.96, 1.12, 1.28, 1.44, 1.60, 1.76, 1.93, 2.09, 2.25, 2.41).forEach {
            now += 50
            assertEquals(it, repair(it, belt = 0.8), 1e-9)
        }
        assertFalse(echo.dropsHighByte)
        now += 50
        assertEquals(2.57, repair(0.01, belt = 0.9), 1e-9)
        assertTrue(echo.dropsHighByte)
        val raws = listOf(0.17, 0.33, 0.49, 0.65, 0.81, 0.97, 1.14, 1.30, 1.46, 1.62, 1.78, 1.94, 2.10, 2.26)
        raws.forEach { now += 50; repair(it, belt = 1.2) }
        assertEquals("ramp ends on our command", 4.80, repair(2.26, belt = 1.5), 1e-9)
    }

    @Test
    fun `preset jump is corrected once the belt settles`() {
        latch()
        beltSteady(4.82, 2_000)
        // Preset to 12 mph (19.29 = 0x0789) arrives as 05 89 00 = 1.37; continuity guesses wrong.
        now += 100
        val guess = repair(1.37, belt = 4.82)
        assertEquals(3.93, guess, 1e-9)
        // The belt still sitting at the old speed must not "confirm" anything.
        assertNull(beltSteady(4.82, 2_000))
        // Belt accelerates, then holds 19.30.
        var speed = 5.0
        while (speed < 19.3) {
            echo.settle(speed, now)
            now += 100
            speed += 0.5
        }
        assertEquals(19.29, beltSteady(19.30, 2_000)!!, 1e-9)
        now += 3_000
        assertEquals("re-send keeps the settled value", 19.29, repair(1.37, belt = 19.30), 1e-9)
    }

    @Test
    fun `settle leaves a correct target alone`() {
        latch()
        beltSteady(4.82, 3_000)
        assertNull(beltSteady(4.82, 3_000))
    }

    @Test
    fun `stopped belt never settles`() {
        latch()
        now += 100
        repair(1.37, belt = 4.82)
        assertNull(beltSteady(0.0, 5_000))
    }

    @Test
    fun `small values that fit in one byte are not mistaken for a dropped byte`() {
        assertEquals(0.5, repair(0.5, belt = null), 1e-9)
        assertEquals(0.8, repair(0.8, belt = 0.5), 1e-9)
        assertFalse(echo.dropsHighByte)
    }

    @Test
    fun `spec compliant treadmill passes through`() {
        echo.commanded(4.80, now)
        assertEquals(4.80, repair(4.82, belt = 3.0), 1e-9)
        assertFalse(echo.dropsHighByte)
        assertEquals(2.0, repair(2.0, belt = 4.8), 1e-9)
        assertNull(beltSteady(4.8, 3_000))
    }

    @Test
    fun `reset forgets the quirk`() {
        latch()
        echo.reset()
        assertEquals(2.26, repair(2.26, belt = 4.82), 1e-9)
    }
}
