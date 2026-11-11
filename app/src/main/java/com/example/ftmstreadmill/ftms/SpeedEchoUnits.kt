package com.example.ftmstreadmill.ftms

import com.example.ftmstreadmill.workout.model.SpeedUnit
import kotlin.math.abs

/**
 * Works out which unit a treadmill uses for Fitness Machine Status "Target Speed Changed" (0x05).
 *
 * The FTMS spec says km/h, but some treadmills whose console is set to miles echo the value in mph:
 * a 4.83 km/h (3.0 mph) command comes back as 3.00. Taken at face value that looks like the user
 * slowed the belt to 1.9 mph. The first echo after a command tells the two apart; once a treadmill
 * is known to echo mph, later values (e.g. console button presses) are converted too.
 *
 * One instance per connection; call [reset] when the connection changes.
 */
class SpeedEchoUnits {
    private var pendingCommandKmh: Double? = null

    /** True once this treadmill has echoed a command back in mph. */
    var echoesMph: Boolean = false
        private set

    /** Records a speed we just commanded, so its echo can be recognised. */
    fun commanded(kmh: Double) {
        pendingCommandKmh = kmh
    }

    /** Converts a raw 0x05 value (nominally 0.01 km/h units, already divided by 100) to km/h. */
    fun toKmh(raw: Double): Double {
        pendingCommandKmh?.let { command ->
            when {
                near(raw, command) -> {
                    pendingCommandKmh = null
                    echoesMph = false
                    return raw
                }
                near(raw, command / SpeedUnit.KM_PER_MILE) -> {
                    pendingCommandKmh = null
                    echoesMph = true
                    return command
                }
            }
        }
        return if (echoesMph) raw * SpeedUnit.KM_PER_MILE else raw
    }

    fun reset() {
        pendingCommandKmh = null
        echoesMph = false
    }

    private fun near(a: Double, b: Double) = abs(a - b) <= TOLERANCE

    private companion object {
        /** Consoles in mph round the echo to 0.1 mph, so allow just over half a step. */
        const val TOLERANCE = 0.06
    }
}
