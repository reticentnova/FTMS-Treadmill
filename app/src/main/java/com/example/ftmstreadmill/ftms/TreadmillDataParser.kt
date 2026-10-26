package com.example.ftmstreadmill.ftms

import com.example.ftmstreadmill.ftms.model.TreadmillData

/**
 * Parses the Treadmill Data characteristic (0x2ACD). The 16-bit flags field says which optional
 * fields follow, in the fixed order defined by the FTMS specification.
 */
object TreadmillDataParser {
    private const val MORE_DATA = 1 shl 0
    private const val AVERAGE_SPEED = 1 shl 1
    private const val TOTAL_DISTANCE = 1 shl 2
    private const val INCLINATION = 1 shl 3
    private const val ELEVATION_GAIN = 1 shl 4
    private const val INSTANTANEOUS_PACE = 1 shl 5
    private const val AVERAGE_PACE = 1 shl 6
    private const val EXPENDED_ENERGY = 1 shl 7
    private const val HEART_RATE = 1 shl 8
    private const val METABOLIC_EQUIVALENT = 1 shl 9
    private const val ELAPSED_TIME = 1 shl 10
    private const val REMAINING_TIME = 1 shl 11
    private const val FORCE_AND_POWER = 1 shl 12

    // "Data not available" sentinels from the spec.
    private const val SINT16_NOT_AVAILABLE = 0x7FFF
    private const val UINT16_NOT_AVAILABLE = 0xFFFF
    private const val UINT8_NOT_AVAILABLE = 0xFF

    fun parse(bytes: ByteArray): TreadmillData {
        val r = ByteReader(bytes)
        val flags = r.uint16()
        var data = TreadmillData()

        // Inverted logic: instantaneous speed is present when "More Data" is NOT set.
        if (flags and MORE_DATA == 0) data = data.copy(speedKmh = r.uint16() / 100.0)
        if (flags has AVERAGE_SPEED) data = data.copy(averageSpeedKmh = r.uint16() / 100.0)
        if (flags has TOTAL_DISTANCE) data = data.copy(totalDistanceMeters = r.uint24())
        if (flags has INCLINATION) {
            val incline = r.sint16()
            val ramp = r.sint16()
            data = data.copy(
                inclinePercent = incline.takeUnless { it == SINT16_NOT_AVAILABLE }?.div(10.0),
                rampAngleDegrees = ramp.takeUnless { it == SINT16_NOT_AVAILABLE }?.div(10.0),
            )
        }
        if (flags has ELEVATION_GAIN) {
            data = data.copy(
                positiveElevationGainMeters = r.uint16() / 10.0,
                negativeElevationGainMeters = r.uint16() / 10.0,
            )
        }
        if (flags has INSTANTANEOUS_PACE) data = data.copy(instantaneousPaceKmPerMin = r.uint8() / 10.0)
        if (flags has AVERAGE_PACE) data = data.copy(averagePaceKmPerMin = r.uint8() / 10.0)
        if (flags has EXPENDED_ENERGY) {
            val total = r.uint16()
            val perHour = r.uint16()
            val perMinute = r.uint8()
            data = data.copy(
                totalEnergyKcal = total.takeUnless { it == UINT16_NOT_AVAILABLE },
                energyPerHourKcal = perHour.takeUnless { it == UINT16_NOT_AVAILABLE },
                energyPerMinuteKcal = perMinute.takeUnless { it == UINT8_NOT_AVAILABLE },
            )
        }
        if (flags has HEART_RATE) data = data.copy(heartRateBpm = r.uint8().takeIf { it > 0 })
        if (flags has METABOLIC_EQUIVALENT) data = data.copy(metabolicEquivalent = r.uint8() / 10.0)
        if (flags has ELAPSED_TIME) data = data.copy(elapsedTimeSeconds = r.uint16())
        if (flags has REMAINING_TIME) data = data.copy(remainingTimeSeconds = r.uint16())
        if (flags has FORCE_AND_POWER) {
            data = data.copy(forceOnBeltNewtons = r.sint16(), powerOutputWatts = r.sint16())
        }
        return data
    }

    private infix fun Int.has(flag: Int) = this and flag != 0
}
