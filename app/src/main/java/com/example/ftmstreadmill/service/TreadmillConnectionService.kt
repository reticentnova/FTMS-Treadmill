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
import com.example.ftmstreadmill.data.TreadmillController
import com.example.ftmstreadmill.data.TreadmillState
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import java.util.Locale
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

    private var observing = false

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        when (intent?.action) {
            ACTION_STOP_BELT -> lifecycleScope.launch { controller.stop() }
            ACTION_DISCONNECT -> controller.disconnect()
        }

        // startForegroundService() obliges us to call startForeground() even if we're about to stop,
        // otherwise the system crashes the app. observeState() stops us if no session is active.
        val state = controller.connectionState.value
        try {
            ServiceCompat.startForeground(
                this,
                NOTIFICATION_ID,
                buildNotification(state, controller.treadmillState.value),
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
            combine(controller.connectionState, controller.treadmillState) { c, t -> c to t }
                .distinctUntilChanged()
                .collect { (connection, treadmill) ->
                    if (!connection.isSessionActive) {
                        ServiceCompat.stopForeground(this@TreadmillConnectionService, ServiceCompat.STOP_FOREGROUND_REMOVE)
                        stopSelf()
                    } else {
                        notificationManager.notify(NOTIFICATION_ID, buildNotification(connection, treadmill))
                    }
                }
        }
    }

    private fun buildNotification(connection: ConnectionState, treadmill: TreadmillState): Notification {
        val openApp = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val text = when (connection) {
            is ConnectionState.Ready -> treadmill.data.speedKmh
                ?.let { getString(R.string.notification_speed, String.format(Locale.getDefault(), "%.1f", it)) }
                ?: getString(R.string.state_connected)
            is ConnectionState.Reconnecting -> getString(R.string.state_reconnecting)
            is ConnectionState.BluetoothOff -> getString(R.string.state_bluetooth_off)
            else -> getString(R.string.state_connecting)
        }
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_treadmill)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(text)
            .setContentIntent(openApp)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .apply {
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
