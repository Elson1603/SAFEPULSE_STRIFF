package com.safepulse.domain.engine

import com.safepulse.data.db.entity.HotspotEntity
import com.safepulse.data.db.entity.UnsafeZoneEntity
import com.safepulse.domain.model.*
import com.safepulse.utils.SafetyConstants
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Central orchestrator that combines all safety inputs into unified safety state.
 * This is the brain of the safety monitoring system.
 */
class SafetyEngine {
    
    private val riskScoringEngine = RiskScoringEngine()
    private val sensorAnalyzer = SensorAnalyzer()
    private val movementTracker = MovementTracker()
    
    // Current state
    private val _safetyState = MutableStateFlow(
        SafetyState(
            mode = SafetyMode.NORMAL,
            riskLevel = RiskLevel.LOW,
            riskScore = 0f,
            isEmergency = false,
            currentEvent = null,
            movementState = MovementState.STATIONARY,
            location = null
        )
    )
    val safetyState: StateFlow<SafetyState> = _safetyState.asStateFlow()
    
    // Emergency event flow
    private val _emergencyEvent = MutableStateFlow<EmergencyEvent?>(null)
    val emergencyEvent: StateFlow<EmergencyEvent?> = _emergencyEvent.asStateFlow()
    
    // Voice trigger state
    private var voiceTriggerDetected = false
    
    // Cached zone data
    private var cachedHotspots: List<HotspotEntity> = emptyList()
    private var cachedUnsafeZones: List<UnsafeZoneEntity> = emptyList()
    
    // User profile
    private var userProfile = UserProfile(Gender.UNSPECIFIED, false)
    
    /**
     * Update cached zone data
     */
    fun updateZoneData(hotspots: List<HotspotEntity>, unsafeZones: List<UnsafeZoneEntity>) {
        cachedHotspots = hotspots
        cachedUnsafeZones = unsafeZones
    }
    
    /**
     * Update user profile
     */
    fun updateUserProfile(profile: UserProfile) {
        userProfile = profile
    }
    
    /**
     * Process location update
     */
    fun processLocation(location: LocationData) {
        movementTracker.updateWithLocation(location)
        updateSafetyState(location)
    }
    
    /**
     * Process sensor data update
     */
    fun processSensorData(sensorData: SensorData, currentLocation: LocationData?) {
        movementTracker.updateWithSensorData(sensorData)
        
        // Analyze sensor data for events
        val analysisResult = sensorAnalyzer.analyzeSensorData(sensorData)
        
        if (analysisResult != null) {
            handleDetectedEvent(analysisResult, currentLocation)
        }
        
        // Update overall state
        currentLocation?.let { updateSafetyState(it) }
    }
    
    /**
     * Process voice trigger detection
     */
    fun processVoiceTrigger(detected: Boolean) {
        voiceTriggerDetected = detected
        if (detected) {
            // Voice trigger boosts confidence of any pending event
            val currentEvent = _safetyState.value.currentEvent
            if (currentEvent != null) {
                val boostedConfidence = (currentEvent.confidence + SafetyConstants.WEIGHT_VOICE)
                    .coerceAtMost(1f)
                
                if (boostedConfidence >= SafetyConstants.EMERGENCY_CONFIDENCE_THRESHOLD) {
                    triggerEmergency(currentEvent.copy(confidence = boostedConfidence))
                }
            } else {
                // Voice trigger alone in heightened mode
                if (_safetyState.value.mode == SafetyMode.HEIGHTENED) {
                    val event = DetectedEvent(
                        type = EventType.VOICE_TRIGGER,
                        confidence = 0.8f,
                        location = _safetyState.value.location
                    )
                    triggerEmergency(event)
                }
            }
        }
    }
    
    /**
     * Trigger manual SOS
     */
    fun triggerManualSOS() {
        val event = DetectedEvent(
            type = EventType.MANUAL_SOS,
            confidence = 1.0f,
            location = _safetyState.value.location
        )
        triggerEmergency(event, requiresConfirmation = false)
    }
    
    /**
     * Trigger silent SOS - sends SMS only (no call) for discreet emergencies
     */
    fun triggerSilentSOS() {
        val event = DetectedEvent(
            type = EventType.MANUAL_SOS, // Use same type but will be handled silently
            confidence = 1.0f,
            location = _safetyState.value.location
        )
        val emergencyEvent = EmergencyEvent(
            type = event.type,
            confidence = event.confidence,
            location = event.location,
            requiresConfirmation = false,
            silent = true // Mark as silent
        )
        
        _emergencyEvent.value = emergencyEvent
        _safetyState.value = _safetyState.value.copy(
            isEmergency = true,
            currentEvent = event
        )
    }
    
    /**
     * Clear emergency event (cancelled by user)
     */
    fun clearEmergency() {
        _emergencyEvent.value = null
        _safetyState.value = _safetyState.value.copy(
            isEmergency = false,
            currentEvent = null
        )
    }
    
    /**
     * Update overall safety state based on current conditions
     */
    private fun updateSafetyState(location: LocationData) {
        // Calculate risk scores
        val accidentRisk = riskScoringEngine.calculateAccidentRisk(location, cachedHotspots)
        val unsafeZoneRisk = riskScoringEngine.calculateUnsafeZoneRisk(location, cachedUnsafeZones)
        
        // Combine risk scores (take max)
        val combinedRiskScore = maxOf(accidentRisk.score, unsafeZoneRisk.score)
        val combinedRiskLevel = when {
            combinedRiskScore >= SafetyConstants.HIGH_RISK_THRESHOLD -> RiskLevel.HIGH
            combinedRiskScore >= SafetyConstants.MEDIUM_RISK_THRESHOLD -> RiskLevel.MEDIUM
            else -> RiskLevel.LOW
        }
        
        // Determine safety mode
        val shouldHeighten = shouldActivateHeightenedMode(unsafeZoneRisk)
        val mode = if (shouldHeighten) SafetyMode.HEIGHTENED else SafetyMode.NORMAL
        
        // Check for inactivity concerns
        if (movementTracker.isInactivityConcerning() && 
            unsafeZoneRisk.isInUnsafeZone &&
            userProfile.gender == Gender.FEMALE) {
            
            // Inactivity in unsafe zone for female user
            val event = DetectedEvent(
                type = EventType.INACTIVITY_ALERT,
                confidence = calculateInactivityConfidence(unsafeZoneRisk),
                location = location
            )
            
            if (event.confidence >= SafetyConstants.EMERGENCY_CONFIDENCE_THRESHOLD) {
                triggerEmergency(event)
            }
        }
        
        _safetyState.value = _safetyState.value.copy(
            mode = mode,
            riskLevel = combinedRiskLevel,
            riskScore = combinedRiskScore,
            movementState = movementTracker.getMovementState(),
            location = location
        )
    }
    
    /**
     * Handle detected event from sensor analysis
     */
    private fun handleDetectedEvent(result: SensorAnalysisResult, location: LocationData?) {
        val event = DetectedEvent(
            type = result.eventType,
            confidence = result.confidence,
            location = location
        )
        
        // Calculate weighted confidence
        val weightedConfidence = calculateWeightedConfidence(event)
        val adjustedEvent = event.copy(confidence = weightedConfidence)
        
        _safetyState.value = _safetyState.value.copy(currentEvent = adjustedEvent)
        
        // Check if emergency threshold is crossed
        if (weightedConfidence >= SafetyConstants.EMERGENCY_CONFIDENCE_THRESHOLD) {
            triggerEmergency(adjustedEvent)
        }
    }
    
    /**
     * Calculate weighted confidence score
     */
    private fun calculateWeightedConfidence(event: DetectedEvent): Float {
        var totalWeight = 0f
        var weightedSum = 0f
        
        // Impact/Event score (from sensor analysis)
        weightedSum += event.confidence * SafetyConstants.WEIGHT_IMPACT
        totalWeight += SafetyConstants.WEIGHT_IMPACT
        
        // Zone risk score
        val currentState = _safetyState.value
        weightedSum += currentState.riskScore * SafetyConstants.WEIGHT_ZONE
        totalWeight += SafetyConstants.WEIGHT_ZONE
        
        // Time factor (night = higher risk)
        val timeScore = if (RiskScoringEngine.isNightTime()) 1f else 0.5f
        weightedSum += timeScore * SafetyConstants.WEIGHT_TIME
        totalWeight += SafetyConstants.WEIGHT_TIME
        
        // Inactivity factor
        val inactivityScore = movementTracker.getInactivityScore()
        weightedSum += inactivityScore * SafetyConstants.WEIGHT_INACTIVITY
        totalWeight += SafetyConstants.WEIGHT_INACTIVITY
        
        // Voice trigger factor
        if (voiceTriggerDetected) {
            weightedSum += 1f * SafetyConstants.WEIGHT_VOICE
            totalWeight += SafetyConstants.WEIGHT_VOICE
        }
        
        return (weightedSum / totalWeight).coerceIn(0f, 1f)
    }
    
    /**
     * Calculate inactivity confidence
     */
    private fun calculateInactivityConfidence(zoneRisk: RiskResult): Float {
        val baseConfidence = movementTracker.getInactivityScore()
        val zoneMultiplier = zoneRisk.score
        val timeMultiplier = if (RiskScoringEngine.isNightTime()) 1.3f else 1f
        
        return (baseConfidence * zoneMultiplier * timeMultiplier).coerceIn(0f, 1f)
    }
    
    /**
     * Determine if heightened safety mode should be activated
     */
    private fun shouldActivateHeightenedMode(unsafeZoneRisk: RiskResult): Boolean {
        // Only for female users
        if (userProfile.gender != Gender.FEMALE) return false
        
        // Night time or in unsafe zone
        return RiskScoringEngine.isNightTime() || 
               unsafeZoneRisk.isInUnsafeZone ||
               unsafeZoneRisk.level == RiskLevel.HIGH
    }
    
    /**
     * Trigger emergency event
     */
    private fun triggerEmergency(event: DetectedEvent, requiresConfirmation: Boolean = true) {
        val emergencyEvent = EmergencyEvent(
            type = event.type,
            confidence = event.confidence,
            location = event.location,
            requiresConfirmation = requiresConfirmation
        )
        
        _emergencyEvent.value = emergencyEvent
        _safetyState.value = _safetyState.value.copy(
            isEmergency = true,
            currentEvent = event
        )
    }
    
    /**
     * Reset engine state
     */
    fun reset() {
        sensorAnalyzer.reset()
        movementTracker.reset()
        voiceTriggerDetected = false
        _emergencyEvent.value = null
        _safetyState.value = SafetyState(
            mode = SafetyMode.NORMAL,
            riskLevel = RiskLevel.LOW,
            riskScore = 0f,
            isEmergency = false,
            currentEvent = null,
            movementState = MovementState.STATIONARY,
            location = null
        )
    }
}
