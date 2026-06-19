package com.safepulse.domain.transition

import com.google.android.gms.maps.model.LatLng
import com.safepulse.data.repository.RiskZoneRepository
import com.safepulse.domain.model.LocationData
import com.safepulse.domain.riskmap.SafetyPlace
import com.safepulse.domain.riskmap.SafetyPlaceType
import kotlin.math.roundToInt

class IsolationScoreCalculator {
    fun calculate(
        location: LocationData,
        safetyPlaces: List<SafetyPlace>,
        nearbyPoiCount: Int
    ): IsolationScore {
        val point = LatLng(location.latitude, location.longitude)
        val policeDistances = safetyPlaces
            .asSequence()
            .filter { it.type == SafetyPlaceType.POLICE }
            .map { RiskZoneRepository.distanceMeters(point, it.location) }
            .toList()
        val hospitalDistances = safetyPlaces
            .asSequence()
            .filter { it.type == SafetyPlaceType.HOSPITAL }
            .map { RiskZoneRepository.distanceMeters(point, it.location) }
            .toList()

        val nearestPolice = policeDistances.minOrNull()
        val nearestHospital = hospitalDistances.minOrNull()
        val nearestEmergency = listOfNotNull(nearestPolice, nearestHospital).minOrNull()

        var score = 0
        val reasons = mutableListOf<String>()

        score += distancePenalty(nearestPolice, POLICE_DISTANCE_METERS)
        score += distancePenalty(nearestHospital, HOSPITAL_DISTANCE_METERS)
        score += distancePenalty(nearestEmergency, EMERGENCY_DISTANCE_METERS)

        if (nearestPolice == null || nearestPolice > POLICE_DISTANCE_METERS) {
            reasons.add("Police station is not close")
        }
        if (nearestHospital == null || nearestHospital > HOSPITAL_DISTANCE_METERS) {
            reasons.add("Hospital is not close")
        }
        if (nearbyPoiCount <= LOW_POI_COUNT) {
            score += 18
            reasons.add("Few nearby safety points")
        } else if (nearbyPoiCount <= MEDIUM_POI_COUNT) {
            score += 8
            reasons.add("Limited nearby safety points")
        }

        val boundedScore = score.coerceIn(0, 100)
        val confidence = (0.55f + boundedScore / 220f).coerceIn(0.55f, 0.95f)

        return IsolationScore(
            score = boundedScore,
            confidence = confidence,
            nearestPoliceMeters = nearestPolice,
            nearestHospitalMeters = nearestHospital,
            nearestEmergencyServiceMeters = nearestEmergency,
            nearbyPointsOfInterestCount = nearbyPoiCount,
            reasons = reasons
        )
    }

    private fun distancePenalty(distanceMeters: Float?, expectedMeters: Float): Int {
        if (distanceMeters == null) return 20
        if (distanceMeters <= expectedMeters) return 0
        return ((distanceMeters - expectedMeters) / expectedMeters * 16f)
            .roundToInt()
            .coerceIn(4, 20)
    }

    companion object {
        private const val POLICE_DISTANCE_METERS = 1_500f
        private const val HOSPITAL_DISTANCE_METERS = 2_000f
        private const val EMERGENCY_DISTANCE_METERS = 1_500f
        private const val LOW_POI_COUNT = 1
        private const val MEDIUM_POI_COUNT = 3
    }
}
