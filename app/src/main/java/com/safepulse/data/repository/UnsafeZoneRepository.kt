package com.safepulse.data.repository

import com.safepulse.data.db.dao.UnsafeZoneDao
import com.safepulse.data.db.entity.UnsafeZoneEntity
import kotlinx.coroutines.flow.Flow

class UnsafeZoneRepository(private val dao: UnsafeZoneDao) {
    
    fun getAllUnsafeZones(): Flow<List<UnsafeZoneEntity>> = dao.getAllUnsafeZones()
    
    suspend fun getAllUnsafeZonesList(): List<UnsafeZoneEntity> = dao.getAllUnsafeZonesList()
    
    suspend fun getUnsafeZonesNear(lat: Double, lng: Double, radiusDegrees: Double = 0.01): List<UnsafeZoneEntity> {
        return dao.getUnsafeZonesInBounds(
            minLat = lat - radiusDegrees,
            maxLat = lat + radiusDegrees,
            minLng = lng - radiusDegrees,
            maxLng = lng + radiusDegrees
        )
    }
    
    suspend fun insert(zone: UnsafeZoneEntity) = dao.insert(zone)
    
    suspend fun insertAll(zones: List<UnsafeZoneEntity>) = dao.insertAll(zones)
    
    suspend fun delete(zone: UnsafeZoneEntity) = dao.delete(zone)
}
