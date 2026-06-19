package com.safepulse.domain.transition

import com.google.android.gms.maps.model.LatLng
import com.safepulse.data.repository.RiskZoneRepository
import com.safepulse.domain.journey.JourneyActivityType
import com.safepulse.domain.journey.JourneyActivityUpdate
import com.safepulse.domain.journey.JourneyPhase
import com.safepulse.domain.journey.TransitionEvent
import com.safepulse.domain.journey.TransitionType
import com.safepulse.domain.model.LocationData
import com.safepulse.domain.riskmap.CrimeRiskZone
import com.safepulse.domain.riskmap.SafetyPlace
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Calendar
import kotlin.math.roundToInt

class TransitionRiskEngine(
    private val riskZoneRepository: RiskZoneRepository,
    private val config: TransitionRiskConfig = TransitionRiskConfig.DEFAULT,
    private val isolationScoreCalculator: IsolationScoreCalculator = IsolationScoreCalculator(),
    private val lowLightHeuristic: LowLightHeuristic = LowLightHeuristic()
) {
    private val _state = MutableStateFlow(TransitionRiskState())
    val state: StateFlow<TransitionRiskState> = _state.asStateFlow()

    private val _riskEvents = MutableSharedFlow<TransitionRiskEvent>(extraBufferCapacity = 8)
    val riskEvents: SharedFlow<TransitionRiskEvent> = _riskEvents.asSharedFlow()

    private val locationHistory = ArrayDeque<LocationData>()
    private var currentPhase: JourneyPhase? = null
    private var currentActivity: JourneyActivityType = JourneyActivityType.UNKNOWN
    private var activeTransitionType: TransitionType? = null
    private var activeZoneType: TransitionZoneType = TransitionZoneType.UNKNOWN_TRANSITION
    private var activeTransitionStartedAt: Long? = null
    private var cabDropDetectedAt: Long? = null
    private var lastAlertScore = 0

    fun reset() {
        locationHistory.clear()
        currentPhase = null
        currentActivity = JourneyActivityType.UNKNOWN
        activeTransitionType = null
        activeZoneType = TransitionZoneType.UNKNOWN_TRANSITION
        activeTransitionStartedAt = null
        cabDropDetectedAt = null
        lastAlertScore = 0
        _state.value = TransitionRiskState()
    }

    fun processJourneyPhase(phase: JourneyPhase, timestamp: Long = System.currentTimeMillis()) {
        currentPhase = phase
        val zoneType = when (phase) {
            JourneyPhase.STATION_EXIT -> TransitionZoneType.STATION_EXIT
            JourneyPhase.LAST_MILE_WALK -> TransitionZoneType.LAST_MILE_WALK
            else -> activeZoneType
        }
        if (zoneType != activeZoneType) {
            activeZoneType = zoneType
            activeTransitionStartedAt = timestamp
        }
        latestLocation()?.let { recalculate(it, timestamp) }
    }

    fun processActivity(update: JourneyActivityUpdate) {
        currentActivity = update.type
        latestLocation()?.let { recalculate(it, update.timestamp) }
    }

    fun processLocation(location: LocationData) {
        locationHistory.addLast(location)
        trimLocationHistory(location.timestamp)
        recalculate(location, location.timestamp)
    }

    fun processTransition(event: TransitionEvent) {
        activeTransitionType = event.transitionType
        activeTransitionStartedAt = event.timestamp
        activeZoneType = zoneTypeFor(event.transitionType)
        if (event.transitionType == TransitionType.CAB_DROP_DETECTED ||
            event.transitionType == TransitionType.VEHICLE_EXITED
        ) {
            cabDropDetectedAt = event.timestamp
        }
        recalculate(
            LocationData(
                latitude = event.latitude,
                longitude = event.longitude,
                timestamp = event.timestamp
            ),
            event.timestamp
        )
    }

    private fun recalculate(location: LocationData, timestamp: Long) {
        val point = LatLng(location.latitude, location.longitude)
        val crimeZones = riskZoneRepository.loadCrimeRiskZones()
        val safetyPlaces = riskZoneRepository.loadSafetyPlaces()
        val highCrimeZone = matchingHighCrimeZone(point, crimeZones)
        val safetyPlacesNear = riskZoneRepository.getSafetyPlacesNear(point, maxDistanceKm = 5.0)
        val nearestFacilityDistance = safetyPlacesNear
            .map { RiskZoneRepository.distanceMeters(point, it.location) }
            .minOrNull()
        val nearbyPoiCount = nearbyPoiCount(point, crimeZones, safetyPlaces)
        val nearestMajorRoad = nearestLikelyMajorRoadMeters(point, crimeZones)
        val isolation = isolationScoreCalculator.calculate(location, safetyPlacesNear.ifEmpty { safetyPlaces }, nearbyPoiCount)
        val hour = Calendar.getInstance().apply { timeInMillis = timestamp }.get(Calendar.HOUR_OF_DAY)
        val isEvening = hour in 18..21
        val isLateNight = hour in 22..23 || hour in 0..5
        val isNightTime = isEvening || isLateNight
        val lowLight = lowLightHeuristic.assess(
            location = location,
            isNightTime = isNightTime,
            isLateNight = isLateNight,
            lowMovementDensity = isLowMovementDensity(timestamp),
            distanceFromFacilitiesMeters = nearestFacilityDistance,
            distanceFromMajorRoadMeters = nearestMajorRoad
        )

        val zoneType = dominantZoneType(highCrimeZone, lowLight, isolation)
        val reasons = buildRiskReasons(
            zoneType = zoneType,
            highCrimeZone = highCrimeZone,
            isEvening = isEvening,
            isLateNight = isLateNight,
            lowLight = lowLight,
            isolation = isolation,
            timestamp = timestamp
        )
        val score = reasons.sumOf { it.score }.coerceIn(0, 100)
        val confidence = calculateConfidence(zoneType, reasons, highCrimeZone, lowLight, isolation)
        val nextState = TransitionRiskState(
            currentRiskScore = score,
            transitionZoneType = zoneType,
            confidence = confidence,
            reasons = reasons.map { it.explanation },
            reasonDetails = reasons,
            isolationScore = isolation,
            lowLightAssessment = lowLight,
            location = location,
            updatedAt = timestamp
        )

        _state.value = nextState
        emitAlertEventIfNeeded(nextState)
    }

    private fun buildRiskReasons(
        zoneType: TransitionZoneType,
        highCrimeZone: CrimeRiskZone?,
        isEvening: Boolean,
        isLateNight: Boolean,
        lowLight: LowLightAssessment,
        isolation: IsolationScore,
        timestamp: Long
    ): List<TransitionRiskReason> {
        val reasons = mutableListOf<TransitionRiskReason>()
        config.weightFor(zoneType).takeIf { it > 0 }?.let {
            reasons.add(TransitionRiskReason(labelFor(zoneType), it))
        }
        if (activeTransitionType == TransitionType.UNEXPECTED_STOP) {
            reasons.add(TransitionRiskReason("Unexpected Stop", config.unexpectedStopWeight, 0.83f))
        }
        if (highCrimeZone != null) {
            reasons.add(
                TransitionRiskReason(
                    "High Crime Zone",
                    config.highCrimeRiskWeight,
                    highCrimeZone.crimeRiskScore.coerceIn(0f, 1f)
                )
            )
        }
        if (isLateNight) {
            reasons.add(TransitionRiskReason("Late Night", config.lateNightRiskWeight))
        } else if (isEvening) {
            reasons.add(TransitionRiskReason("Night Time", config.eveningRiskWeight))
        }
        if (lowLight.likelyLowLight) {
            reasons.add(TransitionRiskReason("Low Light Area", config.lowLightRiskWeight, lowLight.confidence))
        }
        if (isolation.score >= ISOLATION_RISK_THRESHOLD) {
            reasons.add(TransitionRiskReason("Isolation", config.isolationRiskWeight, isolation.confidence))
        }
        lastMileMinuteBonus(timestamp).takeIf { it > 0 }?.let {
            reasons.add(TransitionRiskReason("Last Mile Duration", it, 0.82f))
        }
        return reasons.distinctBy { it.label }
    }

    private fun dominantZoneType(
        highCrimeZone: CrimeRiskZone?,
        lowLight: LowLightAssessment,
        isolation: IsolationScore
    ): TransitionZoneType {
        return when {
            activeZoneType == TransitionZoneType.LAST_MILE_WALK -> TransitionZoneType.LAST_MILE_WALK
            activeZoneType != TransitionZoneType.UNKNOWN_TRANSITION -> activeZoneType
            highCrimeZone != null -> TransitionZoneType.HIGH_CRIME_TRANSITION
            lowLight.likelyLowLight -> TransitionZoneType.LOW_LIGHT_AREA
            isolation.score >= ISOLATION_RISK_THRESHOLD -> TransitionZoneType.ISOLATED_ROAD
            else -> TransitionZoneType.UNKNOWN_TRANSITION
        }
    }

    private fun zoneTypeFor(type: TransitionType): TransitionZoneType {
        return when (type) {
            TransitionType.STATION_EXIT_DETECTED,
            TransitionType.TRAIN_LIKELY_DETECTED -> TransitionZoneType.STATION_EXIT
            TransitionType.CAB_PICKUP_DETECTED -> TransitionZoneType.CAB_PICKUP_POINT
            TransitionType.CAB_DROP_DETECTED,
            TransitionType.VEHICLE_EXITED -> TransitionZoneType.CAB_DROP_POINT
            TransitionType.LAST_MILE_WALK_STARTED -> TransitionZoneType.LAST_MILE_WALK
            TransitionType.HIGH_RISK_ZONE_ENTERED -> TransitionZoneType.HIGH_CRIME_TRANSITION
            TransitionType.UNEXPECTED_STOP -> TransitionZoneType.UNKNOWN_TRANSITION
            else -> activeZoneType
        }
    }

    private fun matchingHighCrimeZone(point: LatLng, zones: List<CrimeRiskZone>): CrimeRiskZone? {
        return zones
            .filter { zone ->
                zone.crimeRiskScore >= HIGH_CRIME_THRESHOLD &&
                    RiskZoneRepository.distanceMeters(point, zone.location) <= zone.radiusMeters
            }
            .maxByOrNull { it.crimeRiskScore }
    }

    private fun nearbyPoiCount(
        point: LatLng,
        crimeZones: List<CrimeRiskZone>,
        safetyPlaces: List<SafetyPlace>
    ): Int {
        val safetyCount = safetyPlaces.count {
            RiskZoneRepository.distanceMeters(point, it.location) <= POI_RADIUS_METERS
        }
        val hotspotCount = crimeZones.sumOf { zone ->
            zone.hotspots.count { hotspot ->
                RiskZoneRepository.distanceMeters(point, hotspot.location) <= POI_RADIUS_METERS
            }
        }
        return safetyCount + hotspotCount
    }

    private fun nearestLikelyMajorRoadMeters(point: LatLng, crimeZones: List<CrimeRiskZone>): Float? {
        return crimeZones
            .flatMap { it.hotspots }
            .filter { hotspot -> MAJOR_ROAD_KEYWORDS.any { hotspot.label.contains(it, ignoreCase = true) } }
            .map { RiskZoneRepository.distanceMeters(point, it.location) }
            .minOrNull()
    }

    private fun isLowMovementDensity(now: Long): Boolean {
        val recent = locationHistory.filter { now - it.timestamp <= MOVEMENT_DENSITY_WINDOW_MS }
        if (recent.size < 2) return true
        val movingSamples = recent.count { it.speed >= WALKING_SPEED_MPS }
        return movingSamples <= recent.size / 3
    }

    private fun lastMileMinuteBonus(now: Long): Int {
        val startedAt = if (activeZoneType == TransitionZoneType.LAST_MILE_WALK) {
            activeTransitionStartedAt
        } else {
            cabDropDetectedAt
        } ?: return 0
        if (currentActivity != JourneyActivityType.WALKING &&
            currentPhase != JourneyPhase.LAST_MILE_WALK &&
            activeZoneType != TransitionZoneType.LAST_MILE_WALK
        ) {
            return 0
        }
        val minutes = ((now - startedAt) / 60_000L).toInt()
        return if (minutes > 3) ((minutes - 3) * config.lastMileMinuteRiskWeight).coerceAtMost(15) else 0
    }

    private fun calculateConfidence(
        zoneType: TransitionZoneType,
        reasons: List<TransitionRiskReason>,
        highCrimeZone: CrimeRiskZone?,
        lowLight: LowLightAssessment,
        isolation: IsolationScore
    ): Float {
        val zoneConfidence = if (zoneType == TransitionZoneType.UNKNOWN_TRANSITION) 0.45f else 0.72f
        val reasonConfidence = reasons.map { it.confidence }.ifEmpty { listOf(0.45f) }.average().toFloat()
        val dataConfidence = listOfNotNull(
            highCrimeZone?.crimeRiskScore,
            lowLight.confidence.takeIf { lowLight.likelyLowLight },
            isolation.confidence.takeIf { isolation.score >= ISOLATION_RISK_THRESHOLD }
        ).ifEmpty { listOf(0.5f) }.average().toFloat()
        return (zoneConfidence * 0.45f + reasonConfidence * 0.35f + dataConfidence * 0.2f)
            .coerceIn(0f, 0.98f)
    }

    private fun emitAlertEventIfNeeded(state: TransitionRiskState) {
        if (state.currentRiskScore < config.alertThreshold) return
        if (state.currentRiskScore < lastAlertScore + ALERT_SCORE_STEP) return
        lastAlertScore = state.currentRiskScore
        _riskEvents.tryEmit(
            TransitionRiskEvent(
                state = state,
                title = "Elevated Risk Detected",
                description = state.reasonDetails
                    .take(3)
                    .joinToString(separator = ", ") { it.label }
                    .ifBlank { labelFor(state.transitionZoneType) }
            )
        )
    }

    private fun trimLocationHistory(now: Long) {
        while (locationHistory.isNotEmpty() && now - locationHistory.first().timestamp > LOCATION_HISTORY_MS) {
            locationHistory.removeFirst()
        }
    }

    private fun latestLocation(): LocationData? = locationHistory.lastOrNull()

    private fun labelFor(zoneType: TransitionZoneType): String {
        return when (zoneType) {
            TransitionZoneType.STATION_EXIT -> "Station Exit"
            TransitionZoneType.METRO_EXIT -> "Metro Exit"
            TransitionZoneType.BUS_STOP_EXIT -> "Bus Stop Exit"
            TransitionZoneType.CAB_PICKUP_POINT -> "Cab Pickup Point"
            TransitionZoneType.CAB_DROP_POINT -> "Cab Drop Point"
            TransitionZoneType.AUTO_STAND -> "Auto Stand"
            TransitionZoneType.PARKING_AREA -> "Parking Area"
            TransitionZoneType.LAST_MILE_WALK -> "Last Mile Walk"
            TransitionZoneType.ISOLATED_ROAD -> "Isolated Road"
            TransitionZoneType.LOW_LIGHT_AREA -> "Low Light Area"
            TransitionZoneType.HIGH_CRIME_TRANSITION -> "High Crime Transition"
            TransitionZoneType.UNKNOWN_TRANSITION -> "Unknown Transition"
        }
    }

    companion object {
        private const val HIGH_CRIME_THRESHOLD = 0.55f
        private const val ISOLATION_RISK_THRESHOLD = 45
        private const val POI_RADIUS_METERS = 800f
        private const val MOVEMENT_DENSITY_WINDOW_MS = 4 * 60_000L
        private const val LOCATION_HISTORY_MS = 20 * 60_000L
        private const val WALKING_SPEED_MPS = 1.0f
        private const val ALERT_SCORE_STEP = 8
        private val MAJOR_ROAD_KEYWORDS = listOf(
            "road",
            "highway",
            "junction",
            "circle",
            "crossing",
            "bus",
            "station",
            "metro"
        )
    }
}
