package com.example.ftmstreadmill.workout.model

import com.example.ftmstreadmill.workout.io.DurationSerializer
import com.example.ftmstreadmill.workout.io.WorkoutStepSerializer
import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonNames

/**
 * A workout exactly as it appears in an exported `.json` file. Speeds are in [units] so a file
 * written in mph stays readable in mph; convert with [SpeedUnit.toKmh] before driving the treadmill.
 */
@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class Workout(
    @EncodeDefault val format: String = FORMAT,
    @EncodeDefault val version: Int = VERSION,
    val name: String,
    val description: String? = null,
    @EncodeDefault val units: SpeedUnit = SpeedUnit.MPH,
    val warmup: Segment? = null,
    val intervals: List<WorkoutStep> = emptyList(),
    val cooldown: Segment? = null,
) {
    companion object {
        const val FORMAT = "ftms-workout"
        const val VERSION = 1
    }
}

@OptIn(ExperimentalSerializationApi::class)
@Serializable
enum class SpeedUnit {
    @SerialName("mph") @JsonNames("MPH", "mi/h") MPH,
    @SerialName("km/h") @JsonNames("kmh", "kph", "KMH", "km/h") KMH;

    fun toKmh(value: Double): Double = if (this == MPH) value * KM_PER_MILE else value

    fun fromKmh(kmh: Double): Double = if (this == MPH) kmh / KM_PER_MILE else kmh

    companion object {
        const val KM_PER_MILE = 1.609344
    }
}

/** One step of a workout. In JSON an interval is a plain object; a repeat block has a `"repeat"` count. */
@Serializable(with = WorkoutStepSerializer::class)
sealed interface WorkoutStep

/** A constant speed/incline period: an interval, warmup or cooldown. */
@Serializable
data class Segment(
    val name: String? = null,
    @Serializable(with = DurationSerializer::class)
    @SerialName("duration") val durationSec: Int,
    val speed: Double,
    @EncodeDefault(EncodeDefault.Mode.ALWAYS) val incline: Double = 0.0,
) : WorkoutStep

/** [intervals] run in order, [times] times over. */
@Serializable
data class Repeat(
    @SerialName("repeat") val times: Int,
    val intervals: List<Segment>,
) : WorkoutStep
