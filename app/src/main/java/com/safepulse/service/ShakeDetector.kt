package com.safepulse.service

import android.hardware.SensorEvent
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import kotlin.math.sqrt

/**
 * Detects shake gestures for emergency trigger
 * Monitors accelerometer data to detect 3 shakes within 2 seconds
 */
class ShakeDetector(private val scope: CoroutineScope) {
    
    companion object {
        private const val TAG = "ShakeDetector"
        
        // Shake detection parameters
        private const val SHAKE_THRESHOLD = 15.0f // Acceleration threshold in m/s²
        private const val SHAKE_WINDOW_MS = 2000L // Time window for 3 shakes
        private const val MIN_SHAKE_INTERVAL_MS = 200L // Minimum time between shakes
    }
    
    private val shakeTimes = mutableListOf<Long>()
    private var lastShakeTime = 0L
    
    private val _tripleShakeDetected = MutableSharedFlow<Unit>(replay = 0)
    val tripleShakeDetected: SharedFlow<Unit> = _tripleShakeDetected.asSharedFlow()
    
    /**
     * Process accelerometer data to detect shakes
     * Call this with each accelerometer sensor event
     */
    fun onAccelerometerChanged(event: SensorEvent) {
        val x = event.values[0]
        val y = event.values[1]
        val z = event.values[2]
        
        // Calculate total acceleration (magnitude)
        val acceleration = sqrt((x * x + y * y + z * z).toDouble()).toFloat()
        
        // Subtract gravity (9.8 m/s²) to get just the movement
        val netAcceleration = acceleration - 9.8f
        
        // Detect shake if acceleration exceeds threshold
        if (netAcceleration > SHAKE_THRESHOLD) {
            val currentTime = System.currentTimeMillis()
            
            // Debounce - ignore if too soon after last shake
            if (currentTime - lastShakeTime < MIN_SHAKE_INTERVAL_MS) {
                return
            }
            
            lastShakeTime = currentTime
            
            // Clean up old shakes outside the window
            shakeTimes.removeAll { currentTime - it > SHAKE_WINDOW_MS }
            
            // Add current shake
            shakeTimes.add(currentTime)
            
            Log.d(TAG, "Shake detected! (${ shakeTimes.size}/3) acceleration=${String.format("%.2f", netAcceleration)} m/s²")
            
            // Check for triple shake
            if (shakeTimes.size >= 3) {
                val firstShake = shakeTimes[shakeTimes.size - 3]
                val thirdShake = shakeTimes[shakeTimes.size - 1]
                
                if (thirdShake - firstShake <= SHAKE_WINDOW_MS) {
                    Log.i(TAG, "")
                    Log.i(TAG, "🚨🚨🚨 TRIPLE SHAKE DETECTED! 🚨🚨🚨")
                    Log.i(TAG, "   Time window: ${thirdShake - firstShake}ms")
                    Log.i(TAG, "   Triggering emergency response...")
                    Log.i(TAG, "")
                    
                    // Clear shakes to avoid repeated triggers
                    shakeTimes.clear()
                    
                    // Emit event
                    scope.launch {
                        _tripleShakeDetected.emit(Unit)
                    }
                }
            }
        }
    }
    
    /**
     * Reset detection state
     */
    fun reset() {
        shakeTimes.clear()
        lastShakeTime = 0L
        Log.d(TAG, "Detection state reset")
    }
}
