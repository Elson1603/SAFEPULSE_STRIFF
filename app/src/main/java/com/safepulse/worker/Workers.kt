package com.safepulse.worker

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.os.Build
import android.provider.Settings
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.work.*
import com.safepulse.SafePulseApplication
import com.safepulse.data.prefs.UserPreferences
import com.safepulse.data.repository.EventLogRepository
import com.safepulse.service.SafetyFeatureManager
import com.safepulse.service.SafetyForegroundService
import com.safepulse.utils.SafetyConstants
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import java.util.concurrent.TimeUnit

/**
 * WorkManager worker for periodic safety checks and maintenance tasks
 */
class SafetyCheckWorker(
    context: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(context, workerParams) {
    
    override suspend fun doWork(): Result {
        try {
            val app = applicationContext as SafePulseApplication
            val db = app.database
            
            val userPreferences = UserPreferences(applicationContext)
            val eventLogRepository = EventLogRepository(db.eventLogDao())
            
            // Check if service should be running
            val settings = userPreferences.userSettingsFlow.first()
            if (settings.serviceEnabled) {
                // Ensure service is running
                SafetyForegroundService.start(applicationContext)
            }
            
            // Clean up old event logs (older than 30 days)
            eventLogRepository.deleteOldEvents(30)
            
            return Result.success()
        } catch (e: Exception) {
            e.printStackTrace()
            return Result.retry()
        }
    }
    
    companion object {
        private const val WORK_NAME = "safety_check_periodic"
        
        /**
         * Schedule periodic safety check work
         */
        fun schedule(context: Context) {
            val constraints = Constraints.Builder()
                .setRequiresBatteryNotLow(true)
                .build()
            
            val workRequest = PeriodicWorkRequestBuilder<SafetyCheckWorker>(
                15, TimeUnit.MINUTES  // Minimum interval for WorkManager
            )
                .setConstraints(constraints)
                .addTag(SafetyConstants.WORK_TAG_SAFETY_CHECK)
                .build()
            
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                workRequest
            )
        }
        
        /**
         * Cancel scheduled work
         */
        fun cancel(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
        }
    }
}

/**
 * Worker for refreshing zone data (can be extended for sync with remote)
 */
class DataRefreshWorker(
    context: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(context, workerParams) {
    
    override suspend fun doWork(): Result {
        // In a full implementation, this would sync with a remote server
        // For the offline-first prototype, we just verify local data integrity
        
        try {
            val app = applicationContext as SafePulseApplication
            
            // Trigger data preload if needed
            app.database.preloadSampleDataIfNeeded(applicationContext)
            
            return Result.success()
        } catch (e: Exception) {
            e.printStackTrace()
            return Result.retry()
        }
    }
    
    companion object {
        private const val WORK_NAME = "data_refresh_periodic"
        
        fun schedule(context: Context) {
            val constraints = Constraints.Builder()
                .setRequiresBatteryNotLow(true)
                .build()
            
            val workRequest = PeriodicWorkRequestBuilder<DataRefreshWorker>(
                1, TimeUnit.DAYS
            )
                .setConstraints(constraints)
                .addTag(SafetyConstants.WORK_TAG_DATA_REFRESH)
                .build()
            
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                workRequest
            )
        }
    }
}

/**
 * One-shot fake call worker. Used by phone and Wear actions to schedule a believable
 * incoming-call notification after a short delay.
 */
class FakeCallWorker(
    context: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(context, workerParams) {

    override suspend fun doWork(): Result {
        showFakeCall(applicationContext)
        return Result.success()
    }

    companion object {
        private const val CHANNEL_ID = "fake_call_channel"
        private const val WORK_NAME_PREFIX = "fake_call_delay_"
        private const val NOTIFICATION_ID = 999

        fun schedule(context: Context, delayMinutes: Int) {
            val safeDelay = delayMinutes.coerceIn(1, 60)
            val request = OneTimeWorkRequestBuilder<FakeCallWorker>()
                .setInitialDelay(safeDelay.toLong(), TimeUnit.MINUTES)
                .build()

            WorkManager.getInstance(context).enqueueUniqueWork(
                WORK_NAME_PREFIX + System.currentTimeMillis(),
                ExistingWorkPolicy.REPLACE,
                request
            )
        }

        fun showFakeCall(context: Context) {
            if (!canPostNotifications(context)) return
            val callerName = SafetyFeatureManager.getInstance(context).getFakeCallerName()

            val notificationManager =
                context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val channel = NotificationChannel(
                    CHANNEL_ID,
                    "Fake Call",
                    NotificationManager.IMPORTANCE_HIGH
                ).apply {
                    description = "Fake incoming call notifications"
                    setSound(
                        Settings.System.DEFAULT_RINGTONE_URI,
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
                            .build()
                    )
                }
                notificationManager.createNotificationChannel(channel)
            }

            val notification = NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.sym_call_incoming)
                .setContentTitle("Incoming call...")
                .setContentText(callerName)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setCategory(NotificationCompat.CATEGORY_CALL)
                .setFullScreenIntent(null, true)
                .setAutoCancel(true)
                .build()

            notificationManager.notify(NOTIFICATION_ID, notification)
        }

        private fun canPostNotifications(context: Context): Boolean {
            return Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
                    ContextCompat.checkSelfPermission(
                        context,
                        Manifest.permission.POST_NOTIFICATIONS
                    ) == PackageManager.PERMISSION_GRANTED
        }
    }
}

/**
 * One-shot check-in worker. If the user does not clear the timer before it fires,
 * SafePulse starts monitoring and triggers a silent SOS.
 */
class SafetyCheckInWorker(
    context: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(context, workerParams) {

    override suspend fun doWork(): Result {
        return try {
            val manager = SafetyFeatureManager.getInstance(applicationContext)
            manager.handleMissedSafetyCheckIn()

            SafetyForegroundService.start(applicationContext)
            delay(1_500)
            SafetyForegroundService.getInstance()?.triggerSilentSOS()

            showMissedCheckInNotification(applicationContext)
            Result.success()
        } catch (e: Exception) {
            Log.e("SafetyCheckInWorker", "Failed to process missed safety check-in", e)
            Result.retry()
        }
    }

    companion object {
        private const val WORK_NAME = "safety_check_in_timer"
        private const val CHANNEL_ID = "safety_check_in_channel"

        fun schedule(context: Context, delayMinutes: Int) {
            val request = OneTimeWorkRequestBuilder<SafetyCheckInWorker>()
                .setInitialDelay(delayMinutes.coerceIn(5, 240).toLong(), TimeUnit.MINUTES)
                .addTag(SafetyConstants.WORK_TAG_SAFETY_CHECK)
                .build()

            WorkManager.getInstance(context).enqueueUniqueWork(
                WORK_NAME,
                ExistingWorkPolicy.REPLACE,
                request
            )
        }

        fun cancel(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
        }

        private fun showMissedCheckInNotification(context: Context) {
            if (!canPostNotifications(context)) return

            val notificationManager =
                context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val channel = NotificationChannel(
                    CHANNEL_ID,
                    "Safety Check-in",
                    NotificationManager.IMPORTANCE_HIGH
                ).apply {
                    description = "Alerts when a safety check-in is missed"
                }
                notificationManager.createNotificationChannel(channel)
            }

            val notification = NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_dialog_alert)
                .setContentTitle("Safety check-in missed")
                .setContentText("SafePulse triggered a silent SOS because the timer expired.")
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setCategory(NotificationCompat.CATEGORY_ALARM)
                .setAutoCancel(true)
                .build()

            notificationManager.notify(SafetyConstants.NOTIFICATION_ID_SAFETY_CHECK_IN, notification)
        }

        private fun canPostNotifications(context: Context): Boolean {
            return Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
                    ContextCompat.checkSelfPermission(
                        context,
                        Manifest.permission.POST_NOTIFICATIONS
                    ) == PackageManager.PERMISSION_GRANTED
        }
    }
}
