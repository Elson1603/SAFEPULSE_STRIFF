package com.safepulse.data.journey

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.safepulse.domain.journey.TransitionType

@Entity(tableName = "transition_events")
data class TransitionEventEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val timestamp: Long,
    val transitionType: TransitionType,
    val latitude: Double,
    val longitude: Double,
    val confidence: Float,
    val description: String
)
