package com.safepulse.domain.journey

import com.safepulse.data.journey.JourneyRepository
import com.safepulse.domain.transition.TransitionRiskState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.UUID

@OptIn(ExperimentalCoroutinesApi::class)
class JourneySessionManager private constructor(
    private val repository: JourneyRepository,
    private val scope: CoroutineScope
) {
    private val mutex = Mutex()
    private val _state = MutableStateFlow(JourneySessionState())
    val state: StateFlow<JourneySessionState> = _state.asStateFlow()
    private val maxRiskScoresBySession = mutableMapOf<String, Int>()

    val activeSession: StateFlow<JourneySession?> = repository.observeActiveSession()
        .stateIn(scope, SharingStarted.Eagerly, null)

    init {
        scope.launch {
            repository.observeActiveSession()
                .flatMapLatest { session ->
                    if (session == null) {
                        flowOf(
                            JourneySessionState(
                                events = _state.value.events,
                                latestSummary = _state.value.latestSummary
                            )
                        )
                    } else {
                        repository.observeEvents(session.sessionId).map { events ->
                            JourneySessionState(
                                activeSession = session,
                                events = events,
                                latestSummary = _state.value.latestSummary
                            )
                        }
                    }
                }
                .collect { _state.value = it }
        }
    }

    suspend fun startJourney(destination: String? = null): JourneySession = mutex.withLock {
        repository.getActiveSession()?.let { return@withLock it }

        val session = JourneySession(
            sessionId = UUID.randomUUID().toString(),
            startTime = System.currentTimeMillis(),
            destination = destination?.trim()?.takeIf { it.isNotEmpty() }
        )
        repository.insertSession(session)
        maxRiskScoresBySession[session.sessionId] = session.riskScore
        appendEventIfChanged(
            sessionId = session.sessionId,
            eventType = JourneyEventType.JOURNEY_STARTED,
            description = session.destination?.let { "Journey started toward $it" } ?: "Journey started"
        )
        appendEventIfChanged(
            sessionId = session.sessionId,
            eventType = JourneyEventType.SAFETY_MONITORING_ACTIVATED,
            description = "Location, sensors, and risk engine are active"
        )
        appendEventIfChanged(
            sessionId = session.sessionId,
            eventType = JourneyEventType.ACTIVITY_RECOGNITION_STARTED,
            description = "Activity recognition started for walking, vehicle, train, still, and transition signals"
        )
        session
    }

    suspend fun endJourney(
        status: JourneySessionStatus = JourneySessionStatus.COMPLETED
    ): JourneySummary? = mutex.withLock {
        val session = repository.getActiveSession() ?: return@withLock null
        val endTime = System.currentTimeMillis()
        val finalPhase = when (status) {
            JourneySessionStatus.COMPLETED -> JourneyPhase.ARRIVED
            JourneySessionStatus.CANCELLED -> session.currentPhase
            JourneySessionStatus.ACTIVE -> session.currentPhase
        }

        val eventType = if (status == JourneySessionStatus.COMPLETED) {
            JourneyEventType.DESTINATION_REACHED
        } else {
            JourneyEventType.JOURNEY_CANCELLED
        }
        appendEventIfChanged(
            sessionId = session.sessionId,
            eventType = eventType,
            description = if (status == JourneySessionStatus.COMPLETED) {
                "Destination reached"
            } else {
                "Journey cancelled"
            }
        )
        if (status == JourneySessionStatus.COMPLETED) {
            appendEventIfChanged(
                sessionId = session.sessionId,
                eventType = JourneyEventType.JOURNEY_COMPLETED,
                description = "Monitoring session ended and journey summary generated"
            )
        }

        repository.finishSession(
            sessionId = session.sessionId,
            status = status,
            endTime = endTime,
            phase = finalPhase
        )

        val events = repository.getEvents(session.sessionId)
        val summary = JourneySummary(
            sessionId = session.sessionId,
            startedAt = session.startTime,
            endedAt = endTime,
            durationMillis = endTime - session.startTime,
            maxRiskScore = maxOf(
                session.riskScore,
                _state.value.activeSession?.riskScore ?: 0,
                maxRiskScoresBySession[session.sessionId] ?: 0
            ),
            eventCount = events.size,
            finalStatus = status,
            destination = session.destination,
            transportTransitions = transportTransitionsFrom(events),
            riskEvents = riskEventsFrom(events),
            sosTriggered = events.any { it.eventType in SOS_EVENT_TYPES },
            lastKnownSafeCheckpoint = lastKnownSafeCheckpointFrom(events)
        )
        maxRiskScoresBySession.remove(session.sessionId)
        _state.value = JourneySessionState(events = events, latestSummary = summary)
        summary
    }

    suspend fun updateCurrentPhase(phase: JourneyPhase) = mutex.withLock {
        val session = repository.getActiveSession() ?: return@withLock
        if (session.currentPhase == phase) return@withLock

        repository.updateSessionState(session.sessionId, phase, session.riskScore)
        appendEventIfChanged(
            sessionId = session.sessionId,
            eventType = eventTypeForPhase(phase),
            description = descriptionForPhase(phase)
        )
    }

    suspend fun updateFromActivity(update: JourneyActivityUpdate) {
        val currentPhase = repository.getActiveSession()?.currentPhase ?: return
        val nextPhase = JourneyActivityPhaseMapper.map(update, currentPhase) ?: return
        updateCurrentPhase(nextPhase)
    }

    suspend fun updateRiskScore(riskScore: Int) = mutex.withLock {
        val session = repository.getActiveSession() ?: return@withLock
        val nextRiskScore = riskScore.coerceIn(0, 100)
        val wasHighRisk = session.riskScore >= HIGH_RISK_EVENT_THRESHOLD
        val isHighRisk = nextRiskScore >= HIGH_RISK_EVENT_THRESHOLD

        if (session.riskScore != nextRiskScore) {
            repository.updateSessionState(session.sessionId, session.currentPhase, nextRiskScore)
            maxRiskScoresBySession[session.sessionId] = maxOf(
                maxRiskScoresBySession[session.sessionId] ?: session.riskScore,
                nextRiskScore
            )
        }

        if (!wasHighRisk && isHighRisk) {
            appendEventIfChanged(
                sessionId = session.sessionId,
                eventType = JourneyEventType.ELEVATED_RISK_DETECTED,
                description = "Risk score increased to $nextRiskScore"
            )
            appendEventIfChanged(
                sessionId = session.sessionId,
                eventType = JourneyEventType.CHECK_IN_PROMPTED,
                description = "Prompt check-in because journey risk crossed the elevated threshold"
            )
        }
    }

    suspend fun appendJourneyEvent(eventType: String, description: String) {
        val session = repository.getActiveSession() ?: return
        appendEventIfChanged(session.sessionId, eventType, description)
    }

    suspend fun recordTransitionEvent(event: TransitionEvent) = mutex.withLock {
        val session = repository.getActiveSession() ?: return@withLock
        val latestTransition = repository.getLatestTransitionEvent()
        if (latestTransition?.transitionType == event.transitionType &&
            latestTransition.description == event.description &&
            event.timestamp - latestTransition.timestamp < TRANSITION_DUPLICATE_WINDOW_MS
        ) {
            return@withLock
        }

        repository.insertTransitionEvent(event)

        val phase = phaseForTransition(event.transitionType)
        val boostedRiskScore = (session.riskScore + riskBoostForTransition(event.transitionType))
            .coerceIn(0, 100)

        if (phase != null || boostedRiskScore != session.riskScore) {
            repository.updateSessionState(
                sessionId = session.sessionId,
                phase = phase ?: session.currentPhase,
                riskScore = boostedRiskScore
            )
            maxRiskScoresBySession[session.sessionId] = maxOf(
                maxRiskScoresBySession[session.sessionId] ?: session.riskScore,
                boostedRiskScore
            )
        }

        appendEventIfChanged(
            sessionId = session.sessionId,
            eventType = titleForTransition(event.transitionType),
            description = "Confidence ${(event.confidence * 100).toInt()}%. ${event.description}"
        )

        if (event.transitionType == TransitionType.HIGH_RISK_ZONE_ENTERED) {
            appendEventIfChanged(
                sessionId = session.sessionId,
                eventType = JourneyEventType.HIGH_RISK_TRANSITION_ZONE_ENTERED,
                description = event.description
            )
        }
    }

    suspend fun recordTransitionRiskState(riskState: TransitionRiskState) = mutex.withLock {
        val session = repository.getActiveSession() ?: return@withLock
        val boostedRiskScore = maxOf(session.riskScore, riskState.currentRiskScore).coerceIn(0, 100)
        if (boostedRiskScore != session.riskScore) {
            repository.updateSessionState(
                sessionId = session.sessionId,
                phase = session.currentPhase,
                riskScore = boostedRiskScore
            )
            maxRiskScoresBySession[session.sessionId] = maxOf(
                maxRiskScoresBySession[session.sessionId] ?: session.riskScore,
                boostedRiskScore
            )
        }

        appendEventIfChanged(
            sessionId = session.sessionId,
            eventType = "Transition Risk Increased",
            description = "${formatTransitionZone(riskState.transitionZoneType)} risk reached ${riskState.currentRiskScore}. ${
                riskState.reasons.take(4).joinToString(separator = "; ")
            }"
        )

        if (riskState.currentRiskScore >= HIGH_RISK_EVENT_THRESHOLD) {
            appendEventIfChanged(
                sessionId = session.sessionId,
                eventType = JourneyEventType.ELEVATED_RISK_DETECTED,
                description = "Risk score reached ${riskState.currentRiskScore}"
            )
            appendEventIfChanged(
                sessionId = session.sessionId,
                eventType = JourneyEventType.CHECK_IN_PROMPTED,
                description = "Prompt check-in because transition risk is elevated"
            )
        }
    }

    private suspend fun appendEventIfChanged(
        sessionId: String,
        eventType: String,
        description: String
    ): Long? {
        val latest = repository.getLatestEvent(sessionId)
        if (latest?.eventType == eventType && latest.description == description) {
            return null
        }

        return repository.insertEvent(
            JourneyEvent(
                sessionId = sessionId,
                timestamp = System.currentTimeMillis(),
                eventType = eventType,
                description = description
            )
        )
    }

    private fun eventTypeForPhase(phase: JourneyPhase): String {
        return when (phase) {
            JourneyPhase.JOURNEY_STARTED -> JourneyEventType.JOURNEY_STARTED
            JourneyPhase.WALKING -> JourneyEventType.WALKING_DETECTED
            JourneyPhase.IN_VEHICLE -> JourneyEventType.VEHICLE_DETECTED
            JourneyPhase.TRAIN_TRANSIT -> JourneyEventType.TRAIN_TRANSIT
            JourneyPhase.STATION_EXIT -> JourneyEventType.STATION_EXIT
            JourneyPhase.LAST_MILE_WALK -> JourneyEventType.LAST_MILE_WALK
            JourneyPhase.ARRIVED -> JourneyEventType.DESTINATION_REACHED
            JourneyPhase.EMERGENCY -> JourneyEventType.EMERGENCY_TRIGGERED
        }
    }

    private fun descriptionForPhase(phase: JourneyPhase): String {
        return when (phase) {
            JourneyPhase.JOURNEY_STARTED -> "Journey started"
            JourneyPhase.WALKING -> "Walking detected"
            JourneyPhase.IN_VEHICLE -> "Entered vehicle"
            JourneyPhase.TRAIN_TRANSIT -> "Train transit detected"
            JourneyPhase.STATION_EXIT -> "Vehicle stopped or station exit detected"
            JourneyPhase.LAST_MILE_WALK -> "Last mile walk started"
            JourneyPhase.ARRIVED -> "Destination reached"
            JourneyPhase.EMERGENCY -> "Emergency triggered during journey"
        }
    }

    private fun phaseForTransition(type: TransitionType): JourneyPhase? {
        return when (type) {
            TransitionType.JOURNEY_STARTED -> JourneyPhase.JOURNEY_STARTED
            TransitionType.WALKING_STARTED -> JourneyPhase.WALKING
            TransitionType.VEHICLE_ENTERED,
            TransitionType.CAB_PICKUP_DETECTED -> JourneyPhase.IN_VEHICLE
            TransitionType.TRAIN_LIKELY_DETECTED -> JourneyPhase.TRAIN_TRANSIT
            TransitionType.STATION_EXIT_DETECTED,
            TransitionType.CAB_DROP_DETECTED -> JourneyPhase.STATION_EXIT
            TransitionType.LAST_MILE_WALK_STARTED -> JourneyPhase.LAST_MILE_WALK
            TransitionType.DESTINATION_REACHED -> JourneyPhase.ARRIVED
            TransitionType.VEHICLE_EXITED,
            TransitionType.UNEXPECTED_STOP,
            TransitionType.WALKING_STOPPED,
            TransitionType.HIGH_RISK_ZONE_ENTERED,
            TransitionType.HIGH_RISK_ZONE_EXITED -> null
        }
    }

    private fun riskBoostForTransition(type: TransitionType): Int {
        return when (type) {
            TransitionType.STATION_EXIT_DETECTED -> 20
            TransitionType.LAST_MILE_WALK_STARTED -> 15
            TransitionType.HIGH_RISK_ZONE_ENTERED -> 25
            TransitionType.UNEXPECTED_STOP -> 30
            else -> 0
        }
    }

    private fun titleForTransition(type: TransitionType): String {
        return when (type) {
            TransitionType.JOURNEY_STARTED -> "Journey Started"
            TransitionType.WALKING_STARTED -> "Walking Started"
            TransitionType.WALKING_STOPPED -> "Walking Stopped"
            TransitionType.VEHICLE_ENTERED -> "Vehicle Entered"
            TransitionType.VEHICLE_EXITED -> JourneyEventType.VEHICLE_EXITED
            TransitionType.TRAIN_LIKELY_DETECTED -> "Train Likely Detected"
            TransitionType.STATION_EXIT_DETECTED -> "Station Exit Detected"
            TransitionType.CAB_PICKUP_DETECTED -> "Cab Pickup Detected"
            TransitionType.CAB_DROP_DETECTED -> "Cab Drop Detected"
            TransitionType.LAST_MILE_WALK_STARTED -> "Last Mile Walk Started"
            TransitionType.HIGH_RISK_ZONE_ENTERED -> "High Risk Zone Entered"
            TransitionType.HIGH_RISK_ZONE_EXITED -> "High Risk Zone Exited"
            TransitionType.UNEXPECTED_STOP -> JourneyEventType.UNEXPECTED_STOP_DETECTED
            TransitionType.DESTINATION_REACHED -> "Destination Reached"
        }
    }

    private fun transportTransitionsFrom(events: List<JourneyEvent>): List<String> {
        return events
            .filter { it.eventType in TRANSPORT_EVENT_TYPES }
            .map { it.eventType }
            .distinct()
    }

    private fun riskEventsFrom(events: List<JourneyEvent>): List<String> {
        return events
            .filter { it.eventType in RISK_EVENT_TYPES }
            .map { event -> "${event.eventType}: ${event.description}" }
    }

    private fun lastKnownSafeCheckpointFrom(events: List<JourneyEvent>): String? {
        return events.lastOrNull { event ->
            event.eventType in SAFE_CHECKPOINT_EVENT_TYPES
        }?.let { event -> "${event.eventType} at ${event.timestamp}" }
    }

    private fun formatTransitionZone(zoneType: com.safepulse.domain.transition.TransitionZoneType): String {
        return zoneType.name.lowercase()
            .split("_")
            .joinToString(" ") { word -> word.replaceFirstChar { it.titlecase() } }
    }

    companion object {
        private const val HIGH_RISK_EVENT_THRESHOLD = 70
        private const val TRANSITION_DUPLICATE_WINDOW_MS = 60_000L
        private val TRANSPORT_EVENT_TYPES = setOf(
            "Walking Started",
            JourneyEventType.WALKING_DETECTED,
            "Vehicle Entered",
            JourneyEventType.VEHICLE_EXITED,
            "Train Likely Detected",
            JourneyEventType.TRAIN_TRANSIT,
            "Station Exit Detected",
            JourneyEventType.STATION_EXIT,
            JourneyEventType.LAST_MILE_WALK,
            "Last Mile Walk Started",
            "Cab Pickup Detected",
            "Cab Drop Detected"
        )
        private val RISK_EVENT_TYPES = setOf(
            JourneyEventType.HIGH_RISK_ZONE_ENTERED,
            JourneyEventType.HIGH_RISK_TRANSITION_ZONE_ENTERED,
            JourneyEventType.ELEVATED_RISK_DETECTED,
            JourneyEventType.UNEXPECTED_STOP_DETECTED,
            "Transition Risk Increased"
        )
        private val SOS_EVENT_TYPES = setOf(
            JourneyEventType.EMERGENCY_TRIGGERED,
            JourneyEventType.EMERGENCY_MODE_ACTIVATED,
            JourneyEventType.EMERGENCY_ALERT_SENT
        )
        private val SAFE_CHECKPOINT_EVENT_TYPES = setOf(
            JourneyEventType.JOURNEY_STARTED,
            JourneyEventType.SAFETY_MONITORING_ACTIVATED,
            JourneyEventType.WALKING_DETECTED,
            "Walking Started",
            JourneyEventType.VEHICLE_DETECTED,
            "Vehicle Entered",
            JourneyEventType.DESTINATION_REACHED,
            JourneyEventType.JOURNEY_COMPLETED
        )

        @Volatile
        private var INSTANCE: JourneySessionManager? = null

        fun initialize(
            repository: JourneyRepository,
            scope: CoroutineScope
        ): JourneySessionManager {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: JourneySessionManager(repository, scope).also { INSTANCE = it }
            }
        }

        fun getInstance(): JourneySessionManager {
            return requireNotNull(INSTANCE) {
                "JourneySessionManager must be initialized by SafePulseApplication"
            }
        }
    }
}
