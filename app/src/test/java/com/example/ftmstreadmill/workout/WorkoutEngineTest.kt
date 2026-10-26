package com.example.ftmstreadmill.workout

import com.example.ftmstreadmill.data.CommandOutcome
import com.example.ftmstreadmill.data.ConnectionState
import com.example.ftmstreadmill.data.DeviceInfo
import com.example.ftmstreadmill.data.FakeSessionDao
import com.example.ftmstreadmill.data.FakeTreadmillController
import com.example.ftmstreadmill.data.HistoryRepository
import com.example.ftmstreadmill.data.TreadmillEvent
import com.example.ftmstreadmill.ftms.model.ControlResult
import com.example.ftmstreadmill.ftms.model.MachineStatus
import com.example.ftmstreadmill.ftms.model.TreadmillData
import com.example.ftmstreadmill.workout.model.EndReason
import com.example.ftmstreadmill.workout.model.Repeat
import com.example.ftmstreadmill.workout.model.Segment
import com.example.ftmstreadmill.workout.model.SpeedUnit
import com.example.ftmstreadmill.workout.model.Workout
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class WorkoutEngineTest {

    private val controller = FakeTreadmillController()
    private val dao = FakeSessionDao()

    /** 1:00 warmup, then 2 × [0:30 @ 8 km/h 2 %, 0:30 @ 4 km/h]: five segments, 3:00 in total. */
    private val workout = Workout(
        name = "Test",
        units = SpeedUnit.KMH,
        warmup = Segment("Warmup", 60, 3.0, 0.0),
        intervals = listOf(
            Repeat(2, listOf(Segment("Fast", 30, 8.0, 2.0), Segment("Easy", 30, 4.0, 0.0))),
        ),
    )

    private fun TestScope.newEngine(): WorkoutEngine {
        val clock = object : Clock {
            override fun elapsedMs() = testScheduler.currentTime
            override fun wallMs() = WALL_START + testScheduler.currentTime
        }
        return WorkoutEngine(controller, HistoryRepository(dao), clock, backgroundScope).also { runCurrent() }
    }

    private fun WorkoutEngine.active() = state.value as WorkoutRunState.Active

    private fun TestScope.startRunning(engine: WorkoutEngine) {
        engine.start(null, workout, countdown = false)
        runCurrent()
    }

    @Test
    fun `countdown then start and first targets`() = runTest {
        val engine = newEngine()
        engine.start(null, workout)
        runCurrent()
        assertEquals(RunPhase.COUNTDOWN, engine.active().phase)
        assertTrue(controller.commands.isEmpty())

        advanceTimeBy(3_001)
        runCurrent()
        assertEquals(RunPhase.RUNNING, engine.active().phase)
        assertEquals(listOf("start", "speed 3.0", "incline 0.0"), controller.commands)
    }

    @Test
    fun `targets are sent at each segment boundary`() = runTest {
        val engine = newEngine()
        startRunning(engine)
        controller.commands.clear()

        advanceTimeBy(60_300)
        runCurrent()
        assertEquals(1, engine.active().index)
        assertEquals(listOf("speed 8.0", "incline 2.0"), controller.commands)
        assertEquals(1, engine.active().current.intervalNumber)

        advanceTimeBy(30_000)
        runCurrent()
        assertEquals(2, engine.active().index)
        assertEquals(listOf("speed 8.0", "incline 2.0", "speed 4.0", "incline 0.0"), controller.commands)
    }

    @Test
    fun `in-app tweak applies to the current interval only`() = runTest {
        val engine = newEngine()
        startRunning(engine)
        controller.commands.clear()

        engine.setSpeed(5.0)
        runCurrent()
        assertEquals(5.0, engine.active().speedOverrideKmh!!, 1e-9)
        assertTrue("debounced", controller.commands.isEmpty())
        advanceTimeBy(400)
        runCurrent()
        assertEquals(listOf("speed 5.0"), controller.commands)

        advanceTimeBy(60_000)
        runCurrent()
        assertNull(engine.active().speedOverrideKmh)
        assertEquals(8.0, engine.active().targetSpeedKmh, 1e-9)
        assertEquals("speed 8.0", controller.commands[1])
    }

    @Test
    fun `speed change on the treadmill becomes a temporary override`() = runTest {
        val engine = newEngine()
        startRunning(engine)
        controller.commands.clear()

        controller.treadmillState.value = controller.treadmillState.value.copy(targetSpeedKmh = 6.0)
        runCurrent()
        assertEquals(6.0, engine.active().speedOverrideKmh!!, 1e-9)
        assertTrue("no command echoed back", controller.commands.isEmpty())

        advanceTimeBy(60_300)
        runCurrent()
        assertNull(engine.active().speedOverrideKmh)
        assertEquals(listOf("speed 8.0", "incline 2.0"), controller.commands)
    }

    @Test
    fun `pause and resume from the treadmill`() = runTest {
        val engine = newEngine()
        startRunning(engine)
        advanceTimeBy(10_000)
        controller.commands.clear()

        controller.events.emit(TreadmillEvent.Status(MachineStatus.PausedByUser))
        runCurrent()
        val paused = engine.active()
        assertEquals(RunPhase.PAUSED, paused.phase)
        assertEquals(PauseReason.TREADMILL, paused.pauseReason)
        assertTrue("treadmill already paused itself", controller.commands.isEmpty())

        advanceTimeBy(30_000)
        runCurrent()
        assertEquals("clock frozen while paused", paused.elapsedMs, engine.active().elapsedMs)

        controller.events.emit(TreadmillEvent.Status(MachineStatus.StartedOrResumedByUser))
        runCurrent()
        assertEquals(RunPhase.RUNNING, engine.active().phase)
        assertEquals(listOf("speed 3.0", "incline 0.0"), controller.commands)
    }

    @Test
    fun `stop on the treadmill ends and saves the workout`() = runTest {
        val engine = newEngine()
        startRunning(engine)
        advanceTimeBy(20_000)
        runCurrent()
        controller.commands.clear()

        controller.events.emit(TreadmillEvent.Status(MachineStatus.StoppedByUser))
        runCurrent()
        val finished = engine.state.value as WorkoutRunState.Finished
        assertEquals(EndReason.STOPPED_ON_TREADMILL, finished.session.endReason)
        assertEquals(20, finished.session.activeSec)
        assertEquals(1, dao.inserted.size)
        assertFalse("no stop sent", "stop" in controller.commands)
    }

    @Test
    fun `app pause falls back to stop and ignores its echo`() = runTest {
        controller.pauseOutcome = CommandOutcome.Rejected(
            ControlResult.NOT_SUPPORTED,
        )
        val engine = newEngine()
        startRunning(engine)
        advanceTimeBy(5_000)
        engine.pause()
        runCurrent()
        assertEquals(listOf("pause", "stop"), controller.commands.takeLast(2))

        controller.events.emit(TreadmillEvent.Status(MachineStatus.StoppedByUser))
        runCurrent()
        assertEquals(RunPhase.PAUSED, engine.active().phase)
    }

    @Test
    fun `connection loss pauses and stays paused after reconnecting`() = runTest {
        val engine = newEngine()
        startRunning(engine)
        advanceTimeBy(5_000)
        val device = DeviceInfo("AA:BB:CC:DD:EE:FF", "Treadmill")

        controller.connectionState.value = ConnectionState.Reconnecting(device, 1, 1_000)
        runCurrent()
        assertEquals(PauseReason.DISCONNECTED, engine.active().pauseReason)

        controller.connectionState.value = ConnectionState.Ready(device, hasControl = true)
        runCurrent()
        assertEquals(RunPhase.PAUSED, engine.active().phase)

        controller.commands.clear()
        engine.resume()
        runCurrent()
        assertEquals(RunPhase.RUNNING, engine.active().phase)
        assertEquals(listOf("start", "speed 3.0", "incline 0.0"), controller.commands)
    }

    @Test
    fun `belt stopping without a status notification pauses`() = runTest {
        val engine = newEngine()
        startRunning(engine)
        controller.treadmillState.value = controller.treadmillState.value.copy(data = TreadmillData(speedKmh = 3.0))
        advanceTimeBy(2_000)
        controller.treadmillState.value = controller.treadmillState.value.copy(data = TreadmillData(speedKmh = 0.0))
        advanceTimeBy(3_500)
        runCurrent()
        assertEquals(PauseReason.TREADMILL, engine.active().pauseReason)
    }

    @Test
    fun `skip moves to the next interval`() = runTest {
        val engine = newEngine()
        startRunning(engine)
        advanceTimeBy(5_000)
        engine.skip()
        runCurrent()
        assertEquals(1, engine.active().index)
        assertEquals(0L, engine.active().segmentElapsedMs)
    }

    @Test
    fun `runs to completion and records every segment`() = runTest {
        val engine = newEngine()
        startRunning(engine)
        controller.treadmillState.value = controller.treadmillState.value.copy(
            data = TreadmillData(speedKmh = 6.0, totalDistanceMeters = 100),
        )
        advanceTimeBy(60_000)
        controller.treadmillState.value = controller.treadmillState.value.copy(
            data = TreadmillData(speedKmh = 6.0, totalDistanceMeters = 200),
        )
        advanceTimeBy(121_000)
        runCurrent()

        val session = (engine.state.value as WorkoutRunState.Finished).session
        assertEquals(EndReason.COMPLETED, session.endReason)
        assertEquals(5, session.segments.size)
        assertEquals(180, session.activeSec)
        assertEquals(100, session.distanceMeters)
        assertEquals("stop", controller.commands.last())
        assertEquals(1L, session.id)

        engine.dismiss()
        runCurrent()
        assertEquals(WorkoutRunState.Idle, engine.state.value)
    }

    private companion object {
        const val WALL_START = 1_700_000_000_000L
    }
}
