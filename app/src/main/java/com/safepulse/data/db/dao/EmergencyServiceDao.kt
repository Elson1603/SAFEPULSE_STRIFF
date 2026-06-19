package com.safepulse.data.db.dao

import androidx.room.*
import com.safepulse.data.db.entity.EmergencyServiceEntity
import kotlinx.coroutines.flow.Flow

/**
 * DAO for emergency services (police, hospitals, fire stations)
 */
@Dao
interface EmergencyServiceDao {
    
    @Query("SELECT * FROM emergency_services ORDER BY name")
    fun getAllServices(): Flow<List<EmergencyServiceEntity>>
    
    @Query("SELECT * FROM emergency_services ORDER BY name")
    suspend fun getAllServicesList(): List<EmergencyServiceEntity>
    
    @Query("SELECT * FROM emergency_services WHERE type = :type ORDER BY name")
    suspend fun getServicesByType(type: String): List<EmergencyServiceEntity>
    
    @Query("SELECT * FROM emergency_services WHERE city = :city ORDER BY name")
    suspend fun getServicesByCity(city: String): List<EmergencyServiceEntity>
    
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(services: List<EmergencyServiceEntity>)
    
    @Query("SELECT COUNT(*) FROM emergency_services")
    suspend fun getCount(): Int
    
    @Query("DELETE FROM emergency_services")
    suspend fun deleteAll()
}
