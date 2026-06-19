package com.safepulse.domain.saferoutes

import com.google.android.gms.maps.model.LatLng
import com.safepulse.data.repository.HotspotRepository
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Analyzes routes for crime risk using existing crime hotspot data
 */
class RouteRiskAnalyzer(
    private val hotspotRepository: HotspotRepository
) {
    companion object {
        private const val DANGER_RADIUS_METERS = 500f  // 500m around crime hotspot
        private const val RISK_WEIGHT_PER_HOTSPOT = 0.15f  // Each hotspot adds 15% risk
    }
    
    /**
     * Analyze a route for crime risk
     * @param routePoints List of lat/lng points along the route
     * @return RouteRisk assessment
     */
    suspend fun analyzeRouteRisk(routePoints: List<LatLng>): RouteRisk {
        // Get all crime hotspots from database
        val hotspots = hotspotRepository.getAllHotspotsList()
        
        var riskScore = 0f
        var dangerousSegments = 0
        var nearbyHotspots = 0
        
        // Check each point on route against crime hotspots
        for (point in routePoints) {
            val hotspotsNearPoint = hotspots.filter { hotspot ->
                val hotspotLocation = LatLng(hotspot.lat, hotspot.lng)
                distanceBetween(point, hotspotLocation) < DANGER_RADIUS_METERS
            }
            
            if (hotspotsNearPoint.isNotEmpty()) {
                riskScore += hotspotsNearPoint.size * RISK_WEIGHT_PER_HOTSPOT
                dangerousSegments++
                nearbyHotspots += hotspotsNearPoint.size
            }
        }
        
        // Normalize risk score to 0.0 - 1.0
        val normalizedScore = minOf(riskScore, 1.0f)
        
        // Categorize risk level
        val riskLevel = when {
            normalizedScore < 0.3f -> RiskLevel.LOW
            normalizedScore < 0.7f -> RiskLevel.MEDIUM
            else -> RiskLevel.HIGH
        }
        
        return RouteRisk(
            score = normalizedScore,
            level = riskLevel,
            dangerousSegments = dangerousSegments,
            crimeHotspots = nearbyHotspots
        )
    }
    
    /**
     * Calculate distance between two lat/lng points (Haversine formula)
     * @return Distance in meters
     */
    private fun distanceBetween(point1: LatLng, point2: LatLng): Float {
        val earthRadius = 6371000f // meters
        
        val lat1Rad = Math.toRadians(point1.latitude)
        val lat2Rad = Math.toRadians(point2.latitude)
        val deltaLat = Math.toRadians(point2.latitude - point1.latitude)
        val deltaLng = Math.toRadians(point2.longitude - point1.longitude)
        
        val a = sin(deltaLat / 2) * sin(deltaLat / 2) +
                cos(lat1Rad) * cos(lat2Rad) *
                sin(deltaLng / 2) * sin(deltaLng / 2)
        
        val c = 2 * atan2(sqrt(a), sqrt(1 - a))
        
        return (earthRadius * c).toFloat()
    }
}
