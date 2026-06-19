package com.safepulse.domain.journey

data class JourneyEvent(
    val id: Long = 0,
    val sessionId: String,
    val timestamp: Long,
    val eventType: String,
    val description: String
)

object JourneyEventType {
    const val JOURNEY_STARTED = "Journey Started"
    const val SAFETY_MONITORING_ACTIVATED = "Safety Monitoring Activated"
    const val ACTIVITY_RECOGNITION_STARTED = "Activity Recognition Started"
    const val WALKING_DETECTED = "Walking Detected"
    const val VEHICLE_DETECTED = "Vehicle Detected"
    const val VEHICLE_EXITED = "Vehicle Exited"
    const val TRAIN_TRANSIT = "Train Transit"
    const val STATION_EXIT = "Station Exit"
    const val LAST_MILE_WALK = "Last Mile Walk Started"
    const val HIGH_RISK_TRANSITION_ZONE_ENTERED = "High Risk Transition Zone Entered"
    const val HIGH_RISK_ZONE_ENTERED = "High Risk Zone Entered"
    const val ELEVATED_RISK_DETECTED = "Elevated Risk Detected"
    const val CHECK_IN_PROMPTED = "Check-In Prompted"
    const val COMPANION_ALERT_SENT = "Companion Alert Sent"
    const val UNEXPECTED_STOP_DETECTED = "Unexpected Stop Detected"
    const val ROUTE_CHANGED = "Route Changed"
    const val EMERGENCY_TRIGGERED = "Emergency Triggered"
    const val EMERGENCY_MODE_ACTIVATED = "Emergency Mode Activated"
    const val EMERGENCY_ALERT_SENT = "Emergency Alert Sent"
    const val LAST_KNOWN_LOCATION_SHARED = "Last Known Location Shared"
    const val DESTINATION_REACHED = "Destination Reached"
    const val JOURNEY_COMPLETED = "Journey Completed"
    const val SAFE_ARRIVAL_UPDATE_SENT = "Safe Arrival Update Sent"
    const val JOURNEY_CANCELLED = "Journey Cancelled"
}

data class JourneySummary(
    val sessionId: String,
    val startedAt: Long,
    val endedAt: Long,
    val durationMillis: Long,
    val maxRiskScore: Int,
    val eventCount: Int,
    val finalStatus: JourneySessionStatus,
    val destination: String?,
    val transportTransitions: List<String> = emptyList(),
    val riskEvents: List<String> = emptyList(),
    val sosTriggered: Boolean = false,
    val lastKnownSafeCheckpoint: String? = null
)

data class JourneySessionState(
    val activeSession: JourneySession? = null,
    val events: List<JourneyEvent> = emptyList(),
    val latestSummary: JourneySummary? = null
)
