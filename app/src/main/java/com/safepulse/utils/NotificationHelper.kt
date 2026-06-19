package com.safepulse.utils

import android.Manifest
import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.safepulse.MainActivity
import com.safepulse.R
import com.safepulse.domain.journey.JourneyPhase
import com.safepulse.domain.model.EventType
import com.safepulse.domain.model.RiskLevel
import com.safepulse.domain.transition.TransitionRiskState
import com.safepulse.service.SafetyForegroundService
import com.safepulse.service.SOSCancelReceiver

/**
 * Helper for creating and managing notifications
 */
object NotificationHelper {
    
    /**
     * Create foreground service notification
     */
    fun createForegroundNotification(context: Context): Notification {
        val intent = Intent(context, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            context, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        
        return NotificationCompat.Builder(context, SafetyConstants.CHANNEL_ID_SAFETY)
            .setContentTitle(context.getString(R.string.notification_title_monitoring))
            .setContentText(context.getString(R.string.notification_text_monitoring))
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setOngoing(true)
            .setContentIntent(pendingIntent)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
    }

    fun showDefaultForegroundNotification(context: Context) {
        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE)
            as NotificationManager
        notificationManager.notify(
            SafetyConstants.NOTIFICATION_ID_FOREGROUND,
            createForegroundNotification(context)
        )
    }

    fun createJourneyForegroundNotification(
        context: Context,
        phase: JourneyPhase,
        riskScore: Int
    ): Notification {
        val intent = Intent(context, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            context,
            6,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val phaseLabel = formatJourneyPhase(phase)
        val content = "Current Phase: $phaseLabel\nRisk Score: ${riskScore.coerceIn(0, 100)}"

        return NotificationCompat.Builder(context, SafetyConstants.CHANNEL_ID_SAFETY)
            .setContentTitle("SafePulse Journey Active")
            .setContentText("Current Phase: $phaseLabel")
            .setStyle(NotificationCompat.BigTextStyle().bigText(content))
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setOngoing(true)
            .setContentIntent(pendingIntent)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
    }

    fun showJourneyForegroundNotification(
        context: Context,
        phase: JourneyPhase,
        riskScore: Int
    ) {
        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE)
            as NotificationManager
        notificationManager.notify(
            SafetyConstants.NOTIFICATION_ID_FOREGROUND,
            createJourneyForegroundNotification(context, phase, riskScore)
        )
    }

    private fun formatJourneyPhase(phase: JourneyPhase): String {
        return when (phase) {
            JourneyPhase.JOURNEY_STARTED -> "Journey Started"
            JourneyPhase.WALKING -> "Walking"
            JourneyPhase.IN_VEHICLE -> "In Vehicle"
            JourneyPhase.TRAIN_TRANSIT -> "Train Transit"
            JourneyPhase.STATION_EXIT -> "Station Exit"
            JourneyPhase.LAST_MILE_WALK -> "Last Mile Walk"
            JourneyPhase.ARRIVED -> "Arrived"
            JourneyPhase.EMERGENCY -> "Emergency"
        }
    }
    
    /**
     * Show emergency countdown notification
     */
    fun showEmergencyCountdownNotification(
        context: Context,
        eventType: EventType,
        secondsRemaining: Int
    ) {
        val cancelIntent = Intent(context, SOSCancelReceiver::class.java).apply {
            action = SafetyConstants.ACTION_CANCEL_SOS
        }
        val cancelPendingIntent = PendingIntent.getBroadcast(
            context, 0, cancelIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        
        val openIntent = Intent(context, MainActivity::class.java)
        val openPendingIntent = PendingIntent.getActivity(
            context, 1, openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        
        val eventText = when (eventType) {
            EventType.ROAD_ACCIDENT -> "Accident detected"
            EventType.FALL -> "Fall detected"
            EventType.POSSIBLE_ASSAULT -> "Possible assault detected"
            EventType.INACTIVITY_ALERT -> "Inactivity detected"
            EventType.VOICE_TRIGGER -> "Voice emergency detected"
            EventType.MANUAL_SOS -> "Manual SOS triggered"
            EventType.HIGH_RISK_ZONE -> "High risk zone alert"
        }
        
        val notification = NotificationCompat.Builder(context, SafetyConstants.CHANNEL_ID_EMERGENCY)
            .setContentTitle("⚠️ $eventText")
            .setContentText("Sending SOS in $secondsRemaining seconds. Tap to cancel.")
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setOngoing(true)
            .setAutoCancel(false)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setContentIntent(openPendingIntent)
            .addAction(
                android.R.drawable.ic_menu_close_clear_cancel,
                context.getString(R.string.action_cancel),
                cancelPendingIntent
            )
            .setVibrate(longArrayOf(0, 500, 200, 500))
            .build()
        
        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) 
            as NotificationManager
        notificationManager.notify(SafetyConstants.NOTIFICATION_ID_EMERGENCY, notification)
    }
    
    /**
     * Cancel emergency notification
     */
    fun cancelEmergencyNotification(context: Context) {
        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) 
            as NotificationManager
        notificationManager.cancel(SafetyConstants.NOTIFICATION_ID_EMERGENCY)
    }
    
    /**
     * Show high risk zone alert
     */
    fun showRiskAlertNotification(context: Context, riskLevel: RiskLevel) {
        if (riskLevel != RiskLevel.HIGH) return
        
        val intent = Intent(context, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            context, 2, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        
        val notification = NotificationCompat.Builder(context, SafetyConstants.CHANNEL_ID_SAFETY)
            .setContentTitle(context.getString(R.string.notification_title_high_risk))
            .setContentText(context.getString(R.string.notification_text_high_risk))
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(pendingIntent)
            .build()
        
        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) 
            as NotificationManager
        notificationManager.notify(SafetyConstants.NOTIFICATION_ID_RISK_ALERT, notification)
    }
    
    /**
     * Cancel risk alert notification
     */
    fun cancelRiskAlertNotification(context: Context) {
        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) 
            as NotificationManager
        notificationManager.cancel(SafetyConstants.NOTIFICATION_ID_RISK_ALERT)
    }

    fun showTransitionRiskAlertNotification(
        context: Context,
        state: TransitionRiskState
    ) {
        val openPendingIntent = PendingIntent.getActivity(
            context,
            7,
            Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val checkInPendingIntent = PendingIntent.getActivity(
            context,
            8,
            Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val shareText = "SafePulse check-in: elevated commute transition risk detected. Risk score ${state.currentRiskScore}. ${
            state.reasons.take(3).joinToString(separator = ", ")
        }"
        val sharePendingIntent = PendingIntent.getActivity(
            context,
            9,
            Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_TEXT, shareText)
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val sosPendingIntent = PendingIntent.getService(
            context,
            10,
            Intent(context, SafetyForegroundService::class.java).apply {
                action = SafetyConstants.ACTION_TRIGGER_SOS
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val reason = state.reasonDetails
            .take(3)
            .joinToString(separator = ", ") { it.label }
            .ifBlank { "Transition risk increased" }
        val content = "$reason. Risk score ${state.currentRiskScore}."

        val notification = NotificationCompat.Builder(context, SafetyConstants.CHANNEL_ID_EMERGENCY)
            .setContentTitle("Elevated Risk Detected")
            .setContentText(reason)
            .setStyle(NotificationCompat.BigTextStyle().bigText(content))
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setContentIntent(openPendingIntent)
            .addAction(android.R.drawable.ic_menu_view, "Check In", checkInPendingIntent)
            .addAction(android.R.drawable.ic_menu_share, "Share Status", sharePendingIntent)
            .addAction(android.R.drawable.ic_dialog_alert, "Trigger SOS", sosPendingIntent)
            .setVibrate(longArrayOf(0, 400, 150, 400))
            .build()

        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE)
            as NotificationManager
        notificationManager.notify(SafetyConstants.NOTIFICATION_ID_TRANSITION_RISK, notification)
    }

    /**
     * Show a high-priority fallback when Android blocks direct background dialing
     * or when CALL_PHONE is not granted. Tapping the notification opens the call UI.
     */
    fun showEmergencyCallActionNotification(
        context: Context,
        phoneNumber: String,
        contactName: String,
        reason: String
    ) {
        val canCallDirectly = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.CALL_PHONE
        ) == PackageManager.PERMISSION_GRANTED

        val callIntent = Intent(if (canCallDirectly) Intent.ACTION_CALL else Intent.ACTION_DIAL).apply {
            data = Uri.fromParts("tel", phoneNumber, null)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        val callPendingIntent = PendingIntent.getActivity(
            context,
            4,
            callIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val openPendingIntent = PendingIntent.getActivity(
            context,
            5,
            Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(context, SafetyConstants.CHANNEL_ID_EMERGENCY)
            .setContentTitle("Emergency call needed")
            .setContentText("Tap to call $contactName. $reason")
            .setStyle(
                NotificationCompat.BigTextStyle()
                    .bigText("Tap Call now to contact $contactName at $phoneNumber. $reason")
            )
            .setSmallIcon(android.R.drawable.sym_call_outgoing)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setContentIntent(callPendingIntent)
            .setFullScreenIntent(callPendingIntent, true)
            .addAction(android.R.drawable.sym_call_outgoing, "Call now", callPendingIntent)
            .addAction(android.R.drawable.ic_menu_view, "Open SafePulse", openPendingIntent)
            .setVibrate(longArrayOf(0, 500, 200, 500, 200, 500))
            .build()

        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE)
            as NotificationManager
        notificationManager.notify(SafetyConstants.NOTIFICATION_ID_EMERGENCY_CALL, notification)
    }
}
