package com.safepulse.domain.journey

enum class JourneyPhase {
    JOURNEY_STARTED,
    WALKING,
    IN_VEHICLE,
    TRAIN_TRANSIT,
    STATION_EXIT,
    LAST_MILE_WALK,
    ARRIVED,
    EMERGENCY
}

enum class JourneySessionStatus {
    ACTIVE,
    COMPLETED,
    CANCELLED
}
