package com.example.ftmstreadmill.ftms.model

/** Parsed Fitness Machine Feature characteristic (two 32-bit bitfields). */
data class FtmsFeatures(
    val machineFeatures: Long,
    val targetSettingFeatures: Long,
) {
    val speedTargetSupported: Boolean get() = targetSettingFeatures and 0x01L != 0L
    val inclineTargetSupported: Boolean get() = targetSettingFeatures and 0x02L != 0L

    companion object {
        /** Assumed when the feature characteristic is missing or unreadable. */
        val UNKNOWN = FtmsFeatures(machineFeatures = 0, targetSettingFeatures = 0x03)
    }
}

/** A min/max/step range as reported by the Supported Speed or Inclination Range characteristic. */
data class SupportedRange(
    val min: Double,
    val max: Double,
    val step: Double,
) {
    /** Clamps [value] into the range and snaps it to the nearest step above [min]. */
    fun clampAndSnap(value: Double): Double {
        val clamped = value.coerceIn(min, max)
        if (step <= 0.0) return clamped
        val steps = Math.round((clamped - min) / step)
        return (min + steps * step).coerceIn(min, max)
    }

    companion object {
        val DEFAULT_SPEED_KMH = SupportedRange(min = 0.0, max = 20.0, step = 0.1)
        val DEFAULT_INCLINE_PERCENT = SupportedRange(min = 0.0, max = 15.0, step = 0.5)
    }
}
