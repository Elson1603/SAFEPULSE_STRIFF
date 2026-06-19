package com.safepulse

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import com.safepulse.data.db.SafePulseDatabase
import com.safepulse.data.repository.EmergencyContactRepository
import com.safepulse.data.journey.JourneyRepository
import com.safepulse.data.repository.RiskZoneRepository
import com.safepulse.domain.journey.JourneySessionManager
import com.safepulse.domain.transition.TransitionRiskEngine
import com.safepulse.service.JourneyShareManager
import com.safepulse.utils.SafetyConstants
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class SafePulseApplication : Application() {
    
    val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    
    val database: SafePulseDatabase by lazy {
        SafePulseDatabase.getInstance(this)
    }

    val journeyRepository: JourneyRepository by lazy {
        JourneyRepository(database.journeyDao())
    }

    val journeySessionManager: JourneySessionManager by lazy {
        JourneySessionManager.initialize(journeyRepository, applicationScope)
    }

    val journeyShareManager: JourneyShareManager by lazy {
        JourneyShareManager(
            context = this,
            journeySessionManager = journeySessionManager,
            contactRepository = EmergencyContactRepository(database.emergencyContactDao()),
            scope = applicationScope
        )
    }

    val transitionRiskEngine: TransitionRiskEngine by lazy {
        TransitionRiskEngine(RiskZoneRepository(this))
    }
    
    override fun onCreate() {
        super.onCreate()
        instance = this
        journeySessionManager
        journeyShareManager
        transitionRiskEngine
        createNotificationChannels()
        preloadDataIfNeeded()
    }
    
    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val notificationManager = getSystemService(NotificationManager::class.java)
            
            // Safety monitoring channel (low priority, persistent)
            val safetyChannel = NotificationChannel(
                SafetyConstants.CHANNEL_ID_SAFETY,
                getString(R.string.notification_channel_safety),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Ongoing safety monitoring notification"
                setShowBadge(false)
            }
            
            // Emergency alerts channel (high priority)
            val emergencyChannel = NotificationChannel(
                SafetyConstants.CHANNEL_ID_EMERGENCY,
                getString(R.string.notification_channel_emergency),
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Emergency and SOS alerts"
                enableVibration(true)
                vibrationPattern = longArrayOf(0, 500, 200, 500)
                setShowBadge(true)
            }
            
            notificationManager.createNotificationChannel(safetyChannel)
            notificationManager.createNotificationChannel(emergencyChannel)
        }
    }
    
    private fun preloadDataIfNeeded() {
        applicationScope.launch {
            database.preloadSampleDataIfNeeded(this@SafePulseApplication)
        }
    }
    
    companion object {
        lateinit var instance: SafePulseApplication
            private set
    }
}
