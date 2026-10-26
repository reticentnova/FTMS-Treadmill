package com.example.ftmstreadmill.ftms.model

/**
 * Snapshot of the Treadmill Data characteristic. Every field is nullable because a machine only
 * sends the fields it supports, and may split one logical record across several notifications
 * ("More Data" flag). Use [mergedWith] to accumulate partial packets.
 */
data class TreadmillData(
    val speedKmh: Double? = null,
    val averageSpeedKmh: Double? = null,
    val totalDistanceMeters: Int? = null,
    val inclinePercent: Double? = null,
    val rampAngleDegrees: Double? = null,
    val positiveElevationGainMeters: Double? = null,
    val negativeElevationGainMeters: Double? = null,
    val instantaneousPaceKmPerMin: Double? = null,
    val averagePaceKmPerMin: Double? = null,
    val totalEnergyKcal: Int? = null,
    val energyPerHourKcal: Int? = null,
    val energyPerMinuteKcal: Int? = null,
    val heartRateBpm: Int? = null,
    val metabolicEquivalent: Double? = null,
    val elapsedTimeSeconds: Int? = null,
    val remainingTimeSeconds: Int? = null,
    val forceOnBeltNewtons: Int? = null,
    val powerOutputWatts: Int? = null,
) {
    /** Returns a copy where every field present in [newer] overrides the value in this snapshot. */
    fun mergedWith(newer: TreadmillData) = TreadmillData(
        speedKmh = newer.speedKmh ?: speedKmh,
        averageSpeedKmh = newer.averageSpeedKmh ?: averageSpeedKmh,
        totalDistanceMeters = newer.totalDistanceMeters ?: totalDistanceMeters,
        inclinePercent = newer.inclinePercent ?: inclinePercent,
        rampAngleDegrees = newer.rampAngleDegrees ?: rampAngleDegrees,
        positiveElevationGainMeters = newer.positiveElevationGainMeters ?: positiveElevationGainMeters,
        negativeElevationGainMeters = newer.negativeElevationGainMeters ?: negativeElevationGainMeters,
        instantaneousPaceKmPerMin = newer.instantaneousPaceKmPerMin ?: instantaneousPaceKmPerMin,
        averagePaceKmPerMin = newer.averagePaceKmPerMin ?: averagePaceKmPerMin,
        totalEnergyKcal = newer.totalEnergyKcal ?: totalEnergyKcal,
        energyPerHourKcal = newer.energyPerHourKcal ?: energyPerHourKcal,
        energyPerMinuteKcal = newer.energyPerMinuteKcal ?: energyPerMinuteKcal,
        heartRateBpm = newer.heartRateBpm ?: heartRateBpm,
        metabolicEquivalent = newer.metabolicEquivalent ?: metabolicEquivalent,
        elapsedTimeSeconds = newer.elapsedTimeSeconds ?: elapsedTimeSeconds,
        remainingTimeSeconds = newer.remainingTimeSeconds ?: remainingTimeSeconds,
        forceOnBeltNewtons = newer.forceOnBeltNewtons ?: forceOnBeltNewtons,
        powerOutputWatts = newer.powerOutputWatts ?: powerOutputWatts,
    )
}
