package com.safepulse.data.db.dao

import androidx.room.*
import com.safepulse.data.db.entity.EventLogEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface EventLogDao {
    
    @Query("SELECT * FROM event_logs ORDER BY timestamp DESC")
    fun getAllEvents(): Flow<List<EventLogEntity>>
    
    @Query("SELECT * FROM event_logs ORDER BY timestamp DESC LIMIT :limit")
    fun getRecentEvents(limit: Int): Flow<List<EventLogEntity>>
    
    @Query("SELECT * FROM event_logs ORDER BY timestamp DESC")
    suspend fun getAllEventsList(): List<EventLogEntity>
    
    @Query("SELECT * FROM event_logs WHERE type = :type ORDER BY timestamp DESC")
    fun getEventsByType(type: String): Flow<List<EventLogEntity>>
    
    @Query("SELECT * FROM event_logs WHERE timestamp >= :since ORDER BY timestamp DESC")
    suspend fun getEventsSince(since: Long): List<EventLogEntity>
    
    @Insert
    suspend fun insert(event: EventLogEntity): Long
    
    @Delete
    suspend fun delete(event: EventLogEntity)
    
    @Query("DELETE FROM event_logs WHERE timestamp < :before")
    suspend fun deleteOldEvents(before: Long)
    
    @Query("DELETE FROM event_logs")
    suspend fun deleteAll()
    
    @Query("SELECT COUNT(*) FROM event_logs")
    suspend fun getCount(): Int
    
    @Query("SELECT COUNT(*) FROM event_logs WHERE wasSOSSent = 1")
    suspend fun getSOSSentCount(): Int
}
