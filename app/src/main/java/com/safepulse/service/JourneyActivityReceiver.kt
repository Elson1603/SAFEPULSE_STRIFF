package com.safepulse.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.google.android.gms.location.ActivityRecognitionResult
import com.google.android.gms.location.DetectedActivity
import com.safepulse.domain.journey.JourneyActivityType
import com.safepulse.domain.journey.JourneyActivityUpdate

class JourneyActivityReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (!ActivityRecognitionResult.hasResult(intent)) return

        val result = ActivityRecognitionResult.extractResult(intent) ?: return
        val activity = result.mostProbableActivity ?: return
        val update = JourneyActivityUpdate(
            type = activity.toJourneyActivityType(),
            confidence = activity.confidence,
            timestamp = result.time
        )

        JourneyActivityRecognitionBus.emit(update)
    }

    private fun DetectedActivity.toJourneyActivityType(): JourneyActivityType {
        return when (type) {
            DetectedActivity.WALKING,
            DetectedActivity.ON_FOOT -> JourneyActivityType.WALKING
            DetectedActivity.STILL -> JourneyActivityType.STILL
            DetectedActivity.IN_VEHICLE -> JourneyActivityType.IN_VEHICLE
            else -> JourneyActivityType.UNKNOWN
        }
    }
}
