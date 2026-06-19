package com.safepulse.domain.saferoutes

import com.google.android.gms.maps.model.LatLng

/**
 * Represents different levels of safety risk for a route
 */
enum class RiskLevel {
    LOW,      // Green - Safe route
    MEDIUM,   // Orange - Some concerns
    HIGH      // Red - Dangerous, avoid if possible
}

/**
 * Risk assessment for a route
 */
data class RouteRisk(
    val score: Float,              // 0.0 - 1.0 (higher = more dangerous)
    val level: RiskLevel,          // Categorized risk level
    val dangerousSegments: Int = 0,// Number of risky segments
    val crimeHotspots: Int = 0,    // Nearby crime locations
    val disasters: Int = 0         // Active disasters on route
)

/**
 * Safe route with risk assessment
 */
data class SafeRoute(
    val id: String,
    val polyline: String,          // Encoded polyline from Google
    val distance: Long,            // Distance in meters
    val duration: Long,            // Duration in seconds
    val riskScore: Float,          // Overall risk (0.0 - 1.0)
    val riskLevel: RiskLevel,      // Risk category
    val summary: String,           // Route description (e.g., "Via MG Road")
    val isRecommended: Boolean = false  // True if this is the safest
)

/**
 * Types of disasters that can affect routes
 */
enum class DisasterType {
    FLOOD,
    EARTHQUAKE,
    CYCLONE,
    LANDSLIDE,
    HEAVY_RAIN,
    FIRE,
    OTHER
}

/**
 * Severity levels for disasters
 */
enum class Severity {
    LOW,
    MODERATE,
    HIGH,
    CRITICAL
}

/**
 * Active disaster alert
 */
data class DisasterAlert(
    val id: String,
    val type: DisasterType,
    val severity: Severity,
    val location: LatLng,
    val radius: Float,             // Affected area in kilometers
    val startTime: Long,
    val endTime: Long?,
    val description: String,
    val source: String             // IMD, news, etc.
)

/**
 * Recommended vehicle type for a route
 */
enum class RecommendedVehicle(
    val displayName: String,
    val reasoning: String
) {
    TRACKED_CAB(
        "Ola/Uber Cab",
        "GPS tracking, driver verification, CCTV available"
    ),
    AUTO_RICKSHAW(
        "Auto Rickshaw",
        "Widely available, share ride details with contacts"
    ),
    BIKE_TAXI(
        "Bike Taxi (Rapido)",
        "Fast, but share live location with contacts"
    ),
    PUBLIC_BUS(
        "Public Transport",
        "Safe in groups, well-lit stops"
    ),
    WALK(
        "Walking",
        "Route is safe, well-lit, and short distance"
    ),
    AVOID_TRAVEL(
        "⚠️ Avoid Travel",
        "High risk - consider staying safe or alternate time"
    )
}

/**
 * Vehicle recommendation with reasoning
 */
data class VehicleRecommendation(
    val vehicle: RecommendedVehicle,
    val reason: String,
    val safetyTips: List<String> = emptyList()
)
