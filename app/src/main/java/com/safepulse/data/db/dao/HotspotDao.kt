package com.safepulse.data.db.dao

import androidx.room.*
import com.safepulse.data.db.entity.HotspotEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface HotspotDao {
    
    @Query("SELECT * FROM hotspots")
    fun getAllHotspots(): Flow<List<HotspotEntity>>
    
    @Query("SELECT * FROM hotspots")
    suspend fun getAllHotspotsList(): List<HotspotEntity>
    
    @Query("""
        SELECT * FROM hotspots 
        WHERE (lat BETWEEN :minLat AND :maxLat) 
        AND (lng BETWEEN :minLng AND :maxLng)
    """)
    suspend fun getHotspotsInBounds(
        minLat: Double, maxLat: Double,
        minLng: Double, maxLng: Double
    ): List<HotspotEntity>
    
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(hotspots: List<HotspotEntity>)
    
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(hotspot: HotspotEntity)
    
    @Delete
    suspend fun delete(hotspot: HotspotEntity)
    
    @Query("DELETE FROM hotspots")
    suspend fun deleteAll()
    
    @Query("SELECT COUNT(*) FROM hotspots")
    suspend fun getCount(): Int
}
