package com.safepulse.domain.model

/**
 * User gender for safety profile
 */
enum class Gender {
    MALE,
    FEMALE,
    UNSPECIFIED
}

/**
 * Safety mode based on current conditions
 */
enum class SafetyMode {
    NORMAL,
    HEIGHTENED  // Activated for female users in unsafe zones at night
}

/**
 * Risk level classification
 */
enum class RiskLevel {
    LOW,
    MEDIUM,
    HIGH
}

/**
 * Type of detected safety event
 */
enum class EventType {
    ROAD_ACCIDENT,
    FALL,
    POSSIBLE_ASSAULT,
    HIGH_RISK_ZONE,
    INACTIVITY_ALERT,
    VOICE_TRIGGER,
    MANUAL_SOS
}

/**
 * Movement state of the user
 */
enum class MovementState {
    MOVING,
    STATIONARY,
    STOPPED_RECENTLY  // Was moving, now stopped
}
