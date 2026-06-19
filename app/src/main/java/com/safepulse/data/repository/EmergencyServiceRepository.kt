package com.safepulse.data.repository

import com.safepulse.data.db.dao.EmergencyServiceDao
import com.safepulse.data.db.entity.EmergencyServiceEntity
import kotlinx.coroutines.flow.Flow

/**
 * Repository for emergency services data
 */
class EmergencyServiceRepository(private val dao: EmergencyServiceDao) {
    
    fun getAllServices(): Flow<List<EmergencyServiceEntity>> {
        return dao.getAllServices()
    }
    
    suspend fun getAllServicesList(): List<EmergencyServiceEntity> {
        return dao.getAllServicesList()
    }
    
    suspend fun getServicesByType(type: String): List<EmergencyServiceEntity> {
        return dao.getServicesByType(type)
    }
    
    suspend fun getServicesByCity(city: String): List<EmergencyServiceEntity> {
        return dao.getServicesByCity(city)
    }
    
    suspend fun insertAll(services: List<EmergencyServiceEntity>) {
        dao.insertAll(services)
    }
    
    suspend fun getCount(): Int {
        return dao.getCount()
    }
}
