package com.safepulse.data.journey

import com.safepulse.domain.journey.JourneyEvent
import com.safepulse.domain.journey.JourneyPhase
import com.safepulse.domain.journey.JourneySession
import com.safepulse.domain.journey.JourneySessionStatus
import com.safepulse.domain.journey.TransitionEvent
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class JourneyRepository(private val dao: JourneyDao) {
    fun observeActiveSession(): Flow<JourneySession?> {
        return dao.observeActiveSession().map { it?.toDomain() }
    }

    suspend fun getActiveSession(): JourneySession? = dao.getActiveSession()?.toDomain()

    suspend fun getSession(sessionId: String): JourneySession? = dao.getSession(sessionId)?.toDomain()

    suspend fun insertSession(session: JourneySession) {
        dao.insertSession(session.toEntity())
    }

    suspend fun updateSession(session: JourneySession) {
        dao.updateSession(session.toEntity())
    }

    suspend fun updateSessionState(sessionId: String, phase: JourneyPhase, riskScore: Int) {
        dao.updateSessionState(sessionId, phase, riskScore.coerceIn(0, 100))
    }

    suspend fun finishSession(
        sessionId: String,
        status: JourneySessionStatus,
        endTime: Long,
        phase: JourneyPhase
    ) {
        dao.finishSession(sessionId, status, endTime, phase)
    }

    fun observeEvents(sessionId: String): Flow<List<JourneyEvent>> {
        return dao.observeEvents(sessionId).map { events -> events.map { it.toDomain() } }
    }

    suspend fun getEvents(sessionId: String): List<JourneyEvent> {
        return dao.getEvents(sessionId).map { it.toDomain() }
    }

    suspend fun getLatestEvent(sessionId: String): JourneyEvent? {
        return dao.getLatestEvent(sessionId)?.toDomain()
    }

    suspend fun insertEvent(event: JourneyEvent): Long {
        return dao.insertEvent(event.toEntity())
    }

    fun observeRecentTransitionEvents(limit: Int = 100): Flow<List<TransitionEvent>> {
        return dao.observeRecentTransitionEvents(limit).map { events ->
            events.map { it.toDomain() }
        }
    }

    suspend fun getLatestTransitionEvent(): TransitionEvent? {
        return dao.getLatestTransitionEvent()?.toDomain()
    }

    suspend fun insertTransitionEvent(event: TransitionEvent): Long {
        return dao.insertTransitionEvent(event.toEntity())
    }
}

private fun JourneySessionEntity.toDomain(): JourneySession {
    return JourneySession(
        sessionId = sessionId,
        startTime = startTime,
        endTime = endTime,
        status = status,
        currentPhase = currentPhase,
        riskScore = riskScore,
        destination = destination
    )
}

private fun JourneySession.toEntity(): JourneySessionEntity {
    return JourneySessionEntity(
        sessionId = sessionId,
        startTime = startTime,
        endTime = endTime,
        status = status,
        currentPhase = currentPhase,
        riskScore = riskScore,
        destination = destination
    )
}

private fun JourneyEventEntity.toDomain(): JourneyEvent {
    return JourneyEvent(
        id = id,
        sessionId = sessionId,
        timestamp = timestamp,
        eventType = eventType,
        description = description
    )
}

private fun JourneyEvent.toEntity(): JourneyEventEntity {
    return JourneyEventEntity(
        id = id,
        sessionId = sessionId,
        timestamp = timestamp,
        eventType = eventType,
        description = description
    )
}

private fun TransitionEventEntity.toDomain(): TransitionEvent {
    return TransitionEvent(
        id = id,
        timestamp = timestamp,
        transitionType = transitionType,
        latitude = latitude,
        longitude = longitude,
        confidence = confidence,
        description = description
    )
}

private fun TransitionEvent.toEntity(): TransitionEventEntity {
    return TransitionEventEntity(
        id = id,
        timestamp = timestamp,
        transitionType = transitionType,
        latitude = latitude,
        longitude = longitude,
        confidence = confidence,
        description = description
    )
}
