package com.example.ftmstreadmill.workout.io

import com.example.ftmstreadmill.workout.model.Repeat
import com.example.ftmstreadmill.workout.model.Segment
import com.example.ftmstreadmill.workout.model.WorkoutStep
import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonContentPolymorphicSerializer
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull

/**
 * Durations are written as `"m:ss"` or `"h:mm:ss"` so they read naturally in a text editor.
 * A bare number of seconds is accepted too.
 */
object DurationSerializer : KSerializer<Int> {
    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("Duration", PrimitiveKind.STRING)

    override fun serialize(encoder: Encoder, value: Int) = encoder.encodeString(format(value))

    override fun deserialize(decoder: Decoder): Int {
        val element = (decoder as? JsonDecoder)?.decodeJsonElement() ?: return parse(decoder.decodeString())
        val primitive = element as? JsonPrimitive ?: throw SerializationException("duration must be \"m:ss\" or a number of seconds")
        return if (primitive.isString) parse(primitive.content)
        else primitive.intOrNull ?: throw SerializationException("duration must be a whole number of seconds, got ${primitive.content}")
    }

    fun format(seconds: Int): String {
        val h = seconds / 3600
        val m = seconds % 3600 / 60
        val s = seconds % 60
        return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
    }

    /** Parses "90", "1:30" or "1:00:00". */
    fun parse(text: String): Int {
        val parts = text.trim().split(':')
        val numbers = parts.map {
            it.trim().toIntOrNull()?.takeIf { n -> n >= 0 }
                ?: throw SerializationException("duration \"$text\" is not in m:ss form")
        }
        return when (numbers.size) {
            1 -> numbers[0]
            2 -> numbers[0] * 60 + numbers[1].also { checkSeconds(it, text) }
            3 -> numbers[0] * 3600 + numbers[1].also { checkSeconds(it, text) } * 60 + numbers[2].also { checkSeconds(it, text) }
            else -> throw SerializationException("duration \"$text\" is not in m:ss form")
        }
    }

    private fun checkSeconds(value: Int, text: String) {
        if (value > 59) throw SerializationException("duration \"$text\" has more than 59 in a minutes/seconds field")
    }
}

object WorkoutStepSerializer : JsonContentPolymorphicSerializer<WorkoutStep>(WorkoutStep::class) {
    override fun selectDeserializer(element: JsonElement): DeserializationStrategy<WorkoutStep> {
        val obj = element as? JsonObject ?: throw SerializationException("each entry in \"intervals\" must be an object")
        return if ("repeat" in obj) Repeat.serializer() else Segment.serializer()
    }
}
