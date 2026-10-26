package com.example.ftmstreadmill.ftms

import com.example.ftmstreadmill.ftms.model.TreadmillData
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TreadmillDataParserTest {

    @Test
    fun `speed only when no flags set`() {
        // flags=0x0000, speed=0x04B0 (1200 -> 12.00 km/h)
        val data = TreadmillDataParser.parse(bytes(0x00, 0x00, 0xB0, 0x04))
        assertEquals(TreadmillData(speedKmh = 12.0), data)
    }

    @Test
    fun `more data flag means speed is absent`() {
        // flags=0x0005 (more data + total distance), distance=0x0186A0 (100000 m)
        val data = TreadmillDataParser.parse(bytes(0x05, 0x00, 0xA0, 0x86, 0x01))
        assertNull(data.speedKmh)
        assertEquals(100_000, data.totalDistanceMeters)
    }

    @Test
    fun `parses every field in spec order`() {
        val flags = 0x1FFE // everything except More Data
        val payload = bytes(
            flags and 0xFF, flags shr 8,
            0xF4, 0x01, // speed 5.00
            0xE8, 0x03, // avg speed 10.00
            0x10, 0x27, 0x00, // distance 10000 m
            0xF6, 0xFF, // incline -10 -> -1.0 %
            0xFF, 0x7F, // ramp angle not available
            0x64, 0x00, // +elevation 10.0 m
            0x0A, 0x00, // -elevation 1.0 m
            0x05, // pace 0.5 km/min
            0x06, // avg pace 0.6 km/min
            0x2C, 0x01, // total energy 300 kcal
            0xFF, 0xFF, // energy/hour not available
            0xFF, // energy/min not available
            0x8C, // HR 140
            0x50, // MET 8.0
            0x3C, 0x00, // elapsed 60 s
            0x10, 0x0E, // remaining 3600 s
            0x14, 0x00, // force 20 N
            0xC8, 0x00, // power 200 W
        )

        val data = TreadmillDataParser.parse(payload)

        assertEquals(
            TreadmillData(
                speedKmh = 5.0,
                averageSpeedKmh = 10.0,
                totalDistanceMeters = 10_000,
                inclinePercent = -1.0,
                rampAngleDegrees = null,
                positiveElevationGainMeters = 10.0,
                negativeElevationGainMeters = 1.0,
                instantaneousPaceKmPerMin = 0.5,
                averagePaceKmPerMin = 0.6,
                totalEnergyKcal = 300,
                energyPerHourKcal = null,
                energyPerMinuteKcal = null,
                heartRateBpm = 140,
                metabolicEquivalent = 8.0,
                elapsedTimeSeconds = 60,
                remainingTimeSeconds = 3600,
                forceOnBeltNewtons = 20,
                powerOutputWatts = 200,
            ),
            data,
        )
    }

    @Test
    fun `split packets merge into one snapshot`() {
        val first = TreadmillDataParser.parse(bytes(0x08, 0x00, 0x2C, 0x01, 0x32, 0x00, 0x14, 0x00)) // speed 3.00, incline 5.0 %
        val second = TreadmillDataParser.parse(bytes(0x01, 0x04, 0x78, 0x00)) // more data + elapsed 120 s

        val merged = TreadmillData().mergedWith(first).mergedWith(second)

        assertEquals(3.0, merged.speedKmh!!, 0.0001)
        assertEquals(5.0, merged.inclinePercent!!, 0.0001)
        assertEquals(120, merged.elapsedTimeSeconds)
    }

    @Test(expected = FtmsParseException::class)
    fun `truncated payload throws`() {
        TreadmillDataParser.parse(bytes(0x04, 0x00, 0x00, 0x00, 0x01)) // distance needs 3 bytes after speed
    }
}

internal fun bytes(vararg values: Int) = ByteArray(values.size) { values[it].toByte() }
