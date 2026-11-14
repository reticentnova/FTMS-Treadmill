package com.example.ftmstreadmill.ftms

import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Cleans up Fitness Machine Status "Target Speed Changed" (0x05) values.
 *
 * The value is a UINT16 in 0.01 km/h, low byte first. Some treadmills send only the low byte
 * correctly and zero the high byte: a 3.0 mph target (4.82 km/h, 0x01E2) arrives as `05 E2 00`,
 * which reads as 2.26 km/h. The true target is then one of `raw + n × 2.56 km/h`.
 *
 * A treadmill is marked as dropping the high byte the first time an echo of one of our commands
 * only matches once multiples of 2.56 km/h are added back, or a run of small steps with the belt
 * moving wraps from just under 2.56 km/h to just above 0. From then on each short value is
 * resolved, in order:
 * 1. to a command sent within [PENDING_MS] and not yet echoed, if one candidate is within
 *    [ACK_TOLERANCE] of it (treadmills round targets to their own step, e.g. 0.1 mph). Only the
 *    first match counts, so a later scroll-wheel run passing that speed isn't pulled back to it;
 * 2. while the belt is stopped, to the raw value (start-up targets are low);
 * 3. otherwise to the candidate nearest the previous target. Console scroll wheels step 0.1 mph
 *    (0.16 km/h) per echo, so this follows them, including across byte wraps. A result within
 *    [ACK_TOLERANCE] of the last command is reported as exactly that command.
 *
 * Console presets jump further than half of 2.56 km/h, so step 3 can pick the wrong candidate.
 * [settle] fixes that once the belt holds a steady speed matching another candidate.
 *
 * One instance per connection; call [reset] when the connection changes. Not thread safe.
 */
class SpeedEchoRepair {
    private var commandHundredths: Int? = null
    private var pendingCommand: Int? = null
    private var commandedAtMs = Long.MIN_VALUE
    private var targetHundredths: Int? = null
    private var lastRaw: Int? = null
    private var lastRawChangedAtMs = Long.MIN_VALUE
    private var beltAnchor: Int? = null
    private var beltSteadySinceMs = Long.MIN_VALUE
    private var maxHundredths = DEFAULT_MAX

    /** True once this treadmill has been seen zeroing the high byte. */
    var dropsHighByte: Boolean = false
        private set

    /** Records a speed we just commanded, so its echo can be recognised. */
    fun commanded(kmh: Double, nowMs: Long) {
        commandHundredths = hundredths(kmh)
        pendingCommand = commandHundredths
        commandedAtMs = nowMs
    }

    /**
     * Returns the target speed in km/h for an echoed [rawKmh] (as parsed), given the belt's
     * current [beltKmh] (null if unknown), the treadmill's [maxKmh] and the time [nowMs].
     */
    fun repair(rawKmh: Double, beltKmh: Double?, maxKmh: Double, nowMs: Long): Double {
        val value = hundredths(rawKmh)
        maxHundredths = hundredths(maxKmh)
        if (value != lastRaw) {
            lastRaw = value
            lastRawChangedAtMs = nowMs
        }
        val pending = pendingCommand?.takeIf { nowMs - commandedAtMs <= PENDING_MS }
        val belt = beltKmh?.let(::hundredths)?.takeIf { it > 0 }
        val previous = targetHundredths
        if (!dropsHighByte && value < BYTE) {
            // Our command came back short by whole bytes...
            val shortEcho = pending != null && pending >= BYTE &&
                candidates(value).any { it >= BYTE && near(it, pending, ACK_TOLERANCE) }
            // ...or, with the belt moving, a run of 0.1 mph steps wrapped from 2.4x to 0.0x km/h.
            val wrapped = belt != null && previous != null && previous < BYTE &&
                near(value + BYTE, previous, STEP_WINDOW)
            if (shortEcho || wrapped) dropsHighByte = true
        }
        val options = if (dropsHighByte && value < BYTE) candidates(value) else listOf(value)
        val command = commandHundredths
        val target = when {
            pending != null && options.any { near(it, pending, ACK_TOLERANCE) } -> {
                pendingCommand = null
                pending
            }
            options.size == 1 -> value
            belt == null -> value
            else -> options.minBy { abs(it - (targetHundredths ?: belt)) }
        }.let { if (command != null && near(it, command, ACK_TOLERANCE)) command else it }
        targetHundredths = target
        return target / 100.0
    }

    /**
     * Called with each belt speed reading. Returns a corrected target in km/h when the belt has
     * held steady, since the last echo changed, at a speed matching a different candidate of that
     * echo than the one chosen; otherwise null.
     */
    fun settle(beltKmh: Double?, nowMs: Long): Double? {
        val belt = beltKmh?.let(::hundredths) ?: return null
        val anchor = beltAnchor
        if (anchor == null || !near(belt, anchor, STEADY_TOLERANCE)) {
            beltAnchor = belt
            beltSteadySinceMs = nowMs
            return null
        }
        val raw = lastRaw ?: return null
        val target = targetHundredths ?: return null
        if (!dropsHighByte || raw >= BYTE || belt <= 0) return null
        // The belt must have reached its speed after the echo, not merely be sitting at the old one.
        if (beltSteadySinceMs < lastRawChangedAtMs || nowMs - beltSteadySinceMs < SETTLE_MS) return null
        if (near(target, belt, SETTLE_TOLERANCE)) return null
        val match = candidates(raw).firstOrNull { near(it, belt, SETTLE_TOLERANCE) } ?: return null
        targetHundredths = match
        return match / 100.0
    }

    fun reset() {
        commandHundredths = null
        pendingCommand = null
        commandedAtMs = Long.MIN_VALUE
        targetHundredths = null
        lastRaw = null
        lastRawChangedAtMs = Long.MIN_VALUE
        beltAnchor = null
        beltSteadySinceMs = Long.MIN_VALUE
        dropsHighByte = false
    }

    /** [value] plus every multiple of 2.56 km/h up to the treadmill's maximum. */
    private fun candidates(value: Int): List<Int> {
        val max = maxOf(maxHundredths, value)
        return generateSequence(value) { it + BYTE }.takeWhile { it <= max }.toList()
    }

    private fun hundredths(kmh: Double) = (kmh * 100).roundToInt()

    private fun near(a: Int, b: Int, tolerance: Int) = abs(a - b) <= tolerance

    private companion object {
        /** One byte's worth of 0.01 km/h steps. */
        const val BYTE = 256

        /** 0.1 km/h: covers a treadmill rounding our command to its 0.1 mph step (≤ 0.08 km/h). */
        const val ACK_TOLERANCE = 10

        /** Belt readings within 0.05 km/h of each other count as steady. */
        const val STEADY_TOLERANCE = 5

        /** A steady belt within 0.15 km/h of a candidate is taken to be running at that target. */
        const val SETTLE_TOLERANCE = 15

        const val SETTLE_MS = 1_500L

        /**
         * How long after a command an echo is taken as its confirmation. Real confirmations arrive
         * within ~100 ms; the treadmill's own start-up ramp passes our speed seconds later and is
         * followed by continuity instead.
         */
        const val PENDING_MS = 1_500L

        /** A wrap is two scroll-wheel steps (0.16 km/h each) or less across a byte boundary. */
        const val STEP_WINDOW = 35

        const val DEFAULT_MAX = 2_000
    }
}
