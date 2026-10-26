package com.example.ftmstreadmill.workout.io

import com.example.ftmstreadmill.workout.model.Repeat
import com.example.ftmstreadmill.workout.model.Segment
import com.example.ftmstreadmill.workout.model.SpeedUnit
import com.example.ftmstreadmill.workout.model.Workout
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import java.util.Locale

/** Thrown when an imported file can't be used. [message] is written for the user. */
class WorkoutFormatException(message: String) : Exception(message)

/**
 * Reads and writes the workout file format. A file holds a single workout object, a JSON array of
 * them, or `{ "workouts": [ ... ] }` (what "Export all" writes).
 */
object WorkoutJson {

    @OptIn(ExperimentalSerializationApi::class)
    private val json = Json {
        prettyPrint = true
        prettyPrintIndent = "  "
        ignoreUnknownKeys = true
        explicitNulls = false
        isLenient = true
        allowTrailingComma = true
        allowComments = true
    }

    @Serializable
    private data class WorkoutCollection(val workouts: List<Workout>)

    fun encode(workout: Workout): String = json.encodeToString(Workout.serializer(), workout)

    fun encodeAll(workouts: List<Workout>): String =
        if (workouts.size == 1) encode(workouts.single())
        else json.encodeToString(WorkoutCollection.serializer(), WorkoutCollection(workouts))

    /** Decodes and validates. Throws [WorkoutFormatException] with a readable message. */
    fun decode(text: String): List<Workout> {
        val root = try {
            json.parseToJsonElement(text)
        } catch (e: SerializationException) {
            throw WorkoutFormatException("This file is not valid JSON. ${e.cleanMessage()}")
        }
        val elements: List<JsonElement> = when {
            root is JsonArray -> root
            root is JsonObject && "workouts" in root && "name" !in root ->
                root["workouts"] as? JsonArray ?: throw WorkoutFormatException("\"workouts\" must be a list")
            root is JsonObject -> listOf(root)
            else -> throw WorkoutFormatException("This file doesn't contain a workout")
        }
        if (elements.isEmpty()) throw WorkoutFormatException("This file doesn't contain any workouts")
        return elements.mapIndexed { i, element ->
            val label = if (elements.size > 1) "Workout ${i + 1}" else "Workout"
            val workout = try {
                json.decodeFromJsonElement<Workout>(element)
            } catch (e: SerializationException) {
                throw WorkoutFormatException("$label: ${e.cleanMessage()}")
            } catch (e: IllegalArgumentException) {
                throw WorkoutFormatException("$label: ${e.message}")
            }
            validate(workout)
            workout
        }
    }

    fun decodeSingle(text: String): Workout = decode(text).first()

    /** Checks the values a treadmill could actually run. Throws [WorkoutFormatException]. */
    fun validate(workout: Workout) {
        val name = workout.name.ifBlank { throw WorkoutFormatException("A workout needs a name") }
        val prefix = "\"$name\""
        if (workout.version > Workout.VERSION) {
            throw WorkoutFormatException("$prefix was made by a newer version of the app (format version ${workout.version})")
        }
        if (workout.intervals.isEmpty()) throw WorkoutFormatException("$prefix has no intervals")
        workout.warmup?.let { checkSegment(it, "$prefix warmup", workout.units) }
        workout.cooldown?.let { checkSegment(it, "$prefix cooldown", workout.units) }
        workout.intervals.forEachIndexed { i, step ->
            val label = "$prefix interval ${i + 1}"
            when (step) {
                is Segment -> checkSegment(step, label, workout.units)
                is Repeat -> {
                    if (step.times !in 1..MAX_REPEATS) throw WorkoutFormatException("$label: repeat must be between 1 and $MAX_REPEATS")
                    if (step.intervals.isEmpty()) throw WorkoutFormatException("$label: repeat block has no intervals")
                    step.intervals.forEachIndexed { j, s -> checkSegment(s, "$label.${j + 1}", workout.units) }
                }
            }
        }
    }

    private fun checkSegment(segment: Segment, label: String, units: SpeedUnit) {
        if (segment.durationSec !in 1..MAX_SEGMENT_SEC) {
            throw WorkoutFormatException("$label: duration must be between 0:01 and ${DurationSerializer.format(MAX_SEGMENT_SEC)}")
        }
        val maxSpeed = units.fromKmh(MAX_SPEED_KMH)
        if (segment.speed.isNaN() || segment.speed <= 0.0 || segment.speed > maxSpeed) {
            throw WorkoutFormatException(
                "$label: speed ${segment.speed.fmt()} ${units.label} must be above 0 and at most ${maxSpeed.fmt()}",
            )
        }
        if (segment.incline.isNaN() || segment.incline !in MIN_INCLINE..MAX_INCLINE) {
            throw WorkoutFormatException("$label: incline ${segment.incline.fmt()}% must be between ${MIN_INCLINE.fmt()} and ${MAX_INCLINE.fmt()}")
        }
    }

    private val SpeedUnit.label get() = if (this == SpeedUnit.MPH) "mph" else "km/h"

    private fun Double.fmt() = "%.1f".format(Locale.US, this)

    /** kotlinx messages include the whole input; keep just the useful first line. */
    private fun SerializationException.cleanMessage(): String =
        (message ?: "Unreadable file").lineSequence().first().removeSuffix(".").let { "$it." }

    const val MIME_TYPE = "application/json"
    const val MAX_REPEATS = 99
    const val MAX_SEGMENT_SEC = 4 * 3600
    const val MAX_SPEED_KMH = 30.0
    const val MIN_INCLINE = -10.0
    const val MAX_INCLINE = 40.0
}
