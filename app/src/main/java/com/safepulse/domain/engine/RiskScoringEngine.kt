package com.safepulse.domain.engine

import com.safepulse.data.db.entity.HotspotEntity
import com.safepulse.data.db.entity.UnsafeZoneEntity
import com.safepulse.domain.model.LocationData
import com.safepulse.domain.model.RiskLevel
import com.safepulse.domain.model.RiskResult
import com.safepulse.utils.SafetyConstants
import java.util.Calendar
import kotlin.math.*

/**
 * Engine for calculating risk scores based on location and zones
 */
class RiskScoringEngine {
    
    /**
     * Calculate accident risk based on nearby hotspots
     */
    fun calculateAccidentRisk(
        location: LocationData,
        hotspots: List<HotspotEntity>
    ): RiskResult {
        if (hotspots.isEmpty()) {
            return RiskResult(RiskLevel.LOW, 0f)
        }
        
        val currentHour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        val timeMultiplier = getTimeMultiplier(currentHour)
        val currentTimeBucket = getTimeBucket(currentHour)
        
        var maxRiskScore = 0f
        var nearestDistance = Float.MAX_VALUE
        var isInHotspot = false
        
        for (hotspot in hotspots) {
            val distance = calculateDistance(
                location.latitude, location.longitude,
                hotspot.lat, hotspot.lng
            )
            
            if (distance < nearestDistance) {
                nearestDistance = distance
            }
            
            // Check if user is within hotspot radius
            if (distance <= hotspot.radiusMeters) {
                isInHotspot = true
                
                // Apply time bucket matching
                val timeBucketMatch = when (hotspot.timeBucket) {
                    "all" -> 1.0f
                    currentTimeBucket -> 1.2f  // Higher risk if time matches
                    else -> 0.7f
                }
                
                // Apply road type factor
                val roadTypeFactor = when (hotspot.roadType) {
                    "highway" -> 1.2f
                    "urban" -> 1.0f
                    "rural" -> 0.9f
                    else -> 1.0f
                }
                
                // Calculate distance decay (closer = higher risk)
                val distanceDecay = 1f - (distance / hotspot.radiusMeters)
                
                // Combine factors
                val riskScore = hotspot.baseRisk * timeMultiplier * timeBucketMatch * 
                               roadTypeFactor * distanceDecay
                
                if (riskScore > maxRiskScore) {
                    maxRiskScore = riskScore
                }
            }
        }
        
        // Clamp score to 0-1 range
        maxRiskScore = maxRiskScore.coerceIn(0f, 1f)
        
        val level = when {
            maxRiskScore >= SafetyConstants.HIGH_RISK_THRESHOLD -> RiskLevel.HIGH
            maxRiskScore >= SafetyConstants.MEDIUM_RISK_THRESHOLD -> RiskLevel.MEDIUM
            else -> RiskLevel.LOW
        }
        
        return RiskResult(
            level = level,
            score = maxRiskScore,
            isInHotspot = isInHotspot,
            nearestZoneDistance = nearestDistance
        )
    }
    
    /**
     * Calculate unsafe zone risk for women's safety
     */
    fun calculateUnsafeZoneRisk(
        location: LocationData,
        unsafeZones: List<UnsafeZoneEntity>
    ): RiskResult {
        if (unsafeZones.isEmpty()) {
            return RiskResult(RiskLevel.LOW, 0f)
        }
        
        val currentHour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        val timeMultiplier = getTimeMultiplier(currentHour)
        
        var maxRiskScore = 0f
        var nearestDistance = Float.MAX_VALUE
        var isInUnsafeZone = false
        
        for (zone in unsafeZones) {
            val distance = calculateDistance(
                location.latitude, location.longitude,
                zone.lat, zone.lng
            )
            
            if (distance < nearestDistance) {
                nearestDistance = distance
            }
            
            if (distance <= zone.radiusMeters) {
                isInUnsafeZone = true
                
                // Calculate composite unsafe score
                // Higher crime score = higher risk
                // Lower lighting score = higher risk
                // Lower footfall score = higher risk
                val environmentScore = zone.crimeScore * 
                                      (1f - zone.lightingScore * 0.3f) * 
                                      (1f - zone.footfallScore * 0.3f)
                
                // Distance decay
                val distanceDecay = 1f - (distance / zone.radiusMeters)
                
                // Night time amplifies unsafe zone risk significantly
                val nightAmplifier = if (currentHour in 22..23 || currentHour in 0..5) 1.5f else 1.0f
                
                val riskScore = environmentScore * timeMultiplier * distanceDecay * nightAmplifier
                
                if (riskScore > maxRiskScore) {
                    maxRiskScore = riskScore
                }
            }
        }
        
        maxRiskScore = maxRiskScore.coerceIn(0f, 1f)
        
        val level = when {
            maxRiskScore >= SafetyConstants.HIGH_RISK_THRESHOLD -> RiskLevel.HIGH
            maxRiskScore >= SafetyConstants.MEDIUM_RISK_THRESHOLD -> RiskLevel.MEDIUM
            else -> RiskLevel.LOW
        }
        
        return RiskResult(
            level = level,
            score = maxRiskScore,
            isInUnsafeZone = isInUnsafeZone,
            nearestZoneDistance = nearestDistance
        )
    }
    
    /**
     * Get time multiplier based on hour of day
     */
    private fun getTimeMultiplier(hour: Int): Float {
        return when {
            hour in SafetyConstants.NIGHT_START_HOUR..23 || hour in 0..SafetyConstants.DAY_START_HOUR -> 
                SafetyConstants.NIGHT_MULTIPLIER
            hour in SafetyConstants.EVENING_START_HOUR until SafetyConstants.NIGHT_START_HOUR -> 
                SafetyConstants.EVENING_MULTIPLIER
            else -> SafetyConstants.DAY_MULTIPLIER
        }
    }
    
    /**
     * Get time bucket string
     */
    private fun getTimeBucket(hour: Int): String {
        return when {
            hour in SafetyConstants.NIGHT_START_HOUR..23 || hour in 0..SafetyConstants.DAY_START_HOUR -> "night"
            hour in SafetyConstants.EVENING_START_HOUR until SafetyConstants.NIGHT_START_HOUR -> "evening"
            else -> "day"
        }
    }
    
    /**
     * Calculate distance between two points using Haversine formula
     * Returns distance in meters
     */
    private fun calculateDistance(lat1: Double, lng1: Double, lat2: Double, lng2: Double): Float {
        val earthRadius = 6371000.0  // meters
        
        val dLat = Math.toRadians(lat2 - lat1)
        val dLng = Math.toRadians(lng2 - lng1)
        
        val a = sin(dLat / 2).pow(2) + 
                cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * 
                sin(dLng / 2).pow(2)
        
        val c = 2 * atan2(sqrt(a), sqrt(1 - a))
        
        return (earthRadius * c).toFloat()
    }
    
    companion object {
        /**
         * Check if currently night time (high risk period)
         */
        fun isNightTime(): Boolean {
            val hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
            return hour in SafetyConstants.NIGHT_START_HOUR..23 || 
                   hour in 0..SafetyConstants.DAY_START_HOUR
        }
    }
}
