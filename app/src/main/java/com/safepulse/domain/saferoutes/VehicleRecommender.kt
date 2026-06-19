package com.safepulse.domain.saferoutes

import java.util.Calendar

/**
 * Recommends safe vehicle type based on route risk, time, and conditions
 */
class VehicleRecommender {
    
    companion object {
        private const val NIGHT_START_HOUR = 20  // 8 PM
        private const val NIGHT_END_HOUR = 6     // 6 AM
        private const val SHORT_WALK_DISTANCE = 2000  // 2 km
    }
    
    /**
     * Recommend safest vehicle for the route
     */
    fun recommendVehicle(
        route: SafeRoute,
        disasters: List<DisasterAlert> = emptyList(),
        currentHour: Int = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
    ): VehicleRecommendation {
        
        // Critical: Active disasters - avoid travel
        if (disasters.any { it.severity == Severity.CRITICAL }) {
            return VehicleRecommendation(
                vehicle = RecommendedVehicle.AVOID_TRAVEL,
                reason = "⚠️ CRITICAL: ${disasters.first().type} alert active on route",
                safetyTips = listOf(
                    "Stay indoors if possible",
                    "Monitor news for updates",
                    "Contact emergency services if needed"
                )
            )
        }
        
        val isNight = currentHour >= NIGHT_START_HOUR || currentHour < NIGHT_END_HOUR
        val isShortDistance = route.distance < SHORT_WALK_DISTANCE
        
        // High risk disaster warning
        if (disasters.any { it.severity == Severity.HIGH }) {
            return VehicleRecommendation(
                vehicle = RecommendedVehicle.TRACKED_CAB,
                reason = "${disasters.first().type} warning - use tracked vehicle",
                safetyTips = listOf(
                    "Share trip details with emergency contacts",
                    "Keep emergency contacts ready",
                    "Avoid affected areas"
                )
            )
        }
        
        // Route risk-based recommendations
        return when (route.riskLevel) {
            RiskLevel.HIGH -> VehicleRecommendation(
                vehicle = RecommendedVehicle.TRACKED_CAB,
                reason = "High crime risk area detected - use GPS-tracked transport",
                safetyTips = listOf(
                    "Share live location with contacts",
                    "Note cab number/driver details", 
                    "Stay alert and avoid isolated areas"
                )
            )
            
            RiskLevel.MEDIUM -> {
                if (isNight) {
                    VehicleRecommendation(
                        vehicle = RecommendedVehicle.TRACKED_CAB,
                        reason = "Night time + moderate risk - tracked cab recommended",
                        safetyTips = listOf(
                            "Share trip status with contacts",
                            "Avoid stopping at isolated spots"
                        )
                    )
                } else {
                    VehicleRecommendation(
                        vehicle = RecommendedVehicle.AUTO_RICKSHAW,
                        reason = "Moderate risk - auto rickshaw acceptable during daytime",
                        safetyTips = listOf(
                            "Share ride details with contacts",
                            "Note vehicle number"
                        )
                    )
                }
            }
            
            RiskLevel.LOW -> {
                when {
                    isShortDistance && !isNight -> VehicleRecommendation(
                        vehicle = RecommendedVehicle.WALK,
                        reason = "Safe route, short distance - walking is fine",
                        safetyTips = listOf(
                            "Stay on well-lit paths",
                            "Keep emergency contacts handy"
                        )
                    )
                    
                    isNight -> VehicleRecommendation(
                        vehicle = RecommendedVehicle.TRACKED_CAB,
                        reason = "Safe route but night time - cab recommended",
                        safetyTips = listOf(
                            "Use well-known cab services",
                            "Share trip details"
                        )
                    )
                    
                    else -> VehicleRecommendation(
                        vehicle = RecommendedVehicle.PUBLIC_BUS,
                        reason = "Safe route - public transport available",
                        safetyTips = listOf(
                            "Travel in groups when possible",
                            "Stay aware of surroundings"
                        )
                    )
                }
            }
        }
    }
}
