package com.safepulse.data.db.dao

import androidx.room.*
import com.safepulse.data.db.entity.UnsafeZoneEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface UnsafeZoneDao {
    
    @Query("SELECT * FROM unsafe_zones")
    fun getAllUnsafeZones(): Flow<List<UnsafeZoneEntity>>
    
    @Query("SELECT * FROM unsafe_zones")
    suspend fun getAllUnsafeZonesList(): List<UnsafeZoneEntity>
    
    @Query("""
        SELECT * FROM unsafe_zones 
        WHERE (lat BETWEEN :minLat AND :maxLat) 
        AND (lng BETWEEN :minLng AND :maxLng)
    """)
    suspend fun getUnsafeZonesInBounds(
        minLat: Double, maxLat: Double,
        minLng: Double, maxLng: Double
    ): List<UnsafeZoneEntity>
    
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(zones: List<UnsafeZoneEntity>)
    
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(zone: UnsafeZoneEntity)
    
    @Delete
    suspend fun delete(zone: UnsafeZoneEntity)
    
    @Query("DELETE FROM unsafe_zones")
    suspend fun deleteAll()
    
    @Query("SELECT COUNT(*) FROM unsafe_zones")
    suspend fun getCount(): Int
}
