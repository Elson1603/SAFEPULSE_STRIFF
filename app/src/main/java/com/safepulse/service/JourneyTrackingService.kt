package com.safepulse.service

import android.content.Context
import com.google.android.gms.maps.model.LatLng
import com.safepulse.data.db.entity.UnsafeZoneEntity
import com.safepulse.data.repository.RiskZoneRepository
import com.safepulse.data.repository.UnsafeZoneRepository
import com.safepulse.domain.journey.JourneyEventType
import com.safepulse.domain.journey.JourneyPhase
import com.safepulse.domain.journey.JourneySessionManager
import com.safepulse.domain.journey.JourneySessionStatus
import com.safepulse.domain.journey.RiskZoneSnapshot
import com.safepulse.domain.journey.StationVicinitySnapshot
import com.safepulse.domain.journey.TransitionType
import com.safepulse.domain.journey.TransportTransitionDetector
import com.safepulse.domain.model.LocationData
import com.safepulse.domain.model.SafetyMode
import com.safepulse.domain.model.SafetyState
import com.safepulse.domain.transition.TransitionRiskEngine
import com.safepulse.domain.transition.TransitionZoneType
import com.safepulse.utils.NotificationHelper
import com.safepulse.utils.SafetyConstants
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.max
import kotlin.math.roundToInt

class JourneyTrackingService(
    private val context: Context,
    private val locationTracker: LocationTracker,
    private val journeySessionManager: JourneySessionManager,
    private val journeyShareManager: JourneyShareManager,
    private val unsafeZoneRepository: UnsafeZoneRepository,
    private val riskZoneRepository: RiskZoneRepository,
    private val transitionRiskEngine: TransitionRiskEngine,
    private val scope: CoroutineScope
) {
    private val activityRecognitionManager = JourneyActivityRecognitionManager(context)
    private val transitionDetector = TransportTransitionDetector()
    private var periodicJob: Job? = null
    private var notificationJob: Job? = null
    private var activityJob: Job? = null
    private var transitionJob: Job? = null
    private var transitionRiskJob: Job? = null
    private var transitionAlertJob: Job? = null
    private var sessionJob: Job? = null
    private var lastLocation: LocationData? = null
    private var lastSafetyState: SafetyState? = null
    private var activeSessionId: String? = null
    private var lastRecordedTransitionRiskScore = 0
    private var lastRecordedTransitionZone = TransitionZoneType.UNKNOWN_TRANSITION
    private var heightenedMonitoringUntil = 0L
    private val transitionBoostLock = Any()
    private val transitionBoostExpirations = mutableMapOf<TransitionType, Long>()

    fun start() {
        if (periodicJob?.isActive == true) return

        scope.launch { activityRecognitionManager.start() }

        sessionJob = scope.launch {
            journeySessionManager.activeSession.collect { session ->
                if (session == null) {
                    activeSessionId = null
                    lastRecordedTransitionRiskScore = 0
                    lastRecordedTransitionZone = TransitionZoneType.UNKNOWN_TRANSITION
                    transitionDetector.reset()
                    transitionRiskEngine.reset()
                    journeyShareManager.stopMonitoring()
                    return@collect
                }

                if (activeSessionId != session.sessionId) {
                    activeSessionId = session.sessionId
                    lastRecordedTransitionRiskScore = 0
                    lastRecordedTransitionZone = TransitionZoneType.UNKNOWN_TRANSITION
                    transitionDetector.reset()
                    transitionRiskEngine.reset()
                    lastLocation?.let { transitionDetector.processLocation(it) }
                    lastLocation?.let { transitionRiskEngine.processLocation(it) }
                    scope.launch { journeyShareManager.startMonitoring() }
                } else {
                    transitionRiskEngine.processJourneyPhase(session.currentPhase)
                }
            }
        }

        activityJob = scope.launch {
            JourneyActivityRecognitionBus.updates.collect { update ->
                transitionDetector.processActivity(update)
                transitionRiskEngine.processActivity(update)
                scope.launch {
                    journeyShareManager.updateRiskScore(
                        riskScore = transitionRiskEngine.state.value.currentRiskScore,
                        reason = "Activity transition: ${update.type.name.lowercase()}"
                    )
                }
            }
        }

        transitionJob = scope.launch {
            transitionDetector.transitionEvent
                .collect { event ->
                    rememberTransitionRiskBoost(event.transitionType, event.timestamp)
                    transitionRiskEngine.processTransition(event)
                    journeySessionManager.recordTransitionEvent(event)
                    journeyShareManager.recordTransportTransition(event)
                    if (event.transitionType == TransitionType.UNEXPECTED_STOP ||
                        event.transitionType == TransitionType.HIGH_RISK_ZONE_ENTERED
                    ) {
                        heightenedMonitoringUntil =
                            max(heightenedMonitoringUntil, event.timestamp + HEIGHTENED_MONITORING_EXTENSION_MS)
                        locationTracker.updateMode(SafetyMode.HEIGHTENED)
                    }
                }
        }

        transitionRiskJob = scope.launch {
            transitionRiskEngine.state.collect { riskState ->
                val scoreChanged = kotlin.math.abs(
                    riskState.currentRiskScore - lastRecordedTransitionRiskScore
                ) >= TRANSITION_RISK_TIMELINE_STEP
                val zoneChanged = riskState.transitionZoneType != lastRecordedTransitionZone
                if (activeSessionId != null && riskState.currentRiskScore > 0 && (scoreChanged || zoneChanged)) {
                    lastRecordedTransitionRiskScore = riskState.currentRiskScore
                    lastRecordedTransitionZone = riskState.transitionZoneType
                    journeySessionManager.recordTransitionRiskState(riskState)
                    journeyShareManager.updateRiskScore(
                        riskScore = riskState.currentRiskScore,
                        reason = riskState.reasons.firstOrNull()
                    )
                }
            }
        }

        transitionAlertJob = scope.launch {
            transitionRiskEngine.riskEvents.collect { event ->
                NotificationHelper.showTransitionRiskAlertNotification(context, event.state)
                heightenedMonitoringUntil =
                    max(heightenedMonitoringUntil, event.timestamp + HEIGHTENED_MONITORING_EXTENSION_MS)
                locationTracker.updateMode(SafetyMode.HEIGHTENED)
            }
        }

        periodicJob = scope.launch {
            while (isActive) {
                syncCurrentJourneySnapshot()
                delay(SafetyConstants.LOCATION_INTERVAL_NORMAL_MS)
            }
        }

        notificationJob = scope.launch {
            journeySessionManager.state.collect { state ->
                val session = state.activeSession
                if (session == null) {
                    cancelJourneyNotification()
                } else {
                    NotificationHelper.showJourneyForegroundNotification(
                        context = context,
                        phase = session.currentPhase,
                        riskScore = session.riskScore
                    )
                }
            }
        }
    }

    fun stop() {
        periodicJob?.cancel()
        periodicJob = null
        notificationJob?.cancel()
        notificationJob = null
        activityJob?.cancel()
        activityJob = null
        transitionJob?.cancel()
        transitionJob = null
        transitionRiskJob?.cancel()
        transitionRiskJob = null
        transitionAlertJob?.cancel()
        transitionAlertJob = null
        sessionJob?.cancel()
        sessionJob = null
        transitionDetector.reset()
        transitionRiskEngine.reset()
        activeSessionId = null
        lastRecordedTransitionRiskScore = 0
        lastRecordedTransitionZone = TransitionZoneType.UNKNOWN_TRANSITION
        synchronized(transitionBoostLock) {
            transitionBoostExpirations.clear()
        }
        scope.launch { activityRecognitionManager.stop() }
    }

    fun onLocationUpdated(location: LocationData) {
        lastLocation = location
        processJourneyLocation(location)
        syncRiskFromSafetyState()
        applyTransitionMonitoringMode()
    }

    fun onSafetyStateChanged(state: SafetyState) {
        lastSafetyState = state
        syncRiskFromSafetyState()
        applyTransitionMonitoringMode()

        if (state.isEmergency) {
            scope.launch {
                journeySessionManager.updateCurrentPhase(JourneyPhase.EMERGENCY)
            }
        }
    }

    fun onEmergencyTriggered(description: String) {
        scope.launch {
            journeySessionManager.updateCurrentPhase(JourneyPhase.EMERGENCY)
            journeySessionManager.appendJourneyEvent(
                eventType = JourneyEventType.EMERGENCY_MODE_ACTIVATED,
                description = description
            )
            journeySessionManager.appendJourneyEvent(
                eventType = JourneyEventType.EMERGENCY_ALERT_SENT,
                description = "Emergency alert sent to configured contacts and nearby services when available"
            )
            val location = lastLocation ?: locationTracker.currentLocation.value
            journeySessionManager.appendJourneyEvent(
                eventType = JourneyEventType.LAST_KNOWN_LOCATION_SHARED,
                description = location?.let {
                    "Last known location shared: ${it.latitude}, ${it.longitude}"
                } ?: "Last known location unavailable when emergency mode activated"
            )
        }
    }

    private fun syncRiskFromSafetyState() {
        val state = lastSafetyState ?: return
        scope.launch {
            val riskScore = ((state.riskScore * 100f).roundToInt() + activeTransitionRiskBoost())
                .coerceIn(0, 100)
            journeySessionManager.updateRiskScore(riskScore)
        }
    }

    private fun syncCurrentJourneySnapshot() {
        val location = locationTracker.currentLocation.value ?: lastLocation
        lastLocation = location
        location?.let {
            processJourneyLocation(it.copy(timestamp = System.currentTimeMillis()))
        }
        syncRiskFromSafetyState()
        applyTransitionMonitoringMode()
    }

    private fun cancelJourneyNotification() {
        NotificationHelper.showDefaultForegroundNotification(context)
    }

    private fun processJourneyLocation(location: LocationData) {
        if (activeSessionId == null) return

        transitionDetector.processLocation(location)
        transitionRiskEngine.processLocation(location)
        scope.launch { journeyShareManager.recordLocation(location) }
        transitionDetector.processStationVicinity(buildStationVicinitySnapshot(location))

        scope.launch {
            transitionDetector.processRiskZone(buildRiskZoneSnapshot(location))
        }
    }

    private suspend fun buildRiskZoneSnapshot(location: LocationData): RiskZoneSnapshot {
        val point = LatLng(location.latitude, location.longitude)
        val unsafeMatch = findUnsafeZoneMatch(location)
        val unsafeScore = unsafeMatch?.let { unsafeZoneRiskScore(it.zone, it.distanceMeters) } ?: 0f
        val crimeRisk = riskZoneRepository.computeCrimeRisk(point)
        val disasterRisk = riskZoneRepository.computeDisasterRisk(point)
        val engineRisk = lastSafetyState?.riskScore ?: 0f
        val maxRisk = max(max(unsafeScore, crimeRisk), max(disasterRisk, engineRisk)).coerceIn(0f, 1f)

        val sources = mutableListOf<String>()
        unsafeMatch?.let {
            sources.add("unsafe_zones.json match within ${it.distanceMeters.roundToInt()}m")
        }
        if (crimeRisk >= DATASET_HIGH_RISK_THRESHOLD) {
            sources.add("crime_risk_zones.json score ${(crimeRisk * 100).roundToInt()}")
        }
        if (disasterRisk >= DATASET_HIGH_RISK_THRESHOLD) {
            sources.add("disaster_risk_zones.json score ${(disasterRisk * 100).roundToInt()}")
        }
        if (engineRisk >= SafetyConstants.HIGH_RISK_THRESHOLD) {
            sources.add("live safety engine score ${(engineRisk * 100).roundToInt()}")
        }

        return RiskZoneSnapshot(
            isInsideRiskZone = sources.isNotEmpty(),
            riskScore = (maxRisk * 100f).roundToInt(),
            source = sources.ifEmpty { listOf("no active high-risk dataset match") }
                .joinToString(separator = "; ")
        )
    }

    private suspend fun findUnsafeZoneMatch(location: LocationData): UnsafeZoneMatch? {
        val nearbyZones = unsafeZoneRepository.getUnsafeZonesNear(
            lat = location.latitude,
            lng = location.longitude
        )
        val point = LatLng(location.latitude, location.longitude)

        return nearbyZones
            .mapNotNull { zone ->
                val distance = RiskZoneRepository.distanceMeters(point, LatLng(zone.lat, zone.lng))
                if (distance <= zone.radiusMeters) {
                    UnsafeZoneMatch(zone, distance)
                } else {
                    null
                }
            }
            .minByOrNull { it.distanceMeters }
    }

    private fun unsafeZoneRiskScore(zone: UnsafeZoneEntity, distanceMeters: Float): Float {
        val distanceDecay = 1f - (distanceMeters / zone.radiusMeters).coerceIn(0f, 1f)
        return (zone.crimeScore *
            (1f - zone.lightingScore * 0.3f) *
            (1f - zone.footfallScore * 0.3f) *
            distanceDecay
        ).coerceIn(0f, 1f)
    }

    private fun buildStationVicinitySnapshot(location: LocationData): StationVicinitySnapshot {
        val point = LatLng(location.latitude, location.longitude)
        var bestLabel: String? = null
        var bestDistance = Float.MAX_VALUE

        riskZoneRepository.loadCrimeRiskZones().forEach { zone ->
            zone.hotspots.forEach { hotspot ->
                if (hotspot.label.looksLikeStation()) {
                    val distance = RiskZoneRepository.distanceMeters(point, hotspot.location)
                    if (distance <= STATION_HEURISTIC_RADIUS_METERS && distance < bestDistance) {
                        bestDistance = distance
                        bestLabel = hotspot.label
                    }
                }
            }
        }

        return if (bestLabel != null) {
            StationVicinitySnapshot(
                isNearStation = true,
                label = bestLabel,
                confidence = (0.9f - (bestDistance / STATION_HEURISTIC_RADIUS_METERS) * 0.18f)
                    .coerceIn(0.72f, 0.9f)
            )
        } else {
            StationVicinitySnapshot(isNearStation = false)
        }
    }

    private fun String.looksLikeStation(): Boolean {
        val normalized = lowercase()
        return STATION_KEYWORDS.any { keyword -> normalized.contains(keyword) }
    }

    private fun applyTransitionMonitoringMode() {
        if (System.currentTimeMillis() <= heightenedMonitoringUntil) {
            locationTracker.updateMode(SafetyMode.HEIGHTENED)
        }
    }

    private fun rememberTransitionRiskBoost(type: TransitionType, timestamp: Long) {
        val boost = riskBoostForTransition(type)
        synchronized(transitionBoostLock) {
            if (type == TransitionType.HIGH_RISK_ZONE_EXITED) {
                transitionBoostExpirations.remove(TransitionType.HIGH_RISK_ZONE_ENTERED)
            }
            if (boost > 0) {
                transitionBoostExpirations[type] = timestamp + RISK_BOOST_DURATION_MS
            }
        }
    }

    private fun activeTransitionRiskBoost(now: Long = System.currentTimeMillis()): Int {
        return synchronized(transitionBoostLock) {
            val expiredTypes = transitionBoostExpirations
                .filterValues { expiresAt -> expiresAt <= now }
                .keys
            expiredTypes.forEach { transitionBoostExpirations.remove(it) }
            transitionBoostExpirations.keys.sumOf { riskBoostForTransition(it) }
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

    private data class UnsafeZoneMatch(
        val zone: UnsafeZoneEntity,
        val distanceMeters: Float
    )

    companion object {
        private const val DATASET_HIGH_RISK_THRESHOLD = 0.55f
        private const val HEIGHTENED_MONITORING_EXTENSION_MS = 10 * 60_000L
        private const val RISK_BOOST_DURATION_MS = 15 * 60_000L
        private const val STATION_HEURISTIC_RADIUS_METERS = 750f
        private const val TRANSITION_RISK_TIMELINE_STEP = 8
        private val STATION_KEYWORDS = listOf(
            "station",
            "rail",
            "metro",
            "junction",
            "terminus",
            "terminal",
            "cst"
        )
    }
}
