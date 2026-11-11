package com.example.ftmstreadmill.ftms

import com.example.ftmstreadmill.workout.model.SpeedUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SpeedEchoUnitsTest {
    private val echo = SpeedEchoUnits()

    @Test
    fun `km per hour echo passes through`() {
        echo.commanded(4.83)
        assertEquals(4.83, echo.toKmh(4.83), 1e-9)
        assertFalse(echo.echoesMph)
        // A later console change is taken as km/h too.
        assertEquals(6.0, echo.toKmh(6.0), 1e-9)
    }

    @Test
    fun `mph echo of a command maps back to the command`() {
        echo.commanded(3.0 * SpeedUnit.KM_PER_MILE)
        assertEquals(3.0 * SpeedUnit.KM_PER_MILE, echo.toKmh(3.0), 1e-9)
        assertTrue(echo.echoesMph)
    }

    @Test
    fun `mph echo rounded to a tenth is still recognised`() {
        echo.commanded(4.8) // 2.98 mph
        assertEquals(4.8, echo.toKmh(3.0), 1e-9)
        assertTrue(echo.echoesMph)
    }

    @Test
    fun `once mph is detected later console changes are converted`() {
        echo.commanded(3.0 * SpeedUnit.KM_PER_MILE)
        echo.toKmh(3.0)
        assertEquals(3.5 * SpeedUnit.KM_PER_MILE, echo.toKmh(3.5), 1e-9)
    }

    @Test
    fun `unrelated values without a command are untouched`() {
        assertEquals(2.25, echo.toKmh(2.25), 1e-9)
        assertFalse(echo.echoesMph)
    }

    @Test
    fun `reset forgets the detected unit`() {
        echo.commanded(3.0 * SpeedUnit.KM_PER_MILE)
        echo.toKmh(3.0)
        echo.reset()
        assertEquals(3.0, echo.toKmh(3.0), 1e-9)
    }
}
