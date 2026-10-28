package com.example.ftmstreadmill.service

import android.app.ForegroundServiceStartNotAllowedException
import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.example.ftmstreadmill.MainActivity
import com.example.ftmstreadmill.R
import com.example.ftmstreadmill.data.ConnectionState
import com.example.ftmstreadmill.data.SettingsRepository
import com.example.ftmstreadmill.data.TreadmillController
import com.example.ftmstreadmill.data.TreadmillState
import com.example.ftmstreadmill.data.defaultImperial
import com.example.ftmstreadmill.ui.common.Units
import com.example.ftmstreadmill.workout.RunPhase
import com.example.ftmstreadmill.workout.WorkoutEngine
import com.example.ftmstreadmill.workout.WorkoutRunState
import com.example.ftmstreadmill.workout.model.SegmentKind
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Foreground service that keeps the process (and therefore the BLE link held by
 * [TreadmillController]) alive while the app is in the background or the screen is off.
 * It owns no connection logic; it only mirrors state into a notification and stops itself
 * once no session is active.
 */
@AndroidEntryPoint
class TreadmillConnectionService : LifecycleService() {

    @Inject lateinit var controller: TreadmillController

    @Inject lateinit var settingsRepository: SettingsRepository

    @Inject lateinit var workoutEngine: WorkoutEngine

    private var observing = false

    /** Latest unit preference; seeded with the locale default until DataStore emits. */
    private var useImperialUnits = defaultImperial()

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        when (intent?.action) {
            ACTION_STOP_BELT -> lifecycleScope.launch { controller.stop() }
            ACTION_DISCONNECT -> controller.disconnect()
            ACTION_PAUSE_WORKOUT -> workoutEngine.pause()
            ACTION_RESUME_WORKOUT -> workoutEngine.resume()
        }

        // startForegroundService() obliges us to call startForeground() even if we're about to stop,
        // otherwise the system crashes the app. observeState() stops us if no session is active.
        val state = controller.connectionState.value
        try {
            ServiceCompat.startForeground(
                this,
                NOTIFICATION_ID,
                buildNotification(state, controller.treadmillState.value, workoutEngine.state.value.toProgress()),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE,
            )
        } catch (e: ForegroundServiceStartNotAllowedException) {
            Log.w(TAG, "Not allowed to start foreground service", e)
            stopSelf()
            return START_NOT_STICKY
        } catch (e: SecurityException) {
            // Missing BLUETOOTH_CONNECT; the connection itself will fail and surface the error.
            Log.w(TAG, "Foreground service type not permitted", e)
            stopSelf()
            return START_NOT_STICKY
        }
        observeState()
        return START_NOT_STICKY
    }

    private fun observeState() {
        if (observing) return
        observing = true
        val notificationManager = getSystemService(NotificationManager::class.java)
        lifecycleScope.launch {
            combine(
                controller.connectionState,
                controller.treadmillState,
                settingsRepository.settings,
                // Engine state ticks four times a second; only whole seconds matter here.
                workoutEngine.state.map { it.toProgress() }.distinctUntilChanged(),
            ) { c, t, s, w -> NotificationInputs(c, t, s.useImperialUnits, w) }
                .distinctUntilChanged()
                .collect { (connection, treadmill, imperial, workout) ->
                    useImperialUnits = imperial
                    if (!connection.isSessionActive) {
                        ServiceCompat.stopForeground(this@TreadmillConnectionService, ServiceCompat.STOP_FOREGROUND_REMOVE)
                        stopSelf()
                    } else {
                        notificationManager.notify(NOTIFICATION_ID, buildNotification(connection, treadmill, workout))
                    }
                }
        }
    }

    private data class NotificationInputs(
        val connection: ConnectionState,
        val treadmill: TreadmillState,
        val imperial: Boolean,
        val workout: WorkoutProgress?,
    )

    /** The parts of a running workout the notification shows. */
    private data class WorkoutProgress(
        val name: String,
        val segmentLabel: String?,
        val kind: SegmentKind,
        val intervalNumber: Int?,
        val intervalCount: Int,
        val secondsLeft: Int,
        val progressPermille: Int,
        val paused: Boolean,
    )

    private fun WorkoutRunState.toProgress(): WorkoutProgress? {
        val active = this as? WorkoutRunState.Active ?: return null
        return WorkoutProgress(
            name = active.workout.name,
            segmentLabel = active.current.name,
            kind = active.current.kind,
            intervalNumber = active.current.intervalNumber,
            intervalCount = active.plan.intervalCount,
            secondsLeft = active.segmentRemainingSec,
            progressPermille = (active.planProgress * 1000).toInt(),
            paused = active.phase == RunPhase.PAUSED,
        )
    }

    private fun workoutText(w: WorkoutProgress): String {
        val segment = when (w.kind) {
            SegmentKind.WARMUP -> getString(R.string.segment_warmup)
            SegmentKind.COOLDOWN -> getString(R.string.segment_cooldown)
            SegmentKind.INTERVAL -> getString(R.string.notification_interval, w.intervalNumber ?: 0, w.intervalCount)
        }
        val name = w.segmentLabel?.let { " · $it" }.orEmpty()
        return if (w.paused) {
            getString(R.string.notification_workout_paused, segment + name)
        } else {
            getString(R.string.notification_workout_left, segment + name, Units.formatDuration(w.secondsLeft))
        }
    }

    private fun buildNotification(connection: ConnectionState, treadmill: TreadmillState, workout: WorkoutProgress?): Notification {
        val openApp = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val text = when (connection) {
            // Treadmill data is always km/h; convert to the user's units like the in-app display does.
            is ConnectionState.Ready -> treadmill.data.speedKmh
                ?.let {
                    getString(
                        R.string.notification_speed,
                        Units.formatSpeed(it, useImperialUnits),
                        getString(if (useImperialUnits) R.string.unit_mph else R.string.unit_kmh),
                    )
                }
                ?: getString(R.string.state_connected)
            is ConnectionState.Reconnecting -> getString(R.string.state_reconnecting)
            is ConnectionState.BluetoothOff -> getString(R.string.state_bluetooth_off)
            else -> getString(R.string.state_connecting)
        }
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_treadmill)
            .setContentTitle(workout?.name ?: getString(R.string.app_name))
            .setContentText(workout?.let(::workoutText) ?: text)
            .setContentIntent(openApp)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .apply {
                if (workout != null) {
                    setProgress(1000, workout.progressPermille, false)
                    setSubText(text)
                    if (workout.paused) {
                        addAction(0, getString(R.string.resume), serviceIntent(ACTION_RESUME_WORKOUT, 3))
                    } else {
                        addAction(0, getString(R.string.pause), serviceIntent(ACTION_PAUSE_WORKOUT, 4))
                    }
                }
                if (connection is ConnectionState.Ready && treadmill.isBeltMoving) {
                    addAction(0, getString(R.string.action_stop), serviceIntent(ACTION_STOP_BELT, 1))
                }
            }
            .addAction(0, getString(R.string.action_disconnect), serviceIntent(ACTION_DISCONNECT, 2))
            .build()
    }

    private fun serviceIntent(action: String, requestCode: Int): PendingIntent = PendingIntent.getService(
        this, requestCode,
        Intent(this, TreadmillConnectionService::class.java).setAction(action),
        PendingIntent.FLAG_IMMUTABLE,
    )

    companion object {
        private const val TAG = "ConnectionService"
        const val CHANNEL_ID = "treadmill_connection"
        private const val NOTIFICATION_ID = 1
        private const val ACTION_STOP_BELT = "com.example.ftmstreadmill.action.STOP_BELT"
        private const val ACTION_DISCONNECT = "com.example.ftmstreadmill.action.DISCONNECT"
        private const val ACTION_PAUSE_WORKOUT = "com.example.ftmstreadmill.action.PAUSE_WORKOUT"
        private const val ACTION_RESUME_WORKOUT = "com.example.ftmstreadmill.action.RESUME_WORKOUT"

        fun start(context: Context) {
            try {
                context.startForegroundService(Intent(context, TreadmillConnectionService::class.java))
            } catch (e: ForegroundServiceStartNotAllowedException) {
                // App is in the background; the connection still works while the process lives.
                Log.w(TAG, "Could not start foreground service from background", e)
            } catch (e: IllegalStateException) {
                Log.w(TAG, "Could not start foreground service", e)
            }
        }
    }
}
