package com.safepulse.service

import android.content.Intent
import android.content.Context
import com.safepulse.data.db.entity.EmergencyContactEntity
import com.safepulse.data.repository.EmergencyContactRepository
import com.safepulse.domain.journey.JourneyCheckIn
import com.safepulse.domain.journey.JourneyEvent
import com.safepulse.domain.journey.JourneyEventType
import com.safepulse.domain.journey.JourneyLiveState
import com.safepulse.domain.journey.JourneyMonitoringAlert
import com.safepulse.domain.journey.JourneyPhase
import com.safepulse.domain.journey.JourneySessionStatus
import com.safepulse.domain.journey.JourneyShareToken
import com.safepulse.domain.journey.LiveJourneySession
import com.safepulse.domain.journey.TransitionEvent
import com.safepulse.domain.journey.TransitionType
import com.safepulse.domain.model.LocationData
import com.safepulse.domain.journey.JourneySessionManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.UUID

class JourneyShareManager(
    private val context: Context,
    private val journeySessionManager: JourneySessionManager,
    private val contactRepository: EmergencyContactRepository,
    private val scope: CoroutineScope
) {
    companion object {
        private const val DEFAULT_USER_ID = "local-user"
        private const val ETA_MISS_BUFFER_MS = 10 * 60_000L
        private const val UNEXPECTED_STOP_THRESHOLD_MS = 6 * 60_000L
        private const val CHECK_IN_TIMEOUT_MS = 5 * 60_000L
        private const val MONITORING_TICK_MS = 15_000L
        private const val PERIODIC_STATUS_UPDATE_MS = 15 * 60_000L
        private const val DEFAULT_SHARE_DURATION_MS = 60 * 60_000L
        private const val STILL_DISTANCE_METERS = 25.0
    }

    private val emergencyManager = EmergencyManager(context)
    private val _state = MutableStateFlow(JourneyLiveState())
    val state: StateFlow<JourneyLiveState> = _state.asStateFlow()

    private var sessionJob: Job? = null
    private var monitorJob: Job? = null
    private var locationProvider: (() -> LocationData?)? = null
    private var lastLocation: LocationData? = null
    private var lastMovementAt: Long = 0L
    private var lastAlertSignature: String = ""
    private var lastRouteSignature: String = ""
    private var lastPeriodicStatusUpdateAt: Long = 0L

    init {
        sessionJob = scope.launch {
            journeySessionManager.state.collectLatest { sessionState ->
                syncFromSessionState(sessionState)
            }
        }
    }

    fun setLocationProvider(provider: () -> LocationData?) {
        locationProvider = provider
    }

    fun startMonitoring() {
        if (monitorJob?.isActive == true) return
        monitorJob = scope.launch {
            while (isActive) {
                evaluateJourneyHealth()
                delay(MONITORING_TICK_MS)
            }
        }
    }

    fun stopMonitoring() {
        monitorJob?.cancel()
        monitorJob = null
    }

    suspend fun startLiveJourney(
        destination: String,
        durationMinutes: Int,
        userId: String = DEFAULT_USER_ID
    ): LiveJourneySession {
        val journey = journeySessionManager.startJourney(destination)
        val now = System.currentTimeMillis()
        val etaMillis = now + durationMinutes.coerceAtLeast(1) * 60_000L
        val shareToken = buildShareToken(journey.sessionId)
        val shareLink = buildShareLink(shareToken.token)
        val liveSession = LiveJourneySession(
            sessionId = journey.sessionId,
            userId = userId,
            startTime = journey.startTime,
            expectedArrivalTime = etaMillis,
            destination = destination.ifBlank { "Selected destination" },
            status = JourneySessionStatus.ACTIVE,
            currentRiskScore = journey.riskScore,
            sharedContactIds = emptySet(),
            shareToken = shareToken.token,
            shareLink = shareLink
        )

        _state.value = _state.value.copy(
            liveSession = liveSession,
            currentPhase = journey.currentPhase,
            currentRiskScore = journey.riskScore,
            etaMillis = etaMillis,
            transportTransition = null,
            lastKnownLocation = locationProvider?.invoke(),
            currentCheckIn = null,
            shareToken = shareToken,
            monitoringLevel = 1
        )

        lastMovementAt = now
        lastLocation = locationProvider?.invoke()
        lastPeriodicStatusUpdateAt = 0L
        appendJourneyEvent("Companion Monitoring Activated", "Trusted-contact monitoring is active")
        startMonitoring()
        return liveSession
    }

    suspend fun shareJourney(targetContactIds: Set<Long> = emptySet()): JourneyShareToken? {
        val liveSession = _state.value.liveSession ?: return null
        val shareToken = _state.value.shareToken ?: buildShareToken(liveSession.sessionId)
        val contacts = resolveContacts(targetContactIds)
        val message = buildShareMessage(liveSession, shareToken)

        if (contacts.isNotEmpty()) {
            emergencyManager.sendJourneyStatusMessages(contacts, message)
        }

        _state.value = _state.value.copy(
            liveSession = liveSession.copy(sharedContactIds = contacts.map { it.id }.toSet()),
            shareToken = shareToken
        )
        appendJourneyEvent("Journey Shared", "Shared with ${contacts.size} trusted contact(s)")
        return shareToken
    }

    suspend fun shareActiveJourneyViaWhatsAppAndSms(
        targetContactIds: Set<Long> = emptySet()
    ): JourneyShareToken? {
        val liveSession = ensureLiveSessionForActiveJourney() ?: return null
        val shareToken = shareJourney(targetContactIds) ?: return null
        openWhatsAppShare(buildShareMessage(liveSession, shareToken))
        lastPeriodicStatusUpdateAt = System.currentTimeMillis()
        appendJourneyEvent("Journey Shared via WhatsApp", "WhatsApp share sheet opened")
        return shareToken
    }

    suspend fun completeJourney() {
        finishJourney(JourneySessionStatus.COMPLETED, "Journey completed")
    }

    suspend fun cancelJourney(reason: String = "Journey cancelled") {
        finishJourney(JourneySessionStatus.CANCELLED, reason)
    }

    suspend fun recordTransportTransition(event: TransitionEvent) {
        val nextPhase = phaseForTransition(event.transitionType)
        val nextRiskScore = eventRiskBoost(event.transitionType)
        val current = _state.value.liveSession ?: return
        val updatedSession = current.copy(currentRiskScore = maxOf(current.currentRiskScore, nextRiskScore))

        if (nextPhase != null) {
            journeySessionManager.updateCurrentPhase(nextPhase)
        }
        if (nextRiskScore > current.currentRiskScore) {
            journeySessionManager.updateRiskScore(nextRiskScore)
        }

        _state.value = _state.value.copy(
            liveSession = updatedSession,
            currentPhase = nextPhase ?: _state.value.currentPhase,
            currentRiskScore = maxOf(_state.value.currentRiskScore, nextRiskScore),
            transportTransition = event.transitionType.name,
            lastKnownLocation = LocationData(event.latitude, event.longitude, timestamp = event.timestamp)
        )

        appendJourneyEvent(event.transitionType.name, event.description)

        when (event.transitionType) {
            TransitionType.HIGH_RISK_ZONE_ENTERED,
            TransitionType.UNEXPECTED_STOP,
            TransitionType.LAST_MILE_WALK_STARTED -> {
                triggerProactiveAlert(
                    type = event.transitionType.name,
                    reason = event.description,
                    location = _state.value.lastKnownLocation
                )
            }
            else -> Unit
        }
    }

    suspend fun recordLocation(location: LocationData) {
        val previous = lastLocation
        lastLocation = location
        _state.value = _state.value.copy(lastKnownLocation = location)

        if (previous == null || distanceMeters(previous, location) > STILL_DISTANCE_METERS) {
            lastMovementAt = location.timestamp
        }
    }

    suspend fun updateRiskScore(riskScore: Int, reason: String? = null) {
        val current = _state.value.liveSession ?: return
        val nextRisk = riskScore.coerceIn(0, 100)
        val previousRisk = _state.value.currentRiskScore
        journeySessionManager.updateRiskScore(nextRisk)
        _state.value = _state.value.copy(
            liveSession = current.copy(currentRiskScore = nextRisk),
            currentRiskScore = nextRisk
        )

        if (nextRisk > previousRisk && nextRisk >= 70) {
            maybePromptCheckIn("Elevated risk score $nextRisk")
            triggerProactiveAlert(
                type = JourneyEventType.ELEVATED_RISK_DETECTED,
                reason = reason ?: "Elevated risk detected",
                location = _state.value.lastKnownLocation
            )
        }
    }

    suspend fun updateDestination(destination: String, durationMinutes: Int) {
        val current = _state.value.liveSession ?: return
        val etaMillis = System.currentTimeMillis() + durationMinutes.coerceAtLeast(1) * 60_000L
        val updated = current.copy(destination = destination, expectedArrivalTime = etaMillis)
        _state.value = _state.value.copy(liveSession = updated, etaMillis = etaMillis)
        appendJourneyEvent("Destination updated", destination)
        triggerProactiveAlert(
            type = "DESTINATION_CHANGED",
            reason = "Destination changed to $destination",
            location = _state.value.lastKnownLocation
        )
    }

    suspend fun requestCheckIn(label: String, timeoutMinutes: Int = 5) {
        val now = System.currentTimeMillis()
        val checkIn = JourneyCheckIn(
            label = label.ifBlank { "Check-in" },
            requestedAt = now,
            dueAt = now + timeoutMinutes.coerceAtLeast(1) * 60_000L
        )
        _state.value = _state.value.copy(currentCheckIn = checkIn)
        appendJourneyEvent("Check-in requested", checkIn.label)

        val contacts = resolveContacts(_state.value.liveSession?.sharedContactIds.orEmpty())
        if (contacts.isNotEmpty()) {
            emergencyManager.sendJourneyStatusMessages(
                contacts,
                buildCheckInMessage(checkIn)
            )
        }
    }

    suspend fun acknowledgeCheckIn() {
        val checkIn = _state.value.currentCheckIn ?: return
        val acknowledged = checkIn.copy(acknowledgedAt = System.currentTimeMillis())
        _state.value = _state.value.copy(currentCheckIn = null, monitoringLevel = 1)
        appendJourneyEvent("Check-in acknowledged", acknowledged.label)
    }

    fun getShareMessage(): String {
        val liveSession = _state.value.liveSession ?: return "No active journey"
        val token = _state.value.shareToken ?: buildShareToken(liveSession.sessionId)
        return buildShareMessage(liveSession, token)
    }

    private suspend fun finishJourney(status: JourneySessionStatus, reason: String) {
        val current = _state.value.liveSession ?: return
        if (status == JourneySessionStatus.COMPLETED) {
            sendSafeArrivalUpdate(current)
        }
        journeySessionManager.endJourney(status)
        _state.value = _state.value.copy(
            liveSession = current.copy(status = status),
            currentCheckIn = null,
            shareToken = _state.value.shareToken?.let { token ->
                token.copy(expiresAt = System.currentTimeMillis())
            }
        )
        stopMonitoring()
    }

    private suspend fun sendSafeArrivalUpdate(session: LiveJourneySession) {
        val contacts = resolveContacts(session.sharedContactIds)
        if (contacts.isNotEmpty()) {
            emergencyManager.sendJourneyStatusMessages(contacts, buildSafeArrivalMessage(session))
        }
        appendJourneyEvent(
            JourneyEventType.SAFE_ARRIVAL_UPDATE_SENT,
            "Safe arrival update sent to ${contacts.size} trusted contact(s)"
        )
    }

    private suspend fun triggerProactiveAlert(
        type: String,
        reason: String,
        location: LocationData?
    ) {
        val currentSession = _state.value.liveSession ?: return
        val signature = "$type|$reason|${currentSession.sessionId}"
        if (signature == lastAlertSignature) return
        lastAlertSignature = signature

        val contacts = resolveContacts(currentSession.sharedContactIds)
        val alert = JourneyMonitoringAlert(
            type = type,
            reason = reason,
            timestamp = System.currentTimeMillis(),
            contactCount = contacts.size
        )

        _state.value = _state.value.copy(
            alerts = (_state.value.alerts + alert).takeLast(20),
            monitoringLevel = (_state.value.monitoringLevel + 1).coerceAtMost(5)
        )

        if (contacts.isNotEmpty()) {
            emergencyManager.sendJourneyStatusMessages(contacts, buildAlertMessage(alert, location))
            appendJourneyEvent(
                JourneyEventType.COMPANION_ALERT_SENT,
                "Companion alert sent to ${contacts.size} trusted contact(s): $type"
            )
        }

        appendJourneyEvent(type, reason)
    }

    private suspend fun evaluateJourneyHealth() {
        val liveSession = _state.value.liveSession ?: return
        val location = locationProvider?.invoke()?.also { recordLastLocationIfNeeded(it) }
        val now = System.currentTimeMillis()
        val currentRiskScore = _state.value.currentRiskScore

        if (liveSession.status != JourneySessionStatus.ACTIVE) return

        if (now > liveSession.expectedArrivalTime + ETA_MISS_BUFFER_MS) {
            triggerProactiveAlert(
                type = "MISSED_ETA",
                reason = "Expected arrival time missed",
                location = location ?: _state.value.lastKnownLocation
            )
        }

        val checkIn = _state.value.currentCheckIn
        if (checkIn != null && now > checkIn.dueAt && checkIn.acknowledgedAt == null) {
            _state.value = _state.value.copy(
                currentCheckIn = checkIn.copy(ignored = true),
                monitoringLevel = (_state.value.monitoringLevel + 1).coerceAtMost(5)
            )
            triggerProactiveAlert(
                type = "CHECK_IN_MISSED",
                reason = "Check-in ignored: ${checkIn.label}",
                location = location ?: _state.value.lastKnownLocation
            )
        }

        if (currentRiskScore >= 70 && location != null && now - lastMovementAt > UNEXPECTED_STOP_THRESHOLD_MS) {
            triggerProactiveAlert(
                type = JourneyEventType.UNEXPECTED_STOP_DETECTED,
                reason = "User appears still for longer than expected while risk score is $currentRiskScore",
                location = location
            )
            lastMovementAt = now
        }

        sendPeriodicStatusUpdateIfDue(now, location ?: _state.value.lastKnownLocation)
    }

    private suspend fun sendPeriodicStatusUpdateIfDue(now: Long, location: LocationData?) {
        val liveSession = _state.value.liveSession ?: return
        if (liveSession.sharedContactIds.isEmpty()) return

        val lastUpdateAt = lastPeriodicStatusUpdateAt.takeIf { it > 0L }
            ?: liveSession.startTime.also { lastPeriodicStatusUpdateAt = it }
        if (now - lastUpdateAt < PERIODIC_STATUS_UPDATE_MS) return

        val contacts = resolveContacts(liveSession.sharedContactIds)
        if (contacts.isEmpty()) return

        val message = buildPeriodicStatusMessage(
            session = liveSession,
            location = location,
            status = statusTextForPhase(_state.value.currentPhase)
        )
        if (emergencyManager.sendJourneyStatusMessages(contacts, message)) {
            lastPeriodicStatusUpdateAt = now
            appendJourneyEvent(
                "Journey Status Update Sent",
                "15-minute update sent to ${contacts.size} trusted contact(s)"
            )
        }
    }

    private suspend fun ensureLiveSessionForActiveJourney(): LiveJourneySession? {
        val existing = _state.value.liveSession
        val activeSession = journeySessionManager.state.value.activeSession
            ?: journeySessionManager.activeSession.value
            ?: return existing?.takeIf { it.status == JourneySessionStatus.ACTIVE }

        if (existing?.sessionId == activeSession.sessionId &&
            existing.status == JourneySessionStatus.ACTIVE
        ) {
            return existing
        }

        val now = System.currentTimeMillis()
        val shareToken = buildShareToken(activeSession.sessionId)
        val liveSession = LiveJourneySession(
            sessionId = activeSession.sessionId,
            userId = DEFAULT_USER_ID,
            startTime = activeSession.startTime,
            expectedArrivalTime = now + DEFAULT_SHARE_DURATION_MS,
            destination = activeSession.destination ?: "Active Safe Journey",
            status = JourneySessionStatus.ACTIVE,
            currentRiskScore = activeSession.riskScore,
            sharedContactIds = emptySet(),
            shareToken = shareToken.token,
            shareLink = shareToken.link
        )

        _state.value = _state.value.copy(
            liveSession = liveSession,
            currentPhase = activeSession.currentPhase,
            currentRiskScore = activeSession.riskScore,
            lastKnownLocation = locationProvider?.invoke(),
            etaMillis = liveSession.expectedArrivalTime,
            shareToken = shareToken,
            monitoringLevel = 1
        )
        lastMovementAt = now
        lastLocation = _state.value.lastKnownLocation
        lastPeriodicStatusUpdateAt = 0L
        startMonitoring()
        appendJourneyEvent("Companion Monitoring Activated", "Trusted-contact sharing is ready for the active journey")
        return liveSession
    }

    private suspend fun maybePromptCheckIn(label: String) {
        if (_state.value.currentCheckIn != null) return
        requestCheckIn(label, timeoutMinutes = CHECK_IN_TIMEOUT_MS.toInt() / 60_000)
    }

    private fun recordLastLocationIfNeeded(location: LocationData) {
        val previous = lastLocation
        if (previous == null || distanceMeters(previous, location) > STILL_DISTANCE_METERS) {
            lastMovementAt = location.timestamp
        }
        lastLocation = location
        _state.value = _state.value.copy(lastKnownLocation = location)
    }

    private fun syncFromSessionState(sessionState: com.safepulse.domain.journey.JourneySessionState) {
        val activeSession = _state.value.liveSession
        if (activeSession == null) return

        if (sessionState.activeSession == null && activeSession.status == JourneySessionStatus.ACTIVE) {
            val finalStatus = sessionState.latestSummary?.finalStatus ?: JourneySessionStatus.COMPLETED
            _state.value = _state.value.copy(
                liveSession = activeSession.copy(status = finalStatus),
                currentPhase = finalStatus.toPhase(),
                currentRiskScore = activeSession.currentRiskScore,
                timeline = sessionState.events,
                lastTimelineEvent = sessionState.events.lastOrNull()?.description
            )
            stopMonitoring()
            return
        }

        val session = sessionState.activeSession ?: return
        _state.value = _state.value.copy(
            liveSession = activeSession.copy(currentRiskScore = session.riskScore),
            currentPhase = session.currentPhase,
            currentRiskScore = session.riskScore,
            timeline = sessionState.events,
            lastTimelineEvent = sessionState.events.lastOrNull()?.description,
            etaMillis = maxOf(_state.value.etaMillis, activeSession.expectedArrivalTime),
            transportTransition = sessionState.events.lastOrNull()?.eventType ?: _state.value.transportTransition
        )
    }

    private fun appendJourneyEvent(eventType: String, description: String) {
        scope.launch {
            journeySessionManager.appendJourneyEvent(eventType, description)
        }
    }

    private suspend fun resolveContacts(allowedIds: Set<Long>): List<EmergencyContactEntity> {
        val contacts = contactRepository.getAllContactsList()
        return if (allowedIds.isEmpty()) contacts else contacts.filter { it.id in allowedIds }
    }

    private fun buildShareToken(sessionId: String): JourneyShareToken {
        val generatedAt = System.currentTimeMillis()
        val token = UUID.randomUUID().toString().take(8).uppercase()
        return JourneyShareToken(
            token = token,
            link = buildShareLink(token),
            generatedAt = generatedAt,
            expiresAt = generatedAt + 24 * 60 * 60_000L
        )
    }

    private fun buildShareLink(token: String): String {
        return "safepulse://journey/$token"
    }

    private fun buildShareMessage(session: LiveJourneySession, token: JourneyShareToken): String {
        val etaText = java.text.SimpleDateFormat("h:mm a", java.util.Locale.getDefault())
            .format(java.util.Date(session.expectedArrivalTime))
        val locationText = _state.value.lastKnownLocation?.let {
            "Current location: https://maps.google.com/?q=${it.latitude},${it.longitude}"
        } ?: "Current location: waiting for GPS"
        return buildString {
            appendLine("SafePulse Journey Active")
            appendLine("Journey ID: ${token.token}")
            appendLine("Destination: ${session.destination}")
            appendLine("ETA: $etaText")
            appendLine("Status: ${statusTextForPhase(_state.value.currentPhase)}")
            appendLine(locationText)
            appendLine("Link: ${token.link}")
        }
    }

    private fun buildCheckInMessage(checkIn: JourneyCheckIn): String {
        return buildString {
            appendLine("SafePulse check-in requested")
            appendLine("Are you safe?")
            appendLine("Reason: ${checkIn.label}")
            appendLine("Please acknowledge before ${java.text.SimpleDateFormat("h:mm a", java.util.Locale.getDefault()).format(java.util.Date(checkIn.dueAt))}")
        }
    }

    private fun buildAlertMessage(alert: JourneyMonitoringAlert, location: LocationData?): String {
        val locationText = location?.let {
            "https://maps.google.com/?q=${it.latitude},${it.longitude}"
        } ?: "Location unavailable"
        return buildString {
            appendLine("SafePulse proactive journey alert")
            appendLine("Type: ${alert.type}")
            appendLine("Reason: ${alert.reason}")
            appendLine("Location: $locationText")
        }
    }

    private fun buildSafeArrivalMessage(session: LiveJourneySession): String {
        return buildString {
            appendLine("SafePulse safe arrival update")
            appendLine("Journey completed.")
            appendLine("Destination: ${session.destination}")
            appendLine("Journey ID: ${session.shareToken}")
        }
    }

    private fun buildPeriodicStatusMessage(
        session: LiveJourneySession,
        location: LocationData?,
        status: String
    ): String {
        val locationText = location?.let {
            "https://maps.google.com/?q=${it.latitude},${it.longitude}"
        } ?: "Location unavailable"

        return buildString {
            appendLine("SafePulse journey update")
            appendLine("Journey ID: ${session.shareToken}")
            appendLine("Destination: ${session.destination}")
            appendLine("Current status: $status")
            appendLine("Risk score: ${_state.value.currentRiskScore}")
            appendLine("Location: $locationText")
        }
    }

    private fun openWhatsAppShare(message: String) {
        val whatsappIntent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            setPackage("com.whatsapp")
            putExtra(Intent.EXTRA_TEXT, message)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }

        runCatching {
            context.startActivity(whatsappIntent)
        }.onFailure {
            val chooser = Intent.createChooser(
                Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_TEXT, message)
                },
                "Share Journey"
            ).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            runCatching { context.startActivity(chooser) }
        }
    }

    private fun statusTextForPhase(phase: JourneyPhase): String {
        return when (phase) {
            JourneyPhase.JOURNEY_STARTED -> "Journey started"
            JourneyPhase.WALKING -> "Walking"
            JourneyPhase.IN_VEHICLE -> "Travelling in vehicle"
            JourneyPhase.TRAIN_TRANSIT -> "Travelling by train"
            JourneyPhase.STATION_EXIT -> "Station or vehicle exit"
            JourneyPhase.LAST_MILE_WALK -> "Last-mile walk"
            JourneyPhase.ARRIVED -> "Arrived"
            JourneyPhase.EMERGENCY -> "Emergency mode active"
        }
    }

    private fun phaseForTransition(transitionType: TransitionType): JourneyPhase? {
        return when (transitionType) {
            TransitionType.JOURNEY_STARTED -> JourneyPhase.JOURNEY_STARTED
            TransitionType.WALKING_STARTED -> JourneyPhase.WALKING
            TransitionType.LAST_MILE_WALK_STARTED -> JourneyPhase.LAST_MILE_WALK
            TransitionType.VEHICLE_ENTERED,
            TransitionType.CAB_PICKUP_DETECTED -> JourneyPhase.IN_VEHICLE
            TransitionType.TRAIN_LIKELY_DETECTED -> JourneyPhase.TRAIN_TRANSIT
            TransitionType.STATION_EXIT_DETECTED -> JourneyPhase.STATION_EXIT
            TransitionType.DESTINATION_REACHED -> JourneyPhase.ARRIVED
            TransitionType.UNEXPECTED_STOP,
            TransitionType.HIGH_RISK_ZONE_ENTERED,
            TransitionType.HIGH_RISK_ZONE_EXITED,
            TransitionType.WALKING_STOPPED,
            TransitionType.VEHICLE_EXITED,
            TransitionType.CAB_DROP_DETECTED -> null
        }
    }

    private fun eventRiskBoost(transitionType: TransitionType): Int {
        return when (transitionType) {
            TransitionType.HIGH_RISK_ZONE_ENTERED -> 80
            TransitionType.UNEXPECTED_STOP -> 75
            TransitionType.LAST_MILE_WALK_STARTED -> 68
            TransitionType.STATION_EXIT_DETECTED -> 55
            TransitionType.CAB_DROP_DETECTED -> 45
            TransitionType.TRAIN_LIKELY_DETECTED -> 35
            else -> _state.value.currentRiskScore
        }
    }

    private fun distanceMeters(first: LocationData, second: LocationData): Double {
        val earthRadius = 6_371_000.0
        val lat1 = Math.toRadians(first.latitude)
        val lat2 = Math.toRadians(second.latitude)
        val deltaLat = Math.toRadians(second.latitude - first.latitude)
        val deltaLon = Math.toRadians(second.longitude - first.longitude)
        val a = kotlin.math.sin(deltaLat / 2) * kotlin.math.sin(deltaLat / 2) +
            kotlin.math.cos(lat1) * kotlin.math.cos(lat2) *
            kotlin.math.sin(deltaLon / 2) * kotlin.math.sin(deltaLon / 2)
        val c = 2 * kotlin.math.atan2(kotlin.math.sqrt(a), kotlin.math.sqrt(1 - a))
        return earthRadius * c
    }

    private fun JourneySessionStatus.toPhase(): JourneyPhase {
        return when (this) {
            JourneySessionStatus.ACTIVE -> JourneyPhase.JOURNEY_STARTED
            JourneySessionStatus.COMPLETED -> JourneyPhase.ARRIVED
            JourneySessionStatus.CANCELLED -> JourneyPhase.EMERGENCY
        }
    }
}
