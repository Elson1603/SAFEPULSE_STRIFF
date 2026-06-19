package com.safepulse.data.journey

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.safepulse.domain.journey.JourneyPhase
import com.safepulse.domain.journey.JourneySessionStatus

@Entity(tableName = "journey_sessions")
data class JourneySessionEntity(
    @PrimaryKey
    val sessionId: String,
    val startTime: Long,
    val endTime: Long? = null,
    val status: JourneySessionStatus = JourneySessionStatus.ACTIVE,
    val currentPhase: JourneyPhase = JourneyPhase.JOURNEY_STARTED,
    val riskScore: Int = 0,
    val destination: String? = null
)
