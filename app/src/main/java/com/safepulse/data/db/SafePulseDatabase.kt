package com.safepulse.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.safepulse.data.db.dao.*
import com.safepulse.data.db.entity.*
import com.safepulse.data.journey.JourneyDao
import com.safepulse.data.journey.JourneyEventEntity
import com.safepulse.data.journey.JourneySessionEntity
import com.safepulse.data.journey.TransitionEventEntity

@Database(
    entities = [
        HotspotEntity::class,
        UnsafeZoneEntity::class,
        EmergencyContactEntity::class,
        EventLogEntity::class,
        EmergencyServiceEntity::class,
        JourneySessionEntity::class,
        JourneyEventEntity::class,
        TransitionEventEntity::class
    ],
    version = 4,
    exportSchema = false
)
abstract class SafePulseDatabase : RoomDatabase() {
    
    abstract fun hotspotDao(): HotspotDao
    abstract fun unsafeZoneDao(): UnsafeZoneDao
    abstract fun emergencyContactDao(): EmergencyContactDao
    abstract fun eventLogDao(): EventLogDao
    abstract fun emergencyServiceDao(): EmergencyServiceDao
    abstract fun journeyDao(): JourneyDao
    
    suspend fun preloadSampleDataIfNeeded(context: Context) {
        val hotspotCount = hotspotDao().getCount()
        val unsafeZoneCount = unsafeZoneDao().getCount()
        val emergencyServiceCount = emergencyServiceDao().getCount()
        
        if (hotspotCount == 0) {
            loadHotspotsFromAssets(context)
        }
        
        if (unsafeZoneCount == 0) {
            loadUnsafeZonesFromAssets(context)
        }
        
        if (emergencyServiceCount == 0) {
            loadEmergencyServicesFromAssets(context)
        }
    }
    
    private suspend fun loadHotspotsFromAssets(context: Context) {
        try {
            val json = context.assets.open("hotspots.json")
                .bufferedReader()
                .use { it.readText() }
            
            val type = object : TypeToken<List<HotspotJson>>() {}.type
            val hotspots: List<HotspotJson> = Gson().fromJson(json, type)
            
            val entities = hotspots.map { h ->
                HotspotEntity(
                    id = h.id,
                    lat = h.lat,
                    lng = h.lng,
                    radiusMeters = h.radiusMeters,
                    baseRisk = h.baseRisk,
                    timeBucket = h.timeBucket,
                    roadType = h.roadType
                )
            }
            hotspotDao().insertAll(entities)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
    
    private suspend fun loadUnsafeZonesFromAssets(context: Context) {
        try {
            val json = context.assets.open("unsafe_zones.json")
                .bufferedReader()
                .use { it.readText() }
            
            val type = object : TypeToken<List<UnsafeZoneJson>>() {}.type
            val zones: List<UnsafeZoneJson> = Gson().fromJson(json, type)
            
            val entities = zones.map { z ->
                UnsafeZoneEntity(
                    id = z.id,
                    lat = z.lat,
                    lng = z.lng,
                    radiusMeters = z.radiusMeters,
                    crimeScore = z.crimeScore,
                    lightingScore = z.lightingScore,
                    footfallScore = z.footfallScore
                )
            }
            unsafeZoneDao().insertAll(entities)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
    
    private suspend fun loadEmergencyServicesFromAssets(context: Context) {
        try {
            val json = context.assets.open("emergency_services.json")
                .bufferedReader()
                .use { it.readText() }
            
            val type = object : TypeToken<List<EmergencyServiceJson>>() {}.type
            val services: List<EmergencyServiceJson> = Gson().fromJson(json, type)
            
            val entities = services.map { s ->
                EmergencyServiceEntity(
                    id = s.id,
                    name = s.name,
                    type = s.type,
                    address = s.address,
                    phoneNumber = s.phoneNumber,
                    lat = s.lat,
                    lng = s.lng,
                    city = s.city
                )
            }
            emergencyServiceDao().insertAll(entities)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
    
    companion object {
        @Volatile
        private var INSTANCE: SafePulseDatabase? = null
        
        fun getInstance(context: Context): SafePulseDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    SafePulseDatabase::class.java,
                    "safepulse_database"
                )
                .fallbackToDestructiveMigration() // Simple migration for prototype
                .build()
                INSTANCE = instance
                instance
            }
        }
    }
}

// JSON parsing helpers
private data class HotspotJson(
    val id: Long,
    val lat: Double,
    val lng: Double,
    val radiusMeters: Float,
    val baseRisk: Float,
    val timeBucket: String,
    val roadType: String
)

private data class UnsafeZoneJson(
    val id: Long,
    val lat: Double,
    val lng: Double,
    val radiusMeters: Float,
    val crimeScore: Float,
    val lightingScore: Float,
    val footfallScore: Float
)

private data class EmergencyServiceJson(
    val id: Long,
    val name: String,
    val type: String,
    val address: String,
    val phoneNumber: String,
    val lat: Double,
    val lng: Double,
    val city: String
)
