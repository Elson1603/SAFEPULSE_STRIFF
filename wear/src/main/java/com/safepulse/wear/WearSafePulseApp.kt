package com.safepulse.wear

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import com.safepulse.wear.data.PhoneCommunicationManager

class WearSafePulseApp : Application() {

    companion object {
        const val CHANNEL_SAFETY = "safety_channel"
        const val CHANNEL_SOS = "sos_channel"
    }

    val communicationManager: PhoneCommunicationManager by lazy {
        PhoneCommunicationManager(this)
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannels()
    }

    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(NotificationManager::class.java)

            val safetyChannel = NotificationChannel(
                CHANNEL_SAFETY,
                "Safety Monitoring",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Ongoing safety monitoring notifications"
            }

            val sosChannel = NotificationChannel(
                CHANNEL_SOS,
                "SOS Alerts",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Emergency SOS notifications"
                enableVibration(true)
                vibrationPattern = longArrayOf(0, 500, 200, 500, 200, 500)
            }

            manager.createNotificationChannel(safetyChannel)
            manager.createNotificationChannel(sosChannel)
        }
    }
}
