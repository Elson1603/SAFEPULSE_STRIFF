package com.safepulse.domain.transition

import com.safepulse.domain.model.LocationData

class LowLightHeuristic {
    fun assess(
        location: LocationData,
        isNightTime: Boolean,
        isLateNight: Boolean,
        lowMovementDensity: Boolean,
        distanceFromFacilitiesMeters: Float?,
        distanceFromMajorRoadMeters: Float?
    ): LowLightAssessment {
        var confidence = 0f
        val reasons = mutableListOf<String>()

        if (isLateNight) {
            confidence += 0.45f
            reasons.add("Late night")
        } else if (isNightTime) {
            confidence += 0.3f
            reasons.add("After sunset")
        }

        if (lowMovementDensity || location.speed <= LOW_SPEED_MPS) {
            confidence += 0.2f
            reasons.add("Low movement density")
        }

        if (distanceFromFacilitiesMeters == null || distanceFromFacilitiesMeters > FAR_FROM_FACILITIES_METERS) {
            confidence += 0.2f
            reasons.add("Far from known facilities")
        }

        if (distanceFromMajorRoadMeters == null || distanceFromMajorRoadMeters > FAR_FROM_MAJOR_ROAD_METERS) {
            confidence += 0.15f
            reasons.add("Away from likely major roads")
        }

        val boundedConfidence = confidence.coerceIn(0f, 0.95f)
        return LowLightAssessment(
            likelyLowLight = boundedConfidence >= LOW_LIGHT_CONFIDENCE,
            confidence = boundedConfidence,
            reasons = reasons
        )
    }

    companion object {
        private const val LOW_SPEED_MPS = 1.2f
        private const val FAR_FROM_FACILITIES_METERS = 1_000f
        private const val FAR_FROM_MAJOR_ROAD_METERS = 450f
        private const val LOW_LIGHT_CONFIDENCE = 0.55f
    }
}
