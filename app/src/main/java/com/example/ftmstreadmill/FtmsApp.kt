package com.example.ftmstreadmill

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import com.example.ftmstreadmill.data.TreadmillController
import com.example.ftmstreadmill.di.ApplicationScope
import com.example.ftmstreadmill.service.TreadmillConnectionService
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltAndroidApp
class FtmsApp : Application() {

    @Inject lateinit var controller: TreadmillController

    @Inject @ApplicationScope lateinit var appScope: CoroutineScope

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()

        // Keep the process alive with a foreground service for as long as a connection is wanted.
        // The service stops itself when the session ends.
        appScope.launch {
            controller.connectionState
                .map { it.isSessionActive }
                .distinctUntilChanged()
                .filter { it }
                .collect { TreadmillConnectionService.start(this@FtmsApp) }
        }
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            TreadmillConnectionService.CHANNEL_ID,
            getString(R.string.notification_channel_name),
            NotificationManager.IMPORTANCE_LOW,
        ).apply { description = getString(R.string.notification_channel_description) }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }
}
