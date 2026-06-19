package com.safepulse.data.journey

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.safepulse.domain.journey.JourneyPhase
import com.safepulse.domain.journey.JourneySessionStatus
import kotlinx.coroutines.flow.Flow

@Dao
interface JourneyDao {
    @Query("SELECT * FROM journey_sessions WHERE status = 'ACTIVE' ORDER BY startTime DESC LIMIT 1")
    fun observeActiveSession(): Flow<JourneySessionEntity?>

    @Query("SELECT * FROM journey_sessions WHERE status = 'ACTIVE' ORDER BY startTime DESC LIMIT 1")
    suspend fun getActiveSession(): JourneySessionEntity?

    @Query("SELECT * FROM journey_sessions WHERE sessionId = :sessionId LIMIT 1")
    suspend fun getSession(sessionId: String): JourneySessionEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSession(session: JourneySessionEntity)

    @Update
    suspend fun updateSession(session: JourneySessionEntity)

    @Query(
        """
        UPDATE journey_sessions
        SET currentPhase = :phase, riskScore = :riskScore
        WHERE sessionId = :sessionId
        """
    )
    suspend fun updateSessionState(
        sessionId: String,
        phase: JourneyPhase,
        riskScore: Int
    )

    @Query(
        """
        UPDATE journey_sessions
        SET status = :status, endTime = :endTime, currentPhase = :phase
        WHERE sessionId = :sessionId
        """
    )
    suspend fun finishSession(
        sessionId: String,
        status: JourneySessionStatus,
        endTime: Long,
        phase: JourneyPhase
    )

    @Query("SELECT * FROM journey_events WHERE sessionId = :sessionId ORDER BY timestamp ASC, id ASC")
    fun observeEvents(sessionId: String): Flow<List<JourneyEventEntity>>

    @Query("SELECT * FROM journey_events WHERE sessionId = :sessionId ORDER BY timestamp ASC, id ASC")
    suspend fun getEvents(sessionId: String): List<JourneyEventEntity>

    @Query("SELECT * FROM journey_events WHERE sessionId = :sessionId ORDER BY timestamp DESC, id DESC LIMIT 1")
    suspend fun getLatestEvent(sessionId: String): JourneyEventEntity?

    @Insert
    suspend fun insertEvent(event: JourneyEventEntity): Long

    @Insert
    suspend fun insertTransitionEvent(event: TransitionEventEntity): Long

    @Query("SELECT * FROM transition_events ORDER BY timestamp DESC, id DESC LIMIT :limit")
    fun observeRecentTransitionEvents(limit: Int = 100): Flow<List<TransitionEventEntity>>

    @Query("SELECT * FROM transition_events ORDER BY timestamp DESC, id DESC LIMIT 1")
    suspend fun getLatestTransitionEvent(): TransitionEventEntity?
}
