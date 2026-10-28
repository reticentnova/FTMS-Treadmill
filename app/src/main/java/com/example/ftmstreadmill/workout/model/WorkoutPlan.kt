package com.example.ftmstreadmill.workout.model

import kotlinx.serialization.Serializable

@Serializable
enum class SegmentKind { WARMUP, INTERVAL, COOLDOWN }

/** Position inside a repeat block, e.g. round 2 of 8. */
@Serializable
data class RepeatPosition(val round: Int, val of: Int)

/** One segment of a workout with repeats expanded and speeds converted to km/h. */
@Serializable
data class PlannedSegment(
    val kind: SegmentKind,
    val name: String?,
    val durationSec: Int,
    val speedKmh: Double,
    val inclinePercent: Double,
    /** 1-based number among the intervals (warmup and cooldown have none). */
    val intervalNumber: Int? = null,
    val repeat: RepeatPosition? = null,
)

/** The flat sequence the engine runs, the progress bar draws and the profile chart plots. */
data class WorkoutPlan(val segments: List<PlannedSegment>) {
    val totalSec: Int = segments.sumOf { it.durationSec }

    val intervalCount: Int = segments.count { it.kind == SegmentKind.INTERVAL }

    /** Start time of each segment in seconds from the beginning. */
    val startOffsets: List<Int> = segments.runningFold(0) { acc, s -> acc + s.durationSec }.dropLast(1)

    /** Distance if every segment is run at its planned speed. */
    val estimatedDistanceMeters: Double = segments.sumOf { it.speedKmh / 3.6 * it.durationSec }

    val maxSpeedKmh: Double = segments.maxOfOrNull { it.speedKmh } ?: 0.0
    val maxInclinePercent: Double = segments.maxOfOrNull { it.inclinePercent } ?: 0.0

    companion object {
        fun of(workout: Workout): WorkoutPlan {
            val out = mutableListOf<PlannedSegment>()
            val units = workout.units
            fun add(s: Segment, kind: SegmentKind, number: Int?, repeat: RepeatPosition?) {
                out += PlannedSegment(kind, s.name, s.durationSec, units.toKmh(s.speed), s.incline, number, repeat)
            }
            workout.warmup?.let { add(it, SegmentKind.WARMUP, null, null) }
            var number = 0
            for (step in workout.intervals) {
                when (step) {
                    is Segment -> add(step, SegmentKind.INTERVAL, ++number, null)
                    is Repeat -> for (round in 1..step.times) {
                        for (s in step.intervals) add(s, SegmentKind.INTERVAL, ++number, RepeatPosition(round, step.times))
                    }
                }
            }
            workout.cooldown?.let { add(it, SegmentKind.COOLDOWN, null, null) }
            return WorkoutPlan(out)
        }
    }
}
