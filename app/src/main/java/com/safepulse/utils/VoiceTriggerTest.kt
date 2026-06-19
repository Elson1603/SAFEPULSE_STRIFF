package com.safepulse.utils

import android.content.Context
import android.util.Log
import com.safepulse.service.SafetyForegroundService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Manual test utility for voice trigger debugging
 */
object VoiceTriggerTest {
    
    private const val TAG = "VoiceTriggerTest"
    
    /**
     * Simulate keyword detection to test the emergency flow
     * This bypasses audio capture to test if the rest of the system works
     */
    fun simulateKeywordDetection(keyword: String = "help") {
        Log.i(TAG, "====================================")
        Log.i(TAG, "MANUAL TEST: Simulating keyword detection")
        Log.i(TAG, "Keyword: $keyword")
        Log.i(TAG, "====================================")
        
        val service = SafetyForegroundService.getInstance()
        if (service == null) {
            Log.e(TAG, "❌ Safety service is not running!")
            Log.e(TAG, "   Go to Home screen and enable monitoring first")
            return
        }
        
        Log.i(TAG, "✅ Service is running, triggering manual SOS...")
        service.triggerManualSOS()
        
        Log.i(TAG, "✅ Manual SOS triggered - check if SMS/calls are made")
    }
    
    /**
     * Log current voice trigger status
     */
    fun logVoiceTriggerStatus(context: Context) {
        Log.i(TAG, "====================================")
        Log.i(TAG, "VOICE TRIGGER STATUS CHECK")
        Log.i(TAG, "====================================")
        
        val service = SafetyForegroundService.getInstance()
        if (service == null) {
            Log.w(TAG, "⚠️ Safety service is NOT running")
            Log.w(TAG, "   Enable monitoring on Home screen first")
        } else {
            Log.i(TAG, "✅ Safety service is running")
        }
        
        // Check if model file exists
        try {
            context.assets.open("keyword_spotting_model.tflite").use {
                Log.i(TAG, "✅ TFLite model file found in assets")
            }
        } catch (e: Exception) {
            Log.e(TAG, "❌ TFLite model file NOT found in assets!")
        }
        
        Log.i(TAG, "====================================")
    }
}
