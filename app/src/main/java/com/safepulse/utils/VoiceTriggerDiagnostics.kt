package com.safepulse.utils

import android.util.Log

/**
 * Diagnostic utilities for voice trigger debugging
 */
object VoiceTriggerDiagnostics {
    
    private const val TAG = "VoiceDiagnostics"
    
    /**
     * Log system status for voice trigger
     */
    fun logSystemStatus(
        isServiceRunning: Boolean,
        isVoiceTriggerEnabled: Boolean,
        moduleType: String,
        isListening: Boolean,
        hasRecordAudioPermission: Boolean
    ) {
        Log.i(TAG, "=" .repeat(50))
        Log.i(TAG, "VOICE TRIGGER DIAGNOSTICS")
        Log.i(TAG, "=" .repeat(50))
        Log.i(TAG, "Service Running: $isServiceRunning")
        Log.i(TAG, "Voice Trigger Enabled: $isVoiceTriggerEnabled")
        Log.i(TAG, "Module Type: $moduleType")
        Log.i(TAG, "Is Listening: $isListening")
        Log.i(TAG, "Has RECORD_AUDIO Permission: $hasRecordAudioPermission")
        Log.i(TAG, "=" .repeat(50))
    }
    
    /**
     * Log audio processing details
     */
    fun logAudioProcessing(
        bufferSize: Int,
        energy: Float,
        confidence: Float,
        threshold: Float,
        detected: Boolean
    ) {
        val status = if (detected) "✅ DETECTED" else "❌ NOT DETECTED"
        Log.d(TAG, "Audio: buffer=$bufferSize, energy=%.3f, confidence=%.3f, threshold=%.3f → $status".format(energy, confidence, threshold))
    }
}
