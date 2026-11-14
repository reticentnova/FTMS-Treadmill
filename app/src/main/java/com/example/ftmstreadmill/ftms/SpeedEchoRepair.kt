package com.example.ftmstreadmill.ftms

import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Cleans up Fitness Machine Status "Target Speed Changed" (0x05) values.
 *
 * The value is a UINT16 in 0.01 km/h, low byte first. Some treadmills send only the low byte
 * correctly and zero the high byte: a 3.0 mph target (4.82 km/h, 0x01E2) arrives as `05 E2 00`,
 * which reads as 2.26 km/h. Taken at face value that looks like the user slowed the belt.
 *
 * A treadmill is marked as dropping the high byte the first time an echo of one of our commands
 * only matches once multiples of 2.56 km/h are added back. From then on, short values are
 * rebuilt by adding back the multiple that lands nearest the last command (when it is an echo
 * of that) or the belt's actual speed (for changes made on the console).
 *
 * Treadmills also round targets to their own step (e.g. 0.1 mph), so an echo within
 * [ACK_TOLERANCE] of the last command is reported as exactly that command.
 *
 * One instance per connection; call [reset] when the connection changes. Not thread safe.
 */
class SpeedEchoRepair {
    private var commandHundredths: Int? = null

    /** True once this treadmill has been seen zeroing the high byte. */
    var dropsHighByte: Boolean = false
        private set

    /** Records a speed we just commanded, so its echo can be recognised. */
    fun commanded(kmh: Double) {
        commandHundredths = (kmh * 100).roundToInt()
    }

    /**
     * Returns the target speed in km/h for an echoed [rawKmh] (as parsed), given the belt's
     * current [beltKmh] (null if unknown) and the treadmill's [maxKmh].
     */
    fun repair(rawKmh: Double, beltKmh: Double?, maxKmh: Double): Double {
        val value = (rawKmh * 100).roundToInt()
        val command = commandHundredths
        if (!dropsHighByte && command != null && value < BYTE && command >= BYTE &&
            candidates(value, maxKmh).any { it >= BYTE && near(it, command) }
        ) {
            dropsHighByte = true
        }
        val options = if (dropsHighByte && value < BYTE) candidates(value, maxKmh) else listOf(value)
        if (command != null && options.any { near(it, command) }) return command / 100.0

        val reference = beltKmh?.takeIf { it > 0.0 }?.let { (it * 100).roundToInt() } ?: command ?: value
        return options.minBy { abs(it - reference) } / 100.0
    }

    fun reset() {
        commandHundredths = null
        dropsHighByte = false
    }

    /** [value] plus every multiple of 2.56 km/h up to [maxKmh]. */
    private fun candidates(value: Int, maxKmh: Double): List<Int> {
        val max = maxOf((maxKmh * 100).roundToInt(), value)
        return generateSequence(value) { it + BYTE }.takeWhile { it <= max }.toList()
    }

    private fun near(a: Int, b: Int) = abs(a - b) <= ACK_TOLERANCE

    private companion object {
        /** One byte's worth of 0.01 km/h steps. */
        const val BYTE = 256

        /** 0.1 km/h: covers a treadmill rounding our command to its 0.1 mph step (≤ 0.08 km/h). */
        const val ACK_TOLERANCE = 10
    }
}
