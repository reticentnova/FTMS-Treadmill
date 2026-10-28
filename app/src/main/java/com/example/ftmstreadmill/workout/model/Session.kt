package com.example.ftmstreadmill.workout.model

import kotlinx.serialization.Serializable

/** Why a run ended. */
@Serializable
enum class EndReason { COMPLETED, ENDED_IN_APP, STOPPED_ON_TREADMILL, SAFETY_KEY }

/** What actually happened during one planned segment. */
@Serializable
data class SegmentResult(
    val planned: PlannedSegment,
    val startedAt: Long,
    val endedAt: Long,
    val activeSec: Int,
    val avgSpeedKmh: Double?,
    val avgInclinePercent: Double?,
    val distanceMeters: Double,
    /** The speed or incline was changed (in the app or on the treadmill) during this segment. */
    val adjusted: Boolean,
    /** Skipped before its planned duration had elapsed. */
    val skipped: Boolean = false,
)

/** A speed sample taken during the run, used for Health Connect and the history chart. */
@Serializable
data class SpeedSample(val time: Long, val speedKmh: Double, val inclinePercent: Double?)

/** A completed run as stored in history. */
data class Session(
    val id: Long,
    val workoutId: Long?,
    val workoutName: String,
    val startedAt: Long,
    val endedAt: Long,
    val activeSec: Int,
    val distanceMeters: Int?,
    val kcal: Int?,
    val endReason: EndReason,
    val workout: Workout?,
    val segments: List<SegmentResult>,
    val samples: List<SpeedSample>,
    val healthConnectExported: Boolean,
) {
    val completed: Boolean get() = endReason == EndReason.COMPLETED
}
