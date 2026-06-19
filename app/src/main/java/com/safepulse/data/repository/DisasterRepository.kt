package com.safepulse.data.repository

import com.google.android.gms.maps.model.LatLng
import com.safepulse.domain.saferoutes.DisasterAlert
import com.safepulse.domain.saferoutes.DisasterType
import com.safepulse.domain.saferoutes.Severity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

/**
 * Repository for disaster alerts
 * Currently provides mock data - can be replaced with real API
 */
class DisasterRepository {
    
    /**
     * Get active disaster alerts
     * Returns mock data for demonstration
     */
    fun getActiveDisasters(): Flow<List<DisasterAlert>> {
        // Mock disaster data - replace with real API
        val mockDisasters = listOf(
            DisasterAlert(
                id = "disaster_1",
                type = DisasterType.HEAVY_RAIN,
                severity = Severity.MODERATE,
                location = LatLng(12.9716, 77.5946), // Bangalore
                radius = 5f, // 5km radius
                startTime = System.currentTimeMillis() - 3600000, // 1 hour ago
                endTime = System.currentTimeMillis() + 7200000, // 2 hours from now
                description = "Heavy rainfall alert in central Bangalore",
                source = "IMD"
            ),
            DisasterAlert(
                id = "disaster_2",
                type = DisasterType.FLOOD,
                severity = Severity.HIGH,
                location = LatLng(12.9352, 77.6245), // East Bangalore
                radius = 3f,
                startTime = System.currentTimeMillis() - 7200000, // 2 hours ago
                endTime = null, // Ongoing
                description = "Flash flood warning in low-lying areas",
                source = "Local Authorities"
            )
        )
        
        return flowOf(mockDisasters)
    }
    
    /**
     * Get disasters near a specific location
     */
    suspend fun getDisastersNearLocation(
        location: LatLng,
        radiusKm: Float = 10f
    ): List<DisasterAlert> {
        // For now, return all disasters
        // In production, filter by distance
        return getActiveDisasters().let { flow ->
            val disasters = mutableListOf<DisasterAlert>()
            flow.collect { disasters.addAll(it) }
            disasters
        }
    }
}
