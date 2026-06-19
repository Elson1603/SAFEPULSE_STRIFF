package com.safepulse.domain.journey

data class JourneySession(
    val sessionId: String,
    val startTime: Long,
    val endTime: Long? = null,
    val status: JourneySessionStatus = JourneySessionStatus.ACTIVE,
    val currentPhase: JourneyPhase = JourneyPhase.JOURNEY_STARTED,
    val riskScore: Int = 0,
    val destination: String? = null
)
