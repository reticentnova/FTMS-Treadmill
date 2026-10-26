package com.example.ftmstreadmill.ui

import com.example.ftmstreadmill.ftms.TreadmillDataParser
import com.example.ftmstreadmill.ui.control.Units
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import java.util.Locale

class UnitsTest {
    private val originalLocale = Locale.getDefault()

    @Before
    fun setUp() = Locale.setDefault(Locale.US)

    @After
    fun tearDown() = Locale.setDefault(originalLocale)

    @Test
    fun `reported speed round-trips to the commanded mph value`() {
        // Command 5.0 mph -> 8.05 km/h on the wire; the treadmill reports the same raw value back.
        val commandedKmh = Units.speedFromDisplay(5.0, imperial = true)
        val raw = Math.round(commandedKmh * 100).toInt()
        val reported = TreadmillDataParser.parse(byteArrayOf(0, 0, raw.toByte(), (raw shr 8).toByte()))

        assertEquals("5.0", Units.formatSpeed(reported.speedKmh, imperial = true))
        assertEquals("8.1", Units.formatSpeed(reported.speedKmh, imperial = false))
    }

    @Test
    fun `pace and distance follow the unit setting`() {
        assertEquals("12:00", Units.formatPace(Units.speedFromDisplay(5.0, imperial = true), imperial = true))
        assertEquals("6:00", Units.formatPace(10.0, imperial = false))
        assertEquals("1.00", Units.formatDistance(1609, imperial = true))
        assertEquals("1.61", Units.formatDistance(1609, imperial = false))
    }
}
