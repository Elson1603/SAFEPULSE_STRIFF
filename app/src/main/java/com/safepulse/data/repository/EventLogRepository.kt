package com.safepulse.data.repository

import com.safepulse.data.db.dao.EventLogDao
import com.safepulse.data.db.entity.EventLogEntity
import kotlinx.coroutines.flow.Flow

class EventLogRepository(private val dao: EventLogDao) {
    
    fun getAllEvents(): Flow<List<EventLogEntity>> = dao.getAllEvents()
    
    fun getRecentEvents(limit: Int = 50): Flow<List<EventLogEntity>> = dao.getRecentEvents(limit)
    
    suspend fun getAllEventsList(): List<EventLogEntity> = dao.getAllEventsList()
    
    fun getEventsByType(type: String): Flow<List<EventLogEntity>> = dao.getEventsByType(type)
    
    suspend fun getEventsSince(since: Long): List<EventLogEntity> = dao.getEventsSince(since)
    
    suspend fun insert(event: EventLogEntity): Long = dao.insert(event)
    
    suspend fun logEvent(
        type: String,
        confidence: Float,
        lat: Double,
        lng: Double,
        mode: String,
        wasSOSSent: Boolean
    ): Long {
        return dao.insert(
            EventLogEntity(
                timestamp = System.currentTimeMillis(),
                type = type,
                confidence = confidence,
                lat = lat,
                lng = lng,
                mode = mode,
                wasSOSSent = wasSOSSent
            )
        )
    }
    
    suspend fun delete(event: EventLogEntity) = dao.delete(event)
    
    suspend fun deleteOldEvents(olderThanDays: Int = 30) {
        val cutoffTime = System.currentTimeMillis() - (olderThanDays * 24 * 60 * 60 * 1000L)
        dao.deleteOldEvents(cutoffTime)
    }
    
    suspend fun getCount(): Int = dao.getCount()
    
    suspend fun getSOSSentCount(): Int = dao.getSOSSentCount()
}
