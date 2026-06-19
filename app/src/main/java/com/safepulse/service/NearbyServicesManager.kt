package com.safepulse.service

import com.safepulse.data.db.entity.EmergencyServiceEntity
import com.safepulse.data.repository.EmergencyServiceRepository
import com.safepulse.domain.model.EventType
import com.safepulse.domain.model.LocationData
import kotlin.math.*

/**
 * Manager for finding and alerting nearby emergency services
 */
class NearbyServicesManager(private val repository: EmergencyServiceRepository) {
    
    /**
     * Find nearest emergency services based on event type and location
     */
    suspend fun findNearbyServices(
        location: LocationData,
        eventType: EventType,
        maxDistance: Float = 5000f, // 5km default
        maxResults: Int = 3
    ): List<EmergencyServiceEntity> {
        val serviceType = getServiceTypeForEvent(eventType)
        val allServices = repository.getServicesByType(serviceType)
        
        // Calculate distance for each service and sort
        val servicesWithDistance = allServices.map { service ->
            val distance = calculateDistance(
                location.latitude, location.longitude,
                service.lat, service.lng
            )
            service to distance
        }
        
        // Filter by max distance and take top results
        return servicesWithDistance
            .filter { it.second <= maxDistance }
            .sortedBy { it.second }
            .take(maxResults) 
            .map { it.first }
    }
    
    /**
     * Determine service type based on event type
     */
    private fun getServiceTypeForEvent(eventType: EventType): String {
        return when (eventType) {
            EventType.ROAD_ACCIDENT -> "HOSPITAL"
            EventType.FALL -> "HOSPITAL"
            EventType.POSSIBLE_ASSAULT -> "POLICE"
            EventType.INACTIVITY_ALERT -> "POLICE"
            EventType.VOICE_TRIGGER -> "POLICE"
            EventType.HIGH_RISK_ZONE -> "POLICE"
            EventType.MANUAL_SOS -> "POLICE" // Default to police for manual SOS
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
}
