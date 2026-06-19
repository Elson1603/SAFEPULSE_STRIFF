package com.safepulse.domain.transition

import com.safepulse.domain.model.LocationData

data class TransitionRiskReason(
    val label: String,
    val score: Int,
    val confidence: Float = 1f
) {
    val explanation: String
        get() = "+$score $label"
}

data class IsolationScore(
    val score: Int,
    val confidence: Float,
    val nearestPoliceMeters: Float?,
    val nearestHospitalMeters: Float?,
    val nearestEmergencyServiceMeters: Float?,
    val nearbyPointsOfInterestCount: Int,
    val reasons: List<String>
)

data class LowLightAssessment(
    val likelyLowLight: Boolean,
    val confidence: Float,
    val reasons: List<String>
)

data class TransitionRiskState(
    val currentRiskScore: Int = 0,
    val transitionZoneType: TransitionZoneType = TransitionZoneType.UNKNOWN_TRANSITION,
    val confidence: Float = 0f,
    val reasons: List<String> = emptyList(),
    val reasonDetails: List<TransitionRiskReason> = emptyList(),
    val isolationScore: IsolationScore? = null,
    val lowLightAssessment: LowLightAssessment? = null,
    val location: LocationData? = null,
    val updatedAt: Long = System.currentTimeMillis()
) {
    val isElevated: Boolean
        get() = currentRiskScore >= TransitionRiskConfig.DEFAULT.alertThreshold
}

data class TransitionRiskEvent(
    val state: TransitionRiskState,
    val title: String,
    val description: String,
    val timestamp: Long = System.currentTimeMillis()
)

data class TransitionRiskConfig(
    val zoneWeights: Map<TransitionZoneType, Int> = DEFAULT_ZONE_WEIGHTS,
    val unexpectedStopWeight: Int = 25,
    val eveningRiskWeight: Int = 10,
    val lateNightRiskWeight: Int = 20,
    val highCrimeRiskWeight: Int = 30,
    val lowLightRiskWeight: Int = 20,
    val isolationRiskWeight: Int = 10,
    val lastMileMinuteRiskWeight: Int = 3,
    val alertThreshold: Int = 70
) {
    fun weightFor(zoneType: TransitionZoneType): Int {
        return zoneWeights[zoneType] ?: 0
    }

    companion object {
        val DEFAULT_ZONE_WEIGHTS = mapOf(
            TransitionZoneType.STATION_EXIT to 15,
            TransitionZoneType.METRO_EXIT to 15,
            TransitionZoneType.BUS_STOP_EXIT to 12,
            TransitionZoneType.CAB_PICKUP_POINT to 12,
            TransitionZoneType.CAB_DROP_POINT to 20,
            TransitionZoneType.AUTO_STAND to 14,
            TransitionZoneType.PARKING_AREA to 16,
            TransitionZoneType.LAST_MILE_WALK to 25,
            TransitionZoneType.ISOLATED_ROAD to 18,
            TransitionZoneType.LOW_LIGHT_AREA to 20,
            TransitionZoneType.HIGH_CRIME_TRANSITION to 30,
            TransitionZoneType.UNKNOWN_TRANSITION to 8
        )

        val DEFAULT = TransitionRiskConfig()
    }
}
