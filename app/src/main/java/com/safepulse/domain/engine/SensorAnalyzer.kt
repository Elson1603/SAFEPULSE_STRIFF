package com.safepulse.domain.engine

import com.safepulse.domain.model.EventType
import com.safepulse.domain.model.SensorData
import com.safepulse.utils.SafetyConstants
import kotlin.math.abs

/**
 * Analyzes sensor data to detect impacts, falls, and potential assaults
 */
class SensorAnalyzer {
    
    // Recent sensor readings for pattern analysis
    private val recentReadings = mutableListOf<SensorData>()
    private val maxReadingsHistory = 50
    
    // State tracking
    private var lastHighImpactTime = 0L
    private var lastEmittedEventTime = 0L
    private var inFreeFall = false
    private var freeFallStartTime = 0L
    
    /**
     * Process new sensor data and detect events
     * Returns event type and confidence if detected, null otherwise
     */
    fun analyzeSensorData(data: SensorData): SensorAnalysisResult? {
        if (data.timestamp - lastEmittedEventTime < 4_000L) return null

        // Add to history
        recentReadings.add(data)
        if (recentReadings.size > maxReadingsHistory) {
            recentReadings.removeAt(0)
        }
        
        // Check for various event patterns
        val impactResult = detectImpact(data)
        if (impactResult != null) {
            return impactResult
        }
        
        val fallResult = detectFall(data)
        if (fallResult != null) {
            return fallResult
        }
        
        val assaultResult = detectPossibleAssault(data)
        if (assaultResult != null) {
            return assaultResult
        }
        
        return null
    }
    
    /**
     * Detect high-impact events (road accidents, collisions)
     */
    private fun detectImpact(data: SensorData): SensorAnalysisResult? {
        val acceleration = data.accelerationMagnitude
        
        // Major impact detection
        if (acceleration > SafetyConstants.IMPACT_ACCELERATION_THRESHOLD) {
            lastHighImpactTime = data.timestamp
            
            // Calculate confidence from impact plus gyroscope rotation to reduce pocket/bag false positives.
            val magnitudeConfidence = ((acceleration - SafetyConstants.IMPACT_ACCELERATION_THRESHOLD) /
                            SafetyConstants.IMPACT_ACCELERATION_THRESHOLD)
                            .coerceIn(0.6f, 1.0f)
            val rotationBoost = (data.rotationMagnitude / SafetyConstants.VIOLENT_ROTATION_THRESHOLD)
                .coerceIn(0f, 0.2f)
            val confidence = (magnitudeConfidence + rotationBoost).coerceAtMost(1f)
            
            // Check if followed by stillness (unconscious or injured)
            val followedByStillness = checkRecentStillness()
            val adjustedConfidence = if (followedByStillness) {
                (confidence + 0.2f).coerceAtMost(1f)
            } else {
                confidence
            }
            
            lastEmittedEventTime = data.timestamp
            return SensorAnalysisResult(
                eventType = EventType.ROAD_ACCIDENT,
                confidence = adjustedConfidence,
                description = "High impact with motion signature: ${acceleration.toInt()} m/s²"
            )
        }
        
        // Moderate impact - might be accident or rough handling
        if (acceleration > SafetyConstants.MODERATE_IMPACT_THRESHOLD) {
            val rotation = data.rotationMagnitude
            
            // High rotation with moderate impact could indicate assault
            if (rotation > SafetyConstants.VIOLENT_ROTATION_THRESHOLD) {
                lastEmittedEventTime = data.timestamp
                return SensorAnalysisResult(
                    eventType = EventType.POSSIBLE_ASSAULT,
                    confidence = 0.5f,
                    description = "Violent motion detected"
                )
            }
        }
        
        return null
    }
    
    /**
     * Detect fall patterns (free fall followed by impact)
     */
    private fun detectFall(data: SensorData): SensorAnalysisResult? {
        val acceleration = data.accelerationMagnitude
        
        // Detect free fall (near zero gravity)
        if (acceleration < SafetyConstants.FALL_ACCELERATION_THRESHOLD) {
            if (!inFreeFall) {
                inFreeFall = true
                freeFallStartTime = data.timestamp
            }
        } else if (inFreeFall) {
            // Coming out of free fall
            val fallDuration = data.timestamp - freeFallStartTime
            inFreeFall = false
            
            // Fall followed by impact
            if (fallDuration in 100..2000 && 
                acceleration > SafetyConstants.FALL_IMPACT_THRESHOLD) {
                
                val confidence = when {
                    fallDuration > 500 && acceleration > 25f -> 0.9f
                    fallDuration > 300 && acceleration > 20f -> 0.75f
                    else -> 0.6f
                }
                
                lastEmittedEventTime = data.timestamp
                return SensorAnalysisResult(
                    eventType = EventType.FALL,
                    confidence = confidence,
                    description = "Fall detected: ${fallDuration}ms free-fall"
                )
            }
        }
        
        return null
    }
    
    /**
     * Detect patterns that might indicate assault or struggle
     */
    private fun detectPossibleAssault(data: SensorData): SensorAnalysisResult? {
        if (recentReadings.size < 10) return null
        
        // Look for erratic motion pattern (struggle)
        val recentTen = recentReadings.takeLast(10)
        
        // Count direction changes
        var directionChanges = 0
        var highRotationCount = 0
        
        for (i in 1 until recentTen.size) {
            val prev = recentTen[i - 1]
            val curr = recentTen[i]
            
            // Check for sudden direction changes in acceleration
            if ((prev.accelerometerX > 0 && curr.accelerometerX < 0) ||
                (prev.accelerometerX < 0 && curr.accelerometerX > 0)) {
                directionChanges++
            }
            if ((prev.accelerometerY > 0 && curr.accelerometerY < 0) ||
                (prev.accelerometerY < 0 && curr.accelerometerY > 0)) {
                directionChanges++
            }
            
            // Count high rotation readings
            if (curr.rotationMagnitude > SafetyConstants.VIOLENT_ROTATION_THRESHOLD * 0.7f) {
                highRotationCount++
            }
        }
        
        // Erratic motion with high rotation = possible struggle
        if (directionChanges >= 6 && highRotationCount >= 4) {
            val confidence = ((directionChanges + highRotationCount) / 20f)
                            .coerceIn(0.5f, 0.85f)
            
            lastEmittedEventTime = data.timestamp
            return SensorAnalysisResult(
                eventType = EventType.POSSIBLE_ASSAULT,
                confidence = confidence,
                description = "Erratic motion pattern detected"
            )
        }
        
        return null
    }
    
    /**
     * Check if recent readings show stillness (after impact)
     */
    private fun checkRecentStillness(): Boolean {
        if (recentReadings.size < 5) return false
        
        val recentFive = recentReadings.takeLast(5)
        val avgAcceleration = recentFive.map { it.accelerationMagnitude }.average()
        
        // Near gravity (9.8) with low variance = stillness
        return abs(avgAcceleration - 9.8) < 1.5
    }
    
    /**
     * Reset analyzer state
     */
    fun reset() {
        recentReadings.clear()
        lastHighImpactTime = 0L
        lastEmittedEventTime = 0L
        inFreeFall = false
        freeFallStartTime = 0L
    }
}

/**
 * Result of sensor analysis
 */
data class SensorAnalysisResult(
    val eventType: EventType,
    val confidence: Float,
    val description: String
)
