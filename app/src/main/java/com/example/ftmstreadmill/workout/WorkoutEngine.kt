package com.example.ftmstreadmill.workout

import android.util.Log
import com.example.ftmstreadmill.data.CommandOutcome
import com.example.ftmstreadmill.data.ConnectionState
import com.example.ftmstreadmill.data.HistoryRepository
import com.example.ftmstreadmill.data.TreadmillController
import com.example.ftmstreadmill.data.TreadmillEvent
import com.example.ftmstreadmill.di.ApplicationScope
import com.example.ftmstreadmill.ftms.model.MachineStatus
import com.example.ftmstreadmill.workout.model.EndReason
import com.example.ftmstreadmill.workout.model.SegmentResult
import com.example.ftmstreadmill.workout.model.Session
import com.example.ftmstreadmill.workout.model.SpeedSample
import com.example.ftmstreadmill.workout.model.SpeedUnit
import com.example.ftmstreadmill.workout.model.Workout
import com.example.ftmstreadmill.workout.model.WorkoutPlan
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Runs a workout against the treadmill: keeps time, sends each segment's speed and incline at its
 * boundary, applies temporary per-segment tweaks, follows the treadmill's own pause/start/stop
 * buttons and records the result to history.
 *
 * All state changes happen on one actor coroutine fed by [inbox], so timer ticks, button presses and
 * treadmill notifications can never interleave. Treadmill commands are sent from separate jobs so a
 * slow control-point response never stalls the clock.
 */
@Singleton
class WorkoutEngine @Inject constructor(
    private val controller: TreadmillController,
    private val history: HistoryRepository,
    private val clock: Clock,
    @ApplicationScope private val scope: CoroutineScope,
) {
    private val _state = MutableStateFlow<WorkoutRunState>(WorkoutRunState.Idle)
    val state: StateFlow<WorkoutRunState> = _state.asStateFlow()

    private val _events = MutableSharedFlow<WorkoutEvent>(extraBufferCapacity = 32)
    val events: Flow<WorkoutEvent> = _events.asSharedFlow()

    private sealed interface Msg {
        data class Start(val workoutId: Long?, val workout: Workout, val countdown: Boolean) : Msg
        data class CountdownTick(val secondsLeft: Int) : Msg
        data object BeginRunning : Msg
        data object Tick : Msg
        data class Pause(val reason: PauseReason, val sendCommand: Boolean) : Msg
        data class Resume(val sendCommand: Boolean) : Msg
        data class End(val reason: EndReason, val sendStop: Boolean) : Msg
        data object Skip : Msg
        data class SetSpeed(val kmh: Double) : Msg
        data class SetIncline(val percent: Double) : Msg
        data class NudgeSpeed(val steps: Int, val unit: SpeedUnit) : Msg
        data class NudgeIncline(val steps: Int) : Msg
        data object ResetToPlan : Msg
        data class ExternalSpeed(val kmh: Double) : Msg
        data class ExternalIncline(val percent: Double) : Msg
        data class Status(val status: MachineStatus) : Msg
        data class Connection(val ready: Boolean) : Msg
        data object Dismiss : Msg
    }

    private val inbox = Channel<Msg>(Channel.UNLIMITED)

    // ---- Actor-private bookkeeping (touched only from the actor) -----------------------------

    private var tickerJob: Job? = null
    private var countdownJob: Job? = null
    private var targetsJob: Job? = null
    private var speedJob: Job? = null
    private var inclineJob: Job? = null

    private var lastTickAt = 0L
    private var startedAtWall = 0L
    private var lastCommandedSpeed: Double? = null
    private var lastCommandedIncline: Double? = null
    private var lastCountdownAnnounced = 0
    private var halfwayAnnounced = false
    private var beltSeenMoving = false
    private var beltStoppedSinceMs: Long? = null
    private var ignoreStopUntil = 0L
    private var lastReportedDistance: Int? = null
    private var lastReportedKcal: Int? = null
    private var reportedDistanceSeen = false
    private var kcalTotal: Int? = null
    private var lastSampleAt = 0L

    private val results = mutableListOf<SegmentResult>()
    private val samples = mutableListOf<SpeedSample>()
    private var segment = SegmentAccumulator()

    private class SegmentAccumulator(
        val startedAtWall: Long = 0,
        var activeMs: Long = 0,
        var speedIntegral: Double = 0.0,
        var speedMs: Long = 0,
        var inclineIntegral: Double = 0.0,
        var inclineMs: Long = 0,
        var distance: Double = 0.0,
        var adjusted: Boolean = false,
    )

    init {
        scope.launch { for (msg in inbox) handle(msg) }
        scope.launch {
            controller.events.collect { event ->
                if (event is TreadmillEvent.Status) inbox.send(Msg.Status(event.status))
            }
        }
        scope.launch {
            controller.connectionState.map { it is ConnectionState.Ready }.distinctUntilChanged()
                .collect { inbox.send(Msg.Connection(it)) }
        }
        scope.launch {
            controller.treadmillState.map { it.targetSpeedKmh }.distinctUntilChanged()
                .collect { if (it != null) inbox.send(Msg.ExternalSpeed(it)) }
        }
        scope.launch {
            controller.treadmillState.map { it.targetInclinePercent }.distinctUntilChanged()
                .collect { if (it != null) inbox.send(Msg.ExternalIncline(it)) }
        }
    }

    // ---- Public API ---------------------------------------------------------------------------

    fun start(workoutId: Long?, workout: Workout, countdown: Boolean = true) =
        send(Msg.Start(workoutId, workout, countdown))

    fun pause() = send(Msg.Pause(PauseReason.USER, sendCommand = true))

    fun resume() = send(Msg.Resume(sendCommand = true))

    fun end() = send(Msg.End(EndReason.ENDED_IN_APP, sendStop = true))

    fun skip() = send(Msg.Skip)

    fun setSpeed(kmh: Double) = send(Msg.SetSpeed(kmh))

    fun setIncline(percent: Double) = send(Msg.SetIncline(percent))

    /** Changes the current target by [steps] tenths of [unit]; rapid taps accumulate correctly. */
    fun nudgeSpeed(steps: Int, unit: SpeedUnit) = send(Msg.NudgeSpeed(steps, unit))

    /** Changes the current incline target by [steps] of the treadmill's incline increment. */
    fun nudgeIncline(steps: Int) = send(Msg.NudgeIncline(steps))

    fun resetToPlan() = send(Msg.ResetToPlan)

    /** Clears the finished summary. */
    fun dismiss() = send(Msg.Dismiss)

    private fun send(msg: Msg) {
        inbox.trySend(msg)
    }

    // ---- Actor --------------------------------------------------------------------------------

    private suspend fun handle(msg: Msg) {
        val active = _state.value as? WorkoutRunState.Active
        when (msg) {
            is Msg.Start -> if (active == null) begin(msg)
            is Msg.CountdownTick -> if (active?.phase == RunPhase.COUNTDOWN) {
                _state.value = active.copy(countdown = msg.secondsLeft)
                _events.tryEmit(WorkoutEvent.Countdown(msg.secondsLeft))
            }
            Msg.BeginRunning -> if (active?.phase == RunPhase.COUNTDOWN) beginRunning(active)
            Msg.Tick -> if (active?.phase == RunPhase.RUNNING) tick(active)
            is Msg.Pause -> if (active != null) pauseRun(active, msg.reason, msg.sendCommand)
            is Msg.Resume -> if (active?.phase == RunPhase.PAUSED) resumeRun(active, msg.sendCommand)
            is Msg.End -> if (active != null) finish(msg.reason, msg.sendStop)
            Msg.Skip -> if (active != null && active.phase != RunPhase.COUNTDOWN) skip(active)
            is Msg.SetSpeed -> if (active != null) overrideSpeed(active, msg.kmh)
            is Msg.SetIncline -> if (active != null) overrideIncline(active, msg.percent)
            is Msg.NudgeSpeed -> if (active != null) {
                val shown = (msg.unit.fromKmh(active.targetSpeedKmh) * 10).roundToInt()
                overrideSpeed(active, msg.unit.toKmh((shown + msg.steps) / 10.0))
            }
            is Msg.NudgeIncline -> if (active != null) {
                val step = controller.treadmillState.value.inclineRange.step.takeIf { it > 0 } ?: DEFAULT_INCLINE_STEP
                overrideIncline(active, active.targetInclinePercent + msg.steps * step)
            }
            Msg.ResetToPlan -> if (active != null) {
                _state.value = active.copy(speedOverrideKmh = null, inclineOverridePercent = null)
                if (active.phase == RunPhase.RUNNING) sendTargets()
            }
            is Msg.ExternalSpeed -> if (active != null) onExternalSpeed(active, msg.kmh)
            is Msg.ExternalIncline -> if (active != null) onExternalIncline(active, msg.percent)
            is Msg.Status -> if (active != null) onMachineStatus(active, msg.status)
            is Msg.Connection -> if (active != null && !msg.ready && active.phase != RunPhase.PAUSED) {
                pauseRun(active, PauseReason.DISCONNECTED, sendCommand = false)
            }
            Msg.Dismiss -> if (_state.value is WorkoutRunState.Finished) _state.value = WorkoutRunState.Idle
        }
    }

    private fun begin(msg: Msg.Start) {
        val plan = WorkoutPlan.of(msg.workout)
        if (plan.segments.isEmpty()) return
        results.clear()
        samples.clear()
        halfwayAnnounced = false
        lastCountdownAnnounced = 0
        lastReportedDistance = null
        lastReportedKcal = null
        reportedDistanceSeen = false
        kcalTotal = null
        lastCommandedSpeed = null
        lastCommandedIncline = null
        startedAtWall = clock.wallMs()
        val active = WorkoutRunState.Active(msg.workoutId, msg.workout, plan, RunPhase.COUNTDOWN, countdown = COUNTDOWN_SEC)
        _state.value = active
        if (!msg.countdown) {
            beginRunning(active)
            return
        }
        countdownJob = scope.launch {
            for (n in COUNTDOWN_SEC downTo 1) {
                inbox.send(Msg.CountdownTick(n))
                delay(1_000)
            }
            inbox.send(Msg.BeginRunning)
        }
    }

    private fun beginRunning(active: WorkoutRunState.Active) {
        startedAtWall = clock.wallMs()
        val running = active.copy(phase = RunPhase.RUNNING, countdown = 0)
        _state.value = running
        segment = SegmentAccumulator(startedAtWall = startedAtWall)
        startClock()
        targetsJob?.cancel()
        targetsJob = scope.launch {
            report("start", controller.start())
            applyTargets(running.targetSpeedKmh, running.targetInclinePercent)
        }
        _events.tryEmit(WorkoutEvent.SegmentStarted(0, running.current, running.next, isFirst = true))
    }

    private fun startClock() {
        lastTickAt = clock.elapsedMs()
        lastSampleAt = 0
        beltSeenMoving = false
        beltStoppedSinceMs = null
        tickerJob?.cancel()
        tickerJob = scope.launch {
            while (true) {
                delay(TICK_MS)
                inbox.send(Msg.Tick)
            }
        }
    }

    private fun stopClock() {
        tickerJob?.cancel()
        tickerJob = null
        countdownJob?.cancel()
        countdownJob = null
    }

    /** Advances time to now: records samples, crosses segment boundaries and finishes at the end. */
    private suspend fun tick(start: WorkoutRunState.Active) {
        val now = clock.elapsedMs()
        val dt = (now - lastTickAt).coerceAtLeast(0)
        lastTickAt = now
        if (dt == 0L) return

        val data = controller.treadmillState.value.data
        val speed = data.speedKmh
        val incline = data.inclinePercent
        segment.activeMs += dt
        if (speed != null) {
            segment.speedIntegral += speed * dt
            segment.speedMs += dt
        }
        if (incline != null) {
            segment.inclineIntegral += incline * dt
            segment.inclineMs += dt
        }

        // Prefer the treadmill's odometer; integrate speed only for machines that don't report one.
        // The odometer can reset when the treadmill stops, so a drop counts as a new baseline.
        val reported = data.totalDistanceMeters
        val distanceDelta = when {
            reported != null -> {
                reportedDistanceSeen = true
                val last = lastReportedDistance
                lastReportedDistance = reported
                when {
                    last == null -> 0.0
                    reported >= last -> (reported - last).toDouble()
                    else -> reported.toDouble()
                }
            }
            !reportedDistanceSeen && speed != null -> speed / 3.6 * dt / 1000.0
            else -> 0.0
        }
        segment.distance += distanceDelta
        data.totalEnergyKcal?.let { kcal ->
            val last = lastReportedKcal
            lastReportedKcal = kcal
            val delta = when {
                last == null -> 0
                kcal >= last -> kcal - last
                else -> kcal
            }
            kcalTotal = (kcalTotal ?: 0) + delta
        }

        val wall = clock.wallMs()
        if (speed != null && wall - lastSampleAt >= SAMPLE_INTERVAL_MS) {
            lastSampleAt = wall
            samples += SpeedSample(wall, speed, incline)
        }

        var state = start.copy(
            segmentElapsedMs = start.segmentElapsedMs + dt,
            elapsedMs = start.elapsedMs + dt,
            distanceMeters = start.distanceMeters + distanceDelta,
            kcal = kcalTotal,
        )

        // Belt stopped without a Machine Status notification (some treadmills never send one).
        if (speed != null && speed > 0.0) {
            beltSeenMoving = true
            beltStoppedSinceMs = null
        } else if (speed == 0.0 && beltSeenMoving) {
            val since = beltStoppedSinceMs ?: now.also { beltStoppedSinceMs = it }
            if (now - since >= BELT_STOPPED_PAUSE_MS) {
                _state.value = state
                pauseRun(state, PauseReason.TREADMILL, sendCommand = false)
                return
            }
        }

        // Cross as many boundaries as the elapsed time covers.
        while (state.segmentElapsedMs >= state.current.durationSec * 1000L) {
            val overflow = state.segmentElapsedMs - state.current.durationSec * 1000L
            closeSegment(state, skipped = false)
            if (state.next == null) {
                _state.value = state.copy(segmentElapsedMs = state.current.durationSec * 1000L)
                finish(EndReason.COMPLETED, sendStop = true)
                return
            }
            state = enterSegment(state, state.index + 1, overflow)
        }

        val secondsLeft = state.segmentRemainingSec
        if (secondsLeft in 1..PRE_CHANGE_COUNTDOWN && secondsLeft != lastCountdownAnnounced) {
            lastCountdownAnnounced = secondsLeft
            _events.tryEmit(WorkoutEvent.Countdown(secondsLeft))
        }
        if (!halfwayAnnounced && state.planProgress >= 0.5f) {
            halfwayAnnounced = true
            _events.tryEmit(WorkoutEvent.Halfway)
        }
        _state.value = state
    }

    private fun enterSegment(state: WorkoutRunState.Active, index: Int, elapsedMs: Long): WorkoutRunState.Active {
        val next = state.copy(
            index = index,
            segmentElapsedMs = elapsedMs,
            speedOverrideKmh = null,
            inclineOverridePercent = null,
        )
        segment = SegmentAccumulator(startedAtWall = clock.wallMs())
        lastCountdownAnnounced = 0
        _state.value = next
        if (next.phase == RunPhase.RUNNING) sendTargets()
        _events.tryEmit(WorkoutEvent.SegmentStarted(index, next.current, next.next, isFirst = false))
        return next
    }

    private fun closeSegment(state: WorkoutRunState.Active, skipped: Boolean) {
        val acc = segment
        results += SegmentResult(
            planned = state.current,
            startedAt = acc.startedAtWall,
            endedAt = clock.wallMs(),
            activeSec = (acc.activeMs / 1000).toInt(),
            avgSpeedKmh = if (acc.speedMs > 0) acc.speedIntegral / acc.speedMs else null,
            avgInclinePercent = if (acc.inclineMs > 0) acc.inclineIntegral / acc.inclineMs else null,
            distanceMeters = acc.distance,
            adjusted = acc.adjusted,
            skipped = skipped,
        )
    }

    private suspend fun skip(active: WorkoutRunState.Active) {
        val current = if (active.phase == RunPhase.RUNNING) {
            tick(active)
            _state.value as? WorkoutRunState.Active ?: return
        } else {
            active
        }
        closeSegment(current, skipped = true)
        if (current.next == null) {
            finish(EndReason.COMPLETED, sendStop = true)
        } else {
            enterSegment(current, current.index + 1, 0)
        }
    }

    private suspend fun pauseRun(active: WorkoutRunState.Active, reason: PauseReason, sendCommand: Boolean) {
        if (active.phase == RunPhase.COUNTDOWN) {
            // Nothing has run yet: drop back out rather than holding a paused countdown.
            stopClock()
            _state.value = WorkoutRunState.Idle
            return
        }
        if (active.phase == RunPhase.PAUSED) {
            // Already paused; a disconnect is the more important reason to show.
            if (reason == PauseReason.DISCONNECTED) _state.value = active.copy(pauseReason = reason)
            return
        }
        tick(active)
        val current = _state.value as? WorkoutRunState.Active ?: return
        if (current.phase != RunPhase.RUNNING) return
        stopClock()
        speedJob?.cancel()
        inclineJob?.cancel()
        _state.value = current.copy(phase = RunPhase.PAUSED, pauseReason = reason)
        _events.tryEmit(WorkoutEvent.Paused(reason))
        if (sendCommand) {
            targetsJob?.cancel()
            targetsJob = scope.launch {
                val outcome = controller.pause()
                if (outcome is CommandOutcome.Rejected) {
                    // Not every treadmill supports pause; stopping the belt is the safe fallback. Its
                    // "stopped by user" echo must not end the workout.
                    ignoreStopUntil = clock.elapsedMs() + STOP_ECHO_GRACE_MS
                    report("stop (pause fallback)", controller.stop())
                } else {
                    report("pause", outcome)
                }
            }
        }
    }

    private fun resumeRun(active: WorkoutRunState.Active, sendCommand: Boolean) {
        if (controller.connectionState.value !is ConnectionState.Ready) return
        val running = active.copy(phase = RunPhase.RUNNING, pauseReason = null)
        _state.value = running
        startClock()
        _events.tryEmit(WorkoutEvent.Resumed)
        targetsJob?.cancel()
        targetsJob = scope.launch {
            if (sendCommand) report("start", controller.start())
            // Many treadmills restart at a default speed, so always re-send the targets.
            applyTargets(running.targetSpeedKmh, running.targetInclinePercent)
        }
    }

    private suspend fun finish(reason: EndReason, sendStop: Boolean) {
        val active = _state.value as? WorkoutRunState.Active ?: return
        if (active.phase == RunPhase.RUNNING) tick(active)
        // tick() may already have finished the workout at the last boundary.
        val current = _state.value as? WorkoutRunState.Active ?: return
        stopClock()
        targetsJob?.cancel()
        speedJob?.cancel()
        inclineJob?.cancel()
        if (sendStop) scope.launch { report("stop", controller.stop()) }

        if (current.phase == RunPhase.COUNTDOWN || current.elapsedMs < MIN_SAVED_MS) {
            _state.value = WorkoutRunState.Idle
            return
        }
        if (results.size <= current.index) closeSegment(current, skipped = false)

        val session = Session(
            id = 0,
            workoutId = current.workoutId,
            workoutName = current.workout.name,
            startedAt = startedAtWall,
            endedAt = clock.wallMs(),
            activeSec = (current.elapsedMs / 1000).toInt(),
            distanceMeters = current.distanceMeters.toInt(),
            kcal = current.kcal,
            endReason = reason,
            workout = current.workout,
            segments = results.toList(),
            samples = samples.toList(),
            healthConnectExported = false,
        )
        val saved = try {
            session.copy(id = history.insert(session))
        } catch (e: Exception) {
            Log.e(TAG, "Could not save workout", e)
            session
        }
        _state.value = WorkoutRunState.Finished(saved)
        _events.tryEmit(WorkoutEvent.Finished(saved))
    }

    // ---- Targets and overrides ---------------------------------------------------------------

    private fun sendTargets() {
        val active = _state.value as? WorkoutRunState.Active ?: return
        speedJob?.cancel()
        inclineJob?.cancel()
        targetsJob?.cancel()
        targetsJob = scope.launch { applyTargets(active.targetSpeedKmh, active.targetInclinePercent) }
    }

    private suspend fun applyTargets(speedKmh: Double, inclinePercent: Double) {
        val treadmill = controller.treadmillState.value
        lastCommandedSpeed = treadmill.speedRange.clampAndSnap(speedKmh)
        lastCommandedIncline = treadmill.inclineRange.clampAndSnap(inclinePercent)
        if (treadmill.features.speedTargetSupported) {
            // Right after a start some treadmills refuse targets until the belt is up; retry once.
            if (controller.setSpeed(speedKmh) != CommandOutcome.Success) {
                delay(RETRY_DELAY_MS)
                report("speed", controller.setSpeed(speedKmh))
            }
        }
        if (treadmill.features.inclineTargetSupported) {
            if (controller.setIncline(inclinePercent) != CommandOutcome.Success) {
                delay(RETRY_DELAY_MS)
                report("incline", controller.setIncline(inclinePercent))
            }
        }
    }

    private fun overrideSpeed(active: WorkoutRunState.Active, kmh: Double) {
        val target = controller.treadmillState.value.speedRange.clampAndSnap(kmh)
        lastCommandedSpeed = target
        segment.adjusted = true
        _state.value = active.copy(speedOverrideKmh = target.takeUnless { near(it, active.current.speedKmh) })
        if (active.phase != RunPhase.RUNNING) return
        speedJob?.cancel()
        speedJob = scope.launch {
            delay(ADJUST_DEBOUNCE_MS)
            report("speed", controller.setSpeed(target))
        }
    }

    private fun overrideIncline(active: WorkoutRunState.Active, percent: Double) {
        val target = controller.treadmillState.value.inclineRange.clampAndSnap(percent)
        lastCommandedIncline = target
        segment.adjusted = true
        _state.value = active.copy(inclineOverridePercent = target.takeUnless { near(it, active.current.inclinePercent) })
        if (active.phase != RunPhase.RUNNING) return
        inclineJob?.cancel()
        inclineJob = scope.launch {
            delay(ADJUST_DEBOUNCE_MS)
            report("incline", controller.setIncline(target))
        }
    }

    /** A target change we didn't send came from the treadmill's buttons or the Control screen. */
    private fun onExternalSpeed(active: WorkoutRunState.Active, kmh: Double) {
        if (active.phase != RunPhase.RUNNING || kmh <= 0.0) return
        val commanded = lastCommandedSpeed
        if (commanded != null && near(kmh, commanded)) return
        lastCommandedSpeed = kmh
        segment.adjusted = true
        _state.value = active.copy(speedOverrideKmh = kmh.takeUnless { near(it, active.current.speedKmh) })
    }

    private fun onExternalIncline(active: WorkoutRunState.Active, percent: Double) {
        if (active.phase != RunPhase.RUNNING) return
        val commanded = lastCommandedIncline
        if (commanded != null && near(percent, commanded)) return
        lastCommandedIncline = percent
        segment.adjusted = true
        _state.value = active.copy(inclineOverridePercent = percent.takeUnless { near(it, active.current.inclinePercent) })
    }

    private suspend fun onMachineStatus(active: WorkoutRunState.Active, status: MachineStatus) {
        when (status) {
            MachineStatus.PausedByUser ->
                if (active.phase == RunPhase.RUNNING) pauseRun(active, PauseReason.TREADMILL, sendCommand = false)
            MachineStatus.StartedOrResumedByUser ->
                if (active.phase == RunPhase.PAUSED) resumeRun(active, sendCommand = false)
            MachineStatus.StoppedByUser -> when {
                clock.elapsedMs() < ignoreStopUntil -> Unit
                active.phase == RunPhase.COUNTDOWN -> pauseRun(active, PauseReason.TREADMILL, sendCommand = false)
                else -> finish(EndReason.STOPPED_ON_TREADMILL, sendStop = false)
            }
            MachineStatus.StoppedBySafetyKey -> finish(EndReason.SAFETY_KEY, sendStop = false)
            else -> Unit
        }
    }

    private fun near(a: Double, b: Double) = abs(a - b) < TOLERANCE

    private fun report(what: String, outcome: CommandOutcome) {
        if (outcome != CommandOutcome.Success) Log.w(TAG, "$what -> $outcome")
    }

    internal companion object {
        private const val TAG = "WorkoutEngine"
        const val COUNTDOWN_SEC = 3
        const val PRE_CHANGE_COUNTDOWN = 3
        const val TICK_MS = 250L
        const val ADJUST_DEBOUNCE_MS = 350L
        const val RETRY_DELAY_MS = 1_500L
        const val BELT_STOPPED_PAUSE_MS = 3_000L
        const val STOP_ECHO_GRACE_MS = 5_000L
        const val SAMPLE_INTERVAL_MS = 10_000L
        const val MIN_SAVED_MS = 1_000L
        private const val TOLERANCE = 0.05
        private const val DEFAULT_INCLINE_STEP = 0.5
    }
}
