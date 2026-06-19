package com.safepulse.data.db.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Represents an accident hotspot zone stored in local database.
 * Used for accident prediction and risk scoring.
 */
@Entity(tableName = "hotspots")
data class HotspotEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val lat: Double,
    val lng: Double,
    val radiusMeters: Float,
    val baseRisk: Float,          // 0.0 to 1.0
    val timeBucket: String,       // "day", "night", "evening", "all"
    val roadType: String          // "highway", "urban", "rural"
)

/**
 * Represents an unsafe zone for women safety.
 * Used for heightened safety mode activation.
 */
@Entity(tableName = "unsafe_zones")
data class UnsafeZoneEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val lat: Double,
    val lng: Double,
    val radiusMeters: Float,
    val crimeScore: Float,        // 0.0 to 1.0
    val lightingScore: Float,     // 0.0 (dark) to 1.0 (well-lit)
    val footfallScore: Float      // 0.0 (isolated) to 1.0 (crowded)
)

/**
 * Emergency contact for SOS messages and calls.
 */
@Entity(tableName = "emergency_contacts")
data class EmergencyContactEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val name: String,
    val phone: String,
    val isPrimary: Boolean = false
)

/**
 * Log of detected safety events.
 */
@Entity(tableName = "event_logs")
data class EventLogEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val timestamp: Long,          // Unix timestamp in millis
    val type: String,             // ROAD_ACCIDENT, FALL, POSSIBLE_ASSAULT, HIGH_RISK_ZONE
    val confidence: Float,        // 0.0 to 1.0
    val lat: Double,
    val lng: Double,
    val mode: String,             // NORMAL, HEIGHTENED
    val wasSOSSent: Boolean
)

/**
 * Emergency Services Entity - Stores police stations, hospitals, fire stations
 */
@Entity(tableName = "emergency_services")
data class EmergencyServiceEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val type: String, // "POLICE", "HOSPITAL", "FIRE"
    val address: String,
    val phoneNumber: String,
    val lat: Double,
    val lng: Double,
    val city: String
)
