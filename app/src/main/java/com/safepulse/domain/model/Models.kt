package com.safepulse.domain.model

/**
 * Current location data
 */
data class LocationData(
    val latitude: Double,
    val longitude: Double,
    val accuracy: Float = 0f,
    val speed: Float = 0f,
    val timestamp: Long = System.currentTimeMillis()
)

/**
 * Sensor readings from accelerometer and gyroscope
 */
data class SensorData(
    val accelerometerX: Float = 0f,
    val accelerometerY: Float = 0f,
    val accelerometerZ: Float = 0f,
    val gyroscopeX: Float = 0f,
    val gyroscopeY: Float = 0f,
    val gyroscopeZ: Float = 0f,
    val timestamp: Long = System.currentTimeMillis()
) {
    /**
     * Calculate total acceleration magnitude
     */
    val accelerationMagnitude: Float
        get() = kotlin.math.sqrt(
            accelerometerX * accelerometerX +
            accelerometerY * accelerometerY +
            accelerometerZ * accelerometerZ
        )
    
    /**
     * Calculate total rotation magnitude
     */
    val rotationMagnitude: Float
        get() = kotlin.math.sqrt(
            gyroscopeX * gyroscopeX +
            gyroscopeY * gyroscopeY +
            gyroscopeZ * gyroscopeZ
        )
}

/**
 * Result of risk scoring calculation
 */
data class RiskResult(
    val level: RiskLevel,
    val score: Float,  // 0.0 to 1.0
    val isInHotspot: Boolean = false,
    val isInUnsafeZone: Boolean = false,
    val nearestZoneDistance: Float = Float.MAX_VALUE
)

/**
 * Detected safety event with confidence
 */
data class DetectedEvent(
    val type: EventType,
    val confidence: Float,  // 0.0 to 1.0
    val location: LocationData?,
    val timestamp: Long = System.currentTimeMillis()
)

/**
 * Current safety state
 */
data class SafetyState(
    val mode: SafetyMode,
    val riskLevel: RiskLevel,
    val riskScore: Float,
    val isEmergency: Boolean,
    val currentEvent: DetectedEvent?,
    val movementState: MovementState,
    val location: LocationData?
)

/**
 * User profile for safety calculations
 */
data class UserProfile(
    val gender: Gender,
    val voiceTriggerEnabled: Boolean
)

/**
 * Emergency event ready for SOS
 */
data class EmergencyEvent(
    val type: EventType,
    val confidence: Float,
    val location: LocationData?,
    val timestamp: Long = System.currentTimeMillis(),
    val requiresConfirmation: Boolean = true,
    val silent: Boolean = false // True for discreet alerts (SMS only, no call)
)
