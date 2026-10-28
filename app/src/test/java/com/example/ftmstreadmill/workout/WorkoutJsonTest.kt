package com.example.ftmstreadmill.workout

import com.example.ftmstreadmill.workout.io.DurationSerializer
import com.example.ftmstreadmill.workout.io.WorkoutFormatException
import com.example.ftmstreadmill.workout.io.WorkoutJson
import com.example.ftmstreadmill.workout.model.Repeat
import com.example.ftmstreadmill.workout.model.Segment
import com.example.ftmstreadmill.workout.model.SegmentKind
import com.example.ftmstreadmill.workout.model.SpeedUnit
import com.example.ftmstreadmill.workout.model.Workout
import com.example.ftmstreadmill.workout.model.WorkoutPlan
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class WorkoutJsonTest {

    private val sample = Workout(
        name = "Hills",
        description = "Up and down",
        units = SpeedUnit.MPH,
        warmup = Segment(durationSec = 300, speed = 2.0),
        intervals = listOf(
            Segment("Climb", 120, 3.5, 6.0),
            Repeat(4, listOf(Segment("Push", 60, 5.0, 2.0), Segment("Easy", 90, 3.0))),
        ),
        cooldown = Segment(durationSec = 300, speed = 2.0),
    )

    @Test
    fun `round trips and is human readable`() {
        val text = WorkoutJson.encode(sample)
        assertTrue(text, "\"duration\": \"5:00\"" in text)
        assertTrue(text, "\"units\": \"mph\"" in text)
        assertTrue(text, "\"repeat\": 4" in text)
        assertTrue(text, "\"format\": \"ftms-workout\"" in text)
        assertEquals(sample, WorkoutJson.decodeSingle(text))
    }

    @Test
    fun `accepts hand-written variations`() {
        val text = """
            {
              // comments and trailing commas are fine when editing by hand
              "name": "Quick",
              "units": "kmh",
              "intervals": [
                { "duration": 90, "speed": 8 },
                { "duration": "1:00:00", "speed": 6.5, "incline": 1, },
              ],
            }
        """.trimIndent()
        val workout = WorkoutJson.decodeSingle(text)
        assertEquals(SpeedUnit.KMH, workout.units)
        assertNull(workout.warmup)
        assertEquals(90, (workout.intervals[0] as Segment).durationSec)
        assertEquals(3600, (workout.intervals[1] as Segment).durationSec)
    }

    @Test
    fun `reads export-all files and arrays`() {
        val two = WorkoutJson.encodeAll(listOf(sample, sample.copy(name = "Other")))
        assertEquals(listOf("Hills", "Other"), WorkoutJson.decode(two).map { it.name })
        val array = "[${WorkoutJson.encode(sample)}]"
        assertEquals(1, WorkoutJson.decode(array).size)
    }

    @Test
    fun `explains what is wrong`() {
        fun error(text: String) = assertThrows(WorkoutFormatException::class.java) { WorkoutJson.decode(text) }.message!!

        assertTrue(error("{ nope"), error("{ nope").contains("not valid JSON"))
        val tooFast = """{ "name": "X", "units": "mph", "intervals": [ { "duration": "1:00", "speed": 40 } ] }"""
        assertTrue(error(tooFast), error(tooFast).contains("\"X\" interval 1: speed 40.0 mph"))
        val badDuration = """{ "name": "X", "intervals": [ { "duration": "1:75", "speed": 3 } ] }"""
        assertTrue(error(badDuration), error(badDuration).contains("1:75"))
        val empty = """{ "name": "X", "intervals": [] }"""
        assertTrue(error(empty), error(empty).contains("no intervals"))
    }

    @Test
    fun `duration formatting`() {
        assertEquals("0:30", DurationSerializer.format(30))
        assertEquals("1:02:03", DurationSerializer.format(3723))
        assertEquals(3723, DurationSerializer.parse("1:02:03"))
    }

    @Test
    fun `plan expands repeats and converts units`() {
        val plan = WorkoutPlan.of(sample)
        assertEquals(1 + 1 + 8 + 1, plan.segments.size)
        assertEquals(SegmentKind.WARMUP, plan.segments.first().kind)
        assertEquals(SegmentKind.COOLDOWN, plan.segments.last().kind)
        assertEquals(9, plan.intervalCount)
        assertEquals(300 + 120 + 4 * 150 + 300, plan.totalSec)
        assertEquals(3.5 * SpeedUnit.KM_PER_MILE, plan.segments[1].speedKmh, 1e-9)
        assertEquals(4, plan.segments.last { it.repeat != null }.repeat!!.round)
        assertEquals(420, plan.startOffsets[2])
    }

    @Test
    fun `every bundled preset is valid`() {
        val dir = File("src/main/assets/presets")
        val files = dir.listFiles { f -> f.extension == "json" }.orEmpty()
        assertTrue("presets found in ${dir.absolutePath}", files.isNotEmpty())
        files.forEach { WorkoutJson.decodeSingle(it.readText()) }
    }
}
