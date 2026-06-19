package com.safepulse.data.repository

import com.safepulse.data.db.dao.HotspotDao
import com.safepulse.data.db.entity.HotspotEntity
import kotlinx.coroutines.flow.Flow

class HotspotRepository(private val dao: HotspotDao) {
    
    fun getAllHotspots(): Flow<List<HotspotEntity>> = dao.getAllHotspots()
    
    suspend fun getAllHotspotsList(): List<HotspotEntity> = dao.getAllHotspotsList()
    
    suspend fun getHotspotsNear(lat: Double, lng: Double, radiusDegrees: Double = 0.01): List<HotspotEntity> {
        return dao.getHotspotsInBounds(
            minLat = lat - radiusDegrees,
            maxLat = lat + radiusDegrees,
            minLng = lng - radiusDegrees,
            maxLng = lng + radiusDegrees
        )
    }
    
    suspend fun insert(hotspot: HotspotEntity) = dao.insert(hotspot)
    
    suspend fun insertAll(hotspots: List<HotspotEntity>) = dao.insertAll(hotspots)
    
    suspend fun delete(hotspot: HotspotEntity) = dao.delete(hotspot)
}
