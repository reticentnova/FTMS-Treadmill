package com.example.ftmstreadmill.ui.editor

import com.example.ftmstreadmill.data.SegmentDefaults
import com.example.ftmstreadmill.workout.model.Repeat
import com.example.ftmstreadmill.workout.model.Segment
import com.example.ftmstreadmill.workout.model.SpeedUnit
import com.example.ftmstreadmill.workout.model.Workout
import com.example.ftmstreadmill.workout.model.WorkoutStep
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlin.math.roundToInt

/**
 * The editor's working copy. Speeds are in the user's display [units] so steppers move in clean
 * 0.1 steps; every item carries a stable [DraftSegment.key] for list animation and drag-to-reorder.
 */
@Serializable
data class DraftSegment(
    val key: Long,
    val name: String = "",
    val durationSec: Int,
    val speed: Double,
    val incline: Double,
)

@Serializable
sealed interface DraftStep {
    val key: Long
}

@Serializable
@SerialName("interval")
data class DraftInterval(val segment: DraftSegment) : DraftStep {
    override val key: Long get() = segment.key
}

@Serializable
@SerialName("repeat")
data class DraftRepeat(override val key: Long, val times: Int, val intervals: List<DraftSegment>) : DraftStep

@Serializable
data class EditorDraft(
    val name: String = "",
    val description: String = "",
    val units: SpeedUnit = SpeedUnit.MPH,
    val warmupEnabled: Boolean = true,
    val warmup: DraftSegment,
    val cooldownEnabled: Boolean = true,
    val cooldown: DraftSegment,
    val steps: List<DraftStep> = emptyList(),
) {
    fun toWorkout(): Workout = Workout(
        name = name.trim(),
        description = description.trim().ifEmpty { null },
        units = units,
        warmup = warmup.takeIf { warmupEnabled }?.toSegment(),
        intervals = steps.map { step ->
            when (step) {
                is DraftInterval -> step.segment.toSegment()
                is DraftRepeat -> Repeat(step.times, step.intervals.map { it.toSegment() })
            }
        },
        cooldown = cooldown.takeIf { cooldownEnabled }?.toSegment(),
    )

    val maxKey: Long
        get() = (listOf(warmup.key, cooldown.key) + steps.flatMap { step ->
            when (step) {
                is DraftInterval -> listOf(step.key)
                is DraftRepeat -> listOf(step.key) + step.intervals.map { it.key }
            }
        }).max()

    companion object {
        fun from(workout: Workout, units: SpeedUnit, defaults: Pair<SegmentDefaults, SegmentDefaults>, nextKey: () -> Long): EditorDraft {
            fun Segment.draft() = DraftSegment(
                key = nextKey(),
                name = name.orEmpty(),
                durationSec = durationSec,
                speed = round1(units.fromKmh(workout.units.toKmh(speed))),
                incline = incline,
            )
            return EditorDraft(
                name = workout.name,
                description = workout.description.orEmpty(),
                units = units,
                warmupEnabled = workout.warmup != null,
                warmup = workout.warmup?.draft() ?: defaults.first.draft(units, nextKey()),
                cooldownEnabled = workout.cooldown != null,
                cooldown = workout.cooldown?.draft() ?: defaults.second.draft(units, nextKey()),
                steps = workout.intervals.map { step: WorkoutStep ->
                    when (step) {
                        is Segment -> DraftInterval(step.draft())
                        is Repeat -> DraftRepeat(nextKey(), step.times, step.intervals.map { it.draft() })
                    }
                },
            )
        }

        fun blank(units: SpeedUnit, defaults: Pair<SegmentDefaults, SegmentDefaults>, nextKey: () -> Long) = EditorDraft(
            units = units,
            warmup = defaults.first.draft(units, nextKey()),
            cooldown = defaults.second.draft(units, nextKey()),
            steps = listOf(
                DraftInterval(DraftSegment(nextKey(), durationSec = 300, speed = round1(units.fromKmh(DEFAULT_INTERVAL_KMH)), incline = 1.0)),
            ),
        )

        private const val DEFAULT_INTERVAL_KMH = 3.0 * SpeedUnit.KM_PER_MILE
    }
}

fun DraftSegment.toSegment() = Segment(
    name = name.trim().ifEmpty { null },
    durationSec = durationSec,
    speed = speed,
    incline = incline,
)

fun SegmentDefaults.draft(units: SpeedUnit, key: Long) =
    DraftSegment(key, durationSec = durationSec, speed = round1(units.fromKmh(speedKmh)), incline = inclinePercent)

fun round1(value: Double) = (value * 10).roundToInt() / 10.0

/** Duration steps get coarser as the duration grows so long intervals don't need dozens of taps. */
fun durationStep(current: Int, up: Boolean): Int {
    val ref = if (up) current else current - 1
    return when {
        ref < 60 -> 5
        ref < 600 -> 15
        else -> 60
    }
}
