package com.safepulse.domain.journey

enum class JourneyActivityType {
    WALKING,
    STILL,
    IN_VEHICLE,
    UNKNOWN
}

data class JourneyActivityUpdate(
    val type: JourneyActivityType,
    val confidence: Int,
    val timestamp: Long = System.currentTimeMillis()
)

object JourneyActivityPhaseMapper {
    fun map(update: JourneyActivityUpdate, currentPhase: JourneyPhase?): JourneyPhase? {
        if (update.confidence < 50) return null

        return when (update.type) {
            JourneyActivityType.WALKING -> {
                when (currentPhase) {
                    JourneyPhase.IN_VEHICLE,
                    JourneyPhase.TRAIN_TRANSIT,
                    JourneyPhase.STATION_EXIT -> JourneyPhase.LAST_MILE_WALK
                    else -> JourneyPhase.WALKING
                }
            }
            JourneyActivityType.STILL -> JourneyPhase.STATION_EXIT
            JourneyActivityType.IN_VEHICLE -> JourneyPhase.IN_VEHICLE
            JourneyActivityType.UNKNOWN -> null
        }
    }
}
