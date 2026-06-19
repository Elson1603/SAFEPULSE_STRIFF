package com.safepulse.service

import android.content.Context
import android.util.Log
import android.view.KeyEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch

/**
 * Detects triple volume button presses for emergency trigger
 * Monitors volume down button presses within a time window
 */
class VolumeButtonDetector(
    private val context: Context,
    private val scope: CoroutineScope
) {
    
    companion object {
        private const val TAG = "VolumeButtonDetector"
        private const val TRIPLE_PRESS_WINDOW_MS = 2000L // 2 seconds window
        private const val MIN_PRESS_INTERVAL_MS = 100L // Minimum time between presses
    }
    
    private val pressTimes = mutableListOf<Long>()
    private val _triplePressDetected = MutableSharedFlow<Unit>(replay = 0)
    val triplePressDetected: SharedFlow<Unit> = _triplePressDetected.asSharedFlow()
    
    /**
     * Handle volume button key event
     * Call this from the service's onKeyDown/onKeyUp
     */
    fun onVolumeButtonPressed(keyCode: Int): Boolean {
        if (keyCode != KeyEvent.KEYCODE_VOLUME_DOWN) {
            return false
        }
        
        val currentTime = System.currentTimeMillis()
        
        // Clean up old presses outside the window
        pressTimes.removeAll { currentTime - it > TRIPLE_PRESS_WINDOW_MS }
        
        // Check if this press is too soon after the last one (debounce)
        if (pressTimes.isNotEmpty()) {
            val lastPressTime = pressTimes.last()
            if (currentTime - lastPressTime < MIN_PRESS_INTERVAL_MS) {
                Log.v(TAG, "Ignoring button press (too soon)")
                return false
            }
        }
        
        // Add current press
        pressTimes.add(currentTime)
        Log.d(TAG, "Volume down pressed (${pressTimes.size}/3)")
        
        // Check for triple press
        if (pressTimes.size >= 3) {
            val firstPress = pressTimes[pressTimes.size - 3]
            val thirdPress = pressTimes[pressTimes.size - 1]
            
            if (thirdPress - firstPress <= TRIPLE_PRESS_WINDOW_MS) {
                Log.i(TAG, "")
                Log.i(TAG, "🚨🚨🚨 TRIPLE VOLUME PRESS DETECTED! 🚨🚨🚨")
                Log.i(TAG, "   Time window: ${thirdPress - firstPress}ms")
                Log.i(TAG, "   Triggering emergency response...")
                Log.i(TAG, "")
                
                // Clear presses to avoid repeated triggers
                pressTimes.clear()
                
                // Emit event
                scope.launch {
                    _triplePressDetected.emit(Unit)
                }
                
                return true
            }
        }
        
        return false
    }
    
    /**
     * Reset detection state
     */
    fun reset() {
        pressTimes.clear()
        Log.d(TAG, "Detection state reset")
    }
}
