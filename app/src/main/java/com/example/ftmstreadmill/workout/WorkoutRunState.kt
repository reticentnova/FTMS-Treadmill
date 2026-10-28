package com.example.ftmstreadmill.workout

import com.example.ftmstreadmill.workout.model.PlannedSegment
import com.example.ftmstreadmill.workout.model.Session
import com.example.ftmstreadmill.workout.model.Workout
import com.example.ftmstreadmill.workout.model.WorkoutPlan
import kotlin.math.ceil

/** Monotonic time for measuring durations, wall time for timestamps. */
interface Clock {
    fun elapsedMs(): Long
    fun wallMs(): Long
}

object SystemClock : Clock {
    override fun elapsedMs(): Long = android.os.SystemClock.elapsedRealtime()
    override fun wallMs(): Long = System.currentTimeMillis()
}

enum class RunPhase { COUNTDOWN, RUNNING, PAUSED }

enum class PauseReason {
    /** Pause pressed in the app or notification. */
    USER,

    /** The treadmill reported a pause, or its belt stopped. */
    TREADMILL,

    /** The treadmill isn't connected. */
    DISCONNECTED,
}

sealed interface WorkoutRunState {
    data object Idle : WorkoutRunState

    data class Active(
        val workoutId: Long?,
        val workout: Workout,
        val plan: WorkoutPlan,
        val phase: RunPhase,
        /** Seconds left in the start countdown while [phase] is [RunPhase.COUNTDOWN]. */
        val countdown: Int = 0,
        val pauseReason: PauseReason? = null,
        val index: Int = 0,
        val segmentElapsedMs: Long = 0,
        /** Active (unpaused) time since the start. */
        val elapsedMs: Long = 0,
        /** Temporary changes for the current segment only; null means "as planned". */
        val speedOverrideKmh: Double? = null,
        val inclineOverridePercent: Double? = null,
        val distanceMeters: Double = 0.0,
        val kcal: Int? = null,
    ) : WorkoutRunState {
        val current: PlannedSegment get() = plan.segments[index]
        val next: PlannedSegment? get() = plan.segments.getOrNull(index + 1)
        val targetSpeedKmh: Double get() = speedOverrideKmh ?: current.speedKmh
        val targetInclinePercent: Double get() = inclineOverridePercent ?: current.inclinePercent
        val isAdjusted: Boolean get() = speedOverrideKmh != null || inclineOverridePercent != null

        val segmentRemainingSec: Int
            get() = ceil((current.durationSec * 1000L - segmentElapsedMs).coerceAtLeast(0) / 1000.0).toInt()

        val segmentProgress: Float
            get() = (segmentElapsedMs / (current.durationSec * 1000f)).coerceIn(0f, 1f)

        /** Planned time remaining, which ignores pauses and skipped time. */
        val totalRemainingSec: Int
            get() = (plan.totalSec - plan.startOffsets[index]) - (current.durationSec - segmentRemainingSec)

        /** Position along the plan, 0..1. */
        val planProgress: Float
            get() = ((plan.startOffsets[index] * 1000L + segmentElapsedMs) / (plan.totalSec * 1000f)).coerceIn(0f, 1f)
    }

    /** Shown as the summary until dismissed. [session] has its stored id. */
    data class Finished(val session: Session) : WorkoutRunState
}

sealed interface WorkoutEvent {
    /** 3, 2, 1 before the start and before each segment change. */
    data class Countdown(val secondsLeft: Int) : WorkoutEvent

    data class SegmentStarted(
        val index: Int,
        val segment: PlannedSegment,
        val next: PlannedSegment?,
        val isFirst: Boolean,
    ) : WorkoutEvent

    data object Halfway : WorkoutEvent

    data class Paused(val reason: PauseReason) : WorkoutEvent

    data object Resumed : WorkoutEvent

    data class Finished(val session: Session) : WorkoutEvent
}
