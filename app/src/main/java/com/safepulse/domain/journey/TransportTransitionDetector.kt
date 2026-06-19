package com.safepulse.domain.journey

import com.safepulse.domain.model.LocationData
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlin.math.abs

data class RiskZoneSnapshot(
    val isInsideRiskZone: Boolean,
    val riskScore: Int,
    val source: String
)

data class DestinationSnapshot(
    val latitude: Double,
    val longitude: Double,
    val radiusMeters: Float = 120f
)

data class StationVicinitySnapshot(
    val isNearStation: Boolean,
    val label: String? = null,
    val confidence: Float = 0.65f
)

class TransportTransitionDetector {
    private val _transitionEvent = MutableSharedFlow<TransitionEvent>(extraBufferCapacity = 16)
    val transitionEvent: SharedFlow<TransitionEvent> = _transitionEvent.asSharedFlow()

    private val locationHistory = ArrayDeque<LocationData>()
    private val emittedTransitionKeys = mutableMapOf<TransitionType, Long>()

    private var currentActivity: JourneyActivityType = JourneyActivityType.UNKNOWN
    private var activityStartedAt: Long = 0L
    private var walkingStartedAt: Long? = null
    private var walkingStartedEmitted = false
    private var walkingConfidence = 0.78f
    private var stillStartedAt: Long? = null
    private var vehicleStartedAt: Long? = null
    private var vehicleStoppedAt: Long? = null
    private var cabDropDetectedAt: Long? = null
    private var lastMileWalkEmitted = false
    private var trainCandidateStartedAt: Long? = null
    private var trainDetectedAt: Long? = null
    private var trainStoppedAt: Long? = null
    private var trainLikelyActive = false
    private var wasInsideRiskZone = false
    private var latestRiskScore = 0
    private var latestLocation: LocationData? = null
    private var latestStationVicinity = StationVicinitySnapshot(isNearStation = false)
    private var destinationSnapshot: DestinationSnapshot? = null

    fun reset() {
        locationHistory.clear()
        emittedTransitionKeys.clear()
        currentActivity = JourneyActivityType.UNKNOWN
        activityStartedAt = 0L
        walkingStartedAt = null
        walkingStartedEmitted = false
        walkingConfidence = 0.78f
        stillStartedAt = null
        vehicleStartedAt = null
        vehicleStoppedAt = null
        cabDropDetectedAt = null
        lastMileWalkEmitted = false
        trainCandidateStartedAt = null
        trainDetectedAt = null
        trainStoppedAt = null
        trainLikelyActive = false
        wasInsideRiskZone = false
        latestRiskScore = 0
        latestLocation = null
        latestStationVicinity = StationVicinitySnapshot(isNearStation = false)
        destinationSnapshot = null
    }

    fun processActivity(update: JourneyActivityUpdate) {
        if (update.confidence < MIN_ACTIVITY_CONFIDENCE) return
        val nextActivity = update.type
        if (nextActivity == JourneyActivityType.UNKNOWN || nextActivity == currentActivity) return

        val previousActivity = currentActivity
        val previousActivityDuration = if (activityStartedAt > 0L) {
            update.timestamp - activityStartedAt
        } else {
            0L
        }

        currentActivity = nextActivity
        activityStartedAt = update.timestamp

        when (nextActivity) {
            JourneyActivityType.WALKING -> handleWalkingStarted(previousActivity, update, previousActivityDuration)
            JourneyActivityType.IN_VEHICLE -> handleVehicleEntered(previousActivity, update)
            JourneyActivityType.STILL -> handleStillStarted(previousActivity, update)
            JourneyActivityType.UNKNOWN -> Unit
        }
    }

    fun processLocation(location: LocationData) {
        latestLocation = location
        locationHistory.addLast(location)
        trimLocationHistory(location.timestamp)
        evaluateConfirmedWalking(location.timestamp)
        evaluateTrainHeuristic(location.timestamp)
        evaluateLastMileWalk(location.timestamp)
        evaluateUnexpectedStop(location.timestamp)
    }

    fun processRiskZone(snapshot: RiskZoneSnapshot) {
        latestRiskScore = snapshot.riskScore.coerceIn(0, 100)
        val location = latestLocation ?: return

        if (!wasInsideRiskZone && snapshot.isInsideRiskZone) {
            emitTransition(
                type = TransitionType.HIGH_RISK_ZONE_ENTERED,
                timestamp = location.timestamp,
                confidence = confidenceFromRiskScore(latestRiskScore),
                description = "High Risk Zone Entered. Reason: ${snapshot.source}; risk score is $latestRiskScore.",
                location = location
            )
        } else if (wasInsideRiskZone && !snapshot.isInsideRiskZone) {
            emitTransition(
                type = TransitionType.HIGH_RISK_ZONE_EXITED,
                timestamp = location.timestamp,
                confidence = 0.82f,
                description = "High Risk Zone Exited. Reason: location moved outside known risk zone boundaries.",
                location = location
            )
        }

        wasInsideRiskZone = snapshot.isInsideRiskZone
    }

    fun processStationVicinity(snapshot: StationVicinitySnapshot) {
        latestStationVicinity = snapshot
    }

    fun processDestination(snapshot: DestinationSnapshot?) {
        destinationSnapshot = snapshot
    }

    fun markJourneyStarted(location: LocationData?) {
        val eventLocation = location ?: latestLocation ?: LocationData(0.0, 0.0)
        emitTransition(
            type = TransitionType.JOURNEY_STARTED,
            timestamp = System.currentTimeMillis(),
            confidence = 1.0f,
            description = "Journey Started. Reason: user started a continuous SafePulse journey session.",
            location = eventLocation
        )
    }

    fun markDestinationReached(location: LocationData?) {
        val eventLocation = location ?: latestLocation ?: LocationData(0.0, 0.0)
        emitTransition(
            type = TransitionType.DESTINATION_REACHED,
            timestamp = System.currentTimeMillis(),
            confidence = 1.0f,
            description = "Destination Reached. Reason: user completed the active journey.",
            location = eventLocation
        )
    }

    private fun handleWalkingStarted(
        previousActivity: JourneyActivityType,
        update: JourneyActivityUpdate,
        previousActivityDuration: Long
    ) {
        walkingStartedAt = update.timestamp
        walkingStartedEmitted = false
        walkingConfidence = activityConfidence(update)
        stillStartedAt = null
        val location = latestLocation ?: return

        val stopTime = vehicleStoppedAt
        if (previousActivity == JourneyActivityType.STILL &&
            stopTime != null &&
            update.timestamp - stopTime <= CAB_DROP_WINDOW_MS
        ) {
            emitTransition(
                type = TransitionType.VEHICLE_EXITED,
                timestamp = update.timestamp,
                confidence = 0.82f,
                description = "Vehicle Exited. Reason: in-vehicle movement stopped, phone became still, then walking resumed.",
                location = location
            )
            cabDropDetectedAt = update.timestamp
            lastMileWalkEmitted = false
            emitTransition(
                type = TransitionType.CAB_DROP_DETECTED,
                timestamp = update.timestamp,
                confidence = 0.87f,
                description = "Cab Drop Detected. Reason: vehicle movement ended and walking began within 5 minutes.",
                location = location
            )
        }

        val trainStopTime = trainStoppedAt
        if (previousActivity == JourneyActivityType.STILL &&
            trainLikelyActive &&
            trainStopTime != null &&
            update.timestamp - trainStopTime <= STATION_EXIT_WINDOW_MS
        ) {
            val stationConfidence = if (latestStationVicinity.isNearStation) {
                latestStationVicinity.confidence.coerceIn(0.7f, 0.92f)
            } else {
                0.74f
            }
            val stationReason = if (latestStationVicinity.isNearStation) {
                "near ${latestStationVicinity.label ?: "a likely station area"}"
            } else {
                "using movement-only station heuristic"
            }
            emitTransition(
                type = TransitionType.STATION_EXIT_DETECTED,
                timestamp = update.timestamp,
                confidence = stationConfidence,
                description = "Station Exit Detected. Reason: likely train movement ended, phone became still, then walking resumed $stationReason.",
                location = location
            )
            trainLikelyActive = false
            trainStoppedAt = null
        }

        if (previousActivity == JourneyActivityType.STILL && previousActivityDuration >= STILL_CONFIRMATION_MS) {
            vehicleStoppedAt = null
        }
    }

    private fun handleVehicleEntered(
        previousActivity: JourneyActivityType,
        update: JourneyActivityUpdate
    ) {
        vehicleStartedAt = update.timestamp
        stillStartedAt = null
        val location = latestLocation ?: return
        emitWalkingStartedIfConfirmed(update.timestamp, location)

        emitTransition(
            type = TransitionType.VEHICLE_ENTERED,
            timestamp = update.timestamp,
            confidence = activityConfidence(update),
            description = "Vehicle Entered. Reason: Activity Recognition changed to in-vehicle.",
            location = location
        )

        val walkingStart = walkingStartedAt
        if (previousActivity == JourneyActivityType.WALKING &&
            walkingStart != null &&
            update.timestamp - walkingStart >= CAB_PICKUP_MIN_WALK_MS
        ) {
            emitTransition(
                type = TransitionType.CAB_PICKUP_DETECTED,
                timestamp = update.timestamp,
                confidence = 0.88f,
                description = "Cab Pickup Detected. Reason: walking lasted more than 2 minutes and was followed by in-vehicle movement.",
                location = location
            )
        }
    }

    private fun handleStillStarted(
        previousActivity: JourneyActivityType,
        update: JourneyActivityUpdate
    ) {
        stillStartedAt = update.timestamp
        val location = latestLocation ?: return

        if (previousActivity == JourneyActivityType.WALKING) {
            emitTransition(
                type = TransitionType.WALKING_STOPPED,
                timestamp = update.timestamp,
                confidence = activityConfidence(update),
                description = "Walking Stopped. Reason: Activity Recognition changed from walking to still.",
                location = location
            )
        }

        if (previousActivity == JourneyActivityType.IN_VEHICLE) {
            vehicleStoppedAt = update.timestamp
            if (trainLikelyActive) {
                trainStoppedAt = update.timestamp
            }
        }
    }

    private fun evaluateConfirmedWalking(now: Long) {
        val location = latestLocation ?: return
        if (currentActivity != JourneyActivityType.WALKING) return
        emitWalkingStartedIfConfirmed(now, location)
    }

    private fun emitWalkingStartedIfConfirmed(now: Long, location: LocationData) {
        val walkingStart = walkingStartedAt ?: return
        if (walkingStartedEmitted) return
        if (now - walkingStart < WALKING_CONFIRMATION_MS) return

        walkingStartedEmitted = true
        emitTransition(
            type = TransitionType.WALKING_STARTED,
            timestamp = now,
            confidence = walkingConfidence,
            description = "Walking Started. Reason: walking persisted for more than 2 minutes.",
            location = location
        )
    }

    private fun evaluateLastMileWalk(now: Long) {
        val cabDropTime = cabDropDetectedAt ?: return
        val walkingStart = walkingStartedAt ?: return
        val location = latestLocation ?: return
        if (lastMileWalkEmitted || currentActivity != JourneyActivityType.WALKING) return

        if (now - cabDropTime >= LAST_MILE_WALK_MIN_MS && now - walkingStart >= LAST_MILE_WALK_MIN_MS) {
            lastMileWalkEmitted = true
            emitTransition(
                type = TransitionType.LAST_MILE_WALK_STARTED,
                timestamp = now,
                confidence = 0.84f,
                description = "Last Mile Walk Started. Reason: walking continued for more than 3 minutes after cab drop.",
                location = location
            )
        }
    }

    private fun evaluateUnexpectedStop(now: Long) {
        val stillStart = stillStartedAt ?: return
        val location = latestLocation ?: return
        if (currentActivity != JourneyActivityType.STILL) return
        if (isInsideDestinationRadius(location)) return

        if (latestRiskScore >= UNEXPECTED_STOP_RISK_THRESHOLD && now - stillStart >= UNEXPECTED_STOP_MIN_MS) {
            emitTransition(
                type = TransitionType.UNEXPECTED_STOP,
                timestamp = now,
                confidence = 0.83f,
                description = "Unexpected Stop. Reason: user remained still for more than 5 minutes while risk score was $latestRiskScore and location was outside the destination radius.",
                location = location
            )
        }
    }

    private fun evaluateTrainHeuristic(now: Long) {
        val window = locationHistory.filter { now - it.timestamp <= TRAIN_MIN_DURATION_MS }
        if (window.size < MIN_TRAIN_SAMPLES) {
            trainCandidateStartedAt = null
            return
        }

        val movingSamples = window.filter { it.speed >= TRAIN_SPEED_MPS }
        val fastRatio = movingSamples.size.toFloat() / window.size.toFloat()
        val routeSpan = window.last().timestamp - window.first().timestamp
        val bearingChanges = bearingChanges(window)
        val stopCount = countBriefStops(window)
        val linearConfidence = (1f - (bearingChanges / 90f)).coerceIn(0f, 1f)
        val speedConfidence = fastRatio.coerceIn(0f, 1f)
        val stopConfidence = (stopCount / 2f).coerceIn(0f, 1f)
        val confidence = (speedConfidence * 0.5f + linearConfidence * 0.3f + stopConfidence * 0.2f)
            .coerceIn(0f, 1f)

        if (routeSpan >= TRAIN_MIN_DURATION_MS && confidence >= TRAIN_CONFIDENCE_THRESHOLD) {
            val candidateStart = trainCandidateStartedAt ?: window.first().timestamp.also {
                trainCandidateStartedAt = it
            }
            if (!trainLikelyActive && now - candidateStart >= TRAIN_MIN_DURATION_MS) {
                trainLikelyActive = true
                trainDetectedAt = now
                emitTransition(
                    type = TransitionType.TRAIN_LIKELY_DETECTED,
                    timestamp = now,
                    confidence = confidence,
                    description = "Train Likely Detected. Reason: speed stayed above 40 km/h, route direction was mostly linear, and repeated stop intervals were observed for over 10 minutes.",
                    location = window.last()
                )
            }
        } else {
            trainCandidateStartedAt = null
        }
    }

    private fun emitTransition(
        type: TransitionType,
        timestamp: Long,
        confidence: Float,
        description: String,
        location: LocationData
    ) {
        val lastEmittedAt = emittedTransitionKeys[type]
        if (lastEmittedAt != null && timestamp - lastEmittedAt < duplicateWindowFor(type)) return

        emittedTransitionKeys[type] = timestamp
        _transitionEvent.tryEmit(
            TransitionEvent(
            timestamp = timestamp,
            transitionType = type,
            latitude = location.latitude,
            longitude = location.longitude,
            confidence = confidence.coerceIn(0f, 1f),
            description = description
            )
        )
    }

    private fun trimLocationHistory(now: Long) {
        while (locationHistory.isNotEmpty() && now - locationHistory.first().timestamp > LOCATION_HISTORY_MS) {
            locationHistory.removeFirst()
        }
    }

    private fun bearingChanges(samples: List<LocationData>): Float {
        val bearings = samples.zipWithNext()
            .filter { (a, b) -> distanceMeters(a, b) >= MIN_BEARING_DISTANCE_METERS }
            .map { (a, b) -> bearingDegrees(a, b) }
        if (bearings.size < 2) return 0f

        return bearings.zipWithNext()
            .sumOf { (a, b) -> abs(shortestBearingDelta(a, b)).toDouble() }
            .toFloat() / (bearings.size - 1)
    }

    private fun countBriefStops(samples: List<LocationData>): Int {
        var stops = 0
        var inStop = false
        for (sample in samples) {
            if (sample.speed <= STOP_SPEED_MPS) {
                if (!inStop) {
                    stops++
                    inStop = true
                }
            } else {
                inStop = false
            }
        }
        return stops
    }

    private fun distanceMeters(a: LocationData, b: LocationData): Float {
        val results = FloatArray(1)
        android.location.Location.distanceBetween(
            a.latitude,
            a.longitude,
            b.latitude,
            b.longitude,
            results
        )
        return results[0]
    }

    private fun bearingDegrees(a: LocationData, b: LocationData): Float {
        val start = android.location.Location("start").apply {
            latitude = a.latitude
            longitude = a.longitude
        }
        val end = android.location.Location("end").apply {
            latitude = b.latitude
            longitude = b.longitude
        }
        return start.bearingTo(end)
    }

    private fun shortestBearingDelta(a: Float, b: Float): Float {
        var delta = (b - a + 540f) % 360f - 180f
        if (delta < -180f) delta += 360f
        return delta
    }

    private fun activityConfidence(update: JourneyActivityUpdate): Float {
        return (update.confidence / 100f).coerceIn(0.5f, 1f)
    }

    private fun confidenceFromRiskScore(riskScore: Int): Float {
        return (0.65f + (riskScore.coerceIn(0, 100) / 100f) * 0.3f).coerceIn(0f, 1f)
    }

    private fun isInsideDestinationRadius(location: LocationData): Boolean {
        val destination = destinationSnapshot ?: return false
        val destinationLocation = LocationData(
            latitude = destination.latitude,
            longitude = destination.longitude
        )
        return distanceMeters(location, destinationLocation) <= destination.radiusMeters
    }

    private fun duplicateWindowFor(type: TransitionType): Long {
        return when (type) {
            TransitionType.HIGH_RISK_ZONE_ENTERED,
            TransitionType.HIGH_RISK_ZONE_EXITED -> 10 * 60_000L
            TransitionType.UNEXPECTED_STOP -> 10 * 60_000L
            TransitionType.TRAIN_LIKELY_DETECTED -> 30 * 60_000L
            else -> 60_000L
        }
    }

    companion object {
        private const val MIN_ACTIVITY_CONFIDENCE = 50
        private const val WALKING_CONFIRMATION_MS = 2 * 60_000L
        private const val CAB_PICKUP_MIN_WALK_MS = 2 * 60_000L
        private const val CAB_DROP_WINDOW_MS = 5 * 60_000L
        private const val LAST_MILE_WALK_MIN_MS = 3 * 60_000L
        private const val TRAIN_MIN_DURATION_MS = 10 * 60_000L
        private const val TRAIN_SPEED_MPS = 40f / 3.6f
        private const val TRAIN_CONFIDENCE_THRESHOLD = 0.75f
        private const val STATION_EXIT_WINDOW_MS = 8 * 60_000L
        private const val STILL_CONFIRMATION_MS = 30_000L
        private const val UNEXPECTED_STOP_MIN_MS = 5 * 60_000L
        private const val UNEXPECTED_STOP_RISK_THRESHOLD = 65
        private const val LOCATION_HISTORY_MS = 15 * 60_000L
        private const val MIN_TRAIN_SAMPLES = 12
        private const val STOP_SPEED_MPS = 1.5f
        private const val MIN_BEARING_DISTANCE_METERS = 25f
    }
}
