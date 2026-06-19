package com.safepulse.domain.engine

import com.safepulse.domain.model.LocationData
import com.safepulse.domain.model.MovementState
import com.safepulse.domain.model.SensorData
import com.safepulse.utils.SafetyConstants
import kotlin.math.*

/**
 * Tracks user movement patterns to detect inactivity
 */
class MovementTracker {
    
    private var lastMovingTimestamp: Long = System.currentTimeMillis()
    private var lastKnownLocation: LocationData? = null
    private var currentState: MovementState = MovementState.STATIONARY
    
    // Sensor variance tracking
    private val recentAccelerations = mutableListOf<Float>()
    private val maxAccelerationHistory = 20
    
    /**
     * Update movement state based on sensor data
     */
    fun updateWithSensorData(sensorData: SensorData) {
        val variance = calculateAccelerationVariance(sensorData.accelerationMagnitude)
        
        val wasMoving = currentState == MovementState.MOVING
        val isMoving = variance > SafetyConstants.MOVEMENT_VARIANCE_THRESHOLD
        
        when {
            isMoving -> {
                currentState = MovementState.MOVING
                lastMovingTimestamp = sensorData.timestamp
            }
            wasMoving && !isMoving -> {
                currentState = MovementState.STOPPED_RECENTLY
            }
            currentState == MovementState.STOPPED_RECENTLY -> {
                val stoppedDuration = sensorData.timestamp - lastMovingTimestamp
                if (stoppedDuration > SafetyConstants.INACTIVITY_TIMEOUT_MINUTES * 60 * 1000) {
                    currentState = MovementState.STATIONARY
                }
            }
        }
    }
    
    /**
     * Update movement state based on location changes
     */
    fun updateWithLocation(location: LocationData) {
        val previous = lastKnownLocation
        lastKnownLocation = location
        
        if (previous != null) {
            val distance = calculateDistance(
                previous.latitude, previous.longitude,
                location.latitude, location.longitude
            )
            
            if (distance > SafetyConstants.MOVEMENT_THRESHOLD_METERS) {
                currentState = MovementState.MOVING
                lastMovingTimestamp = location.timestamp
            } else if (currentState == MovementState.MOVING) {
                currentState = MovementState.STOPPED_RECENTLY
            }
        }
    }
    
    /**
     * Get current movement state
     */
    fun getMovementState(): MovementState = currentState
    
    /**
     * Get duration since last movement in milliseconds
     */
    fun getInactivityDuration(): Long {
        return System.currentTimeMillis() - lastMovingTimestamp
    }
    
    /**
     * Get duration since last movement in minutes
     */
    fun getInactivityMinutes(): Int {
        return (getInactivityDuration() / 60000).toInt()
    }
    
    /**
     * Check if user has been inactive for concerning duration
     */
    fun isInactivityConcerning(): Boolean {
        return currentState != MovementState.MOVING && 
               getInactivityMinutes() >= SafetyConstants.INACTIVITY_TIMEOUT_MINUTES
    }
    
    /**
     * Get inactivity score (0 to 1) for confidence calculation
     */
    fun getInactivityScore(): Float {
        if (currentState == MovementState.MOVING) return 0f
        
        val inactiveMinutes = getInactivityMinutes()
        return (inactiveMinutes.toFloat() / (SafetyConstants.INACTIVITY_TIMEOUT_MINUTES * 2))
            .coerceIn(0f, 1f)
    }
    
    /**
     * Calculate acceleration variance from recent readings
     */
    private fun calculateAccelerationVariance(newAcceleration: Float): Float {
        recentAccelerations.add(newAcceleration)
        if (recentAccelerations.size > maxAccelerationHistory) {
            recentAccelerations.removeAt(0)
        }
        
        if (recentAccelerations.size < 5) return 0f
        
        val mean = recentAccelerations.average().toFloat()
        val variance = recentAccelerations.map { (it - mean).pow(2) }.average().toFloat()
        
        return sqrt(variance)
    }
    
    /**
     * Calculate distance between two points in meters
     */
    private fun calculateDistance(lat1: Double, lng1: Double, lat2: Double, lng2: Double): Float {
        val earthRadius = 6371000.0
        
        val dLat = Math.toRadians(lat2 - lat1)
        val dLng = Math.toRadians(lng2 - lng1)
        
        val a = sin(dLat / 2).pow(2) + 
                cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * 
                sin(dLng / 2).pow(2)
        
        val c = 2 * atan2(sqrt(a), sqrt(1 - a))
        
        return (earthRadius * c).toFloat()
    }
    
    /**
     * Reset tracker state
     */
    fun reset() {
        lastMovingTimestamp = System.currentTimeMillis()
        lastKnownLocation = null
        currentState = MovementState.STATIONARY
        recentAccelerations.clear()
    }
}
