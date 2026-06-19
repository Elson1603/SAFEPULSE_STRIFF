package com.safepulse.domain.riskmap

import com.google.android.gms.maps.model.LatLng

/**
 * Represents a city-level crime risk zone derived from crime_dataset_india.csv
 */
data class CrimeRiskZone(
    val city: String,
    val state: String,
    val location: LatLng,
    val totalCrimes: Int,
    val violentCrimes: Int,
    val crimeRiskScore: Float,
    val violentCrimeRatio: Float,
    val radiusMeters: Float,
    val dominantCrimes: List<String>,
    val hotspots: List<CrimeHotspot>
)

data class CrimeHotspot(
    val location: LatLng,
    val risk: Float,
    val label: String
)

/**
 * Represents a city-level disaster risk zone derived from landslide.csv
 */
data class DisasterRiskZone(
    val city: String,
    val state: String,
    val location: LatLng,
    val landslideRisk: Float,
    val floodRisk: Float,
    val earthquakeFrequency: Int,
    val avgRainfall: Float,
    val elevation: Int,
    val radiusMeters: Float,
    val riskFactors: List<String>
) {
    val combinedDisasterRisk: Float
        get() = (landslideRisk * 0.4f + floodRisk * 0.4f + (earthquakeFrequency / 5f) * 0.2f)
            .coerceIn(0f, 1f)
}

/**
 * Combined risk data for a location
 */
data class CombinedRiskData(
    val crimeZones: List<CrimeRiskZone>,
    val disasterZones: List<DisasterRiskZone>
)

/**
 * A nearby risk marker for display on the map
 */
data class RiskMarker(
    val location: LatLng,
    val label: String,
    val riskScore: Float,
    val riskType: RiskType,
    val description: String
)

enum class RiskType {
    CRIME_HIGH,
    CRIME_MEDIUM,
    CRIME_LOW,
    LANDSLIDE,
    FLOOD,
    EARTHQUAKE,
    MULTI_HAZARD
}

/**
 * Nearby safety place (police station or hospital)
 */
data class SafetyPlace(
    val id: Long,
    val name: String,
    val type: SafetyPlaceType,
    val address: String,
    val phoneNumber: String,
    val location: LatLng,
    val city: String
)

enum class SafetyPlaceType {
    POLICE, HOSPITAL
}

/**
 * Safe route with risk avoidance scoring
 */
data class SafeRouteOption(
    val name: String,
    val waypoints: List<LatLng>,
    val totalRiskScore: Float,
    val crimeRisk: Float,
    val disasterRisk: Float,
    val distanceKm: Float,
    val isSafest: Boolean = false,
    val warnings: List<String> = emptyList()
)