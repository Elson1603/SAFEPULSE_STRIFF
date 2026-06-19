package com.safepulse.domain.journey

import com.safepulse.domain.model.LocationData

data class LiveJourneySession(
    val sessionId: String,
    val userId: String,
    val startTime: Long,
    val expectedArrivalTime: Long,
    val destination: String,
    val status: JourneySessionStatus,
    val currentRiskScore: Int,
    val sharedContactIds: Set<Long> = emptySet(),
    val shareToken: String = "",
    val shareLink: String = ""
)

data class JourneyShareToken(
    val token: String,
    val link: String,
    val generatedAt: Long,
    val expiresAt: Long
)

data class JourneyCheckIn(
    val label: String,
    val requestedAt: Long,
    val dueAt: Long,
    val acknowledgedAt: Long? = null,
    val ignored: Boolean = false
)

data class JourneyMonitoringAlert(
    val type: String,
    val reason: String,
    val timestamp: Long,
    val contactCount: Int
)

data class JourneyLiveState(
    val liveSession: LiveJourneySession? = null,
    val currentPhase: JourneyPhase = JourneyPhase.JOURNEY_STARTED,
    val currentRiskScore: Int = 0,
    val lastKnownLocation: LocationData? = null,
    val etaMillis: Long = 0L,
    val transportTransition: String? = null,
    val lastTimelineEvent: String? = null,
    val timeline: List<JourneyEvent> = emptyList(),
    val alerts: List<JourneyMonitoringAlert> = emptyList(),
    val currentCheckIn: JourneyCheckIn? = null,
    val monitoringLevel: Int = 1,
    val shareToken: JourneyShareToken? = null
)
