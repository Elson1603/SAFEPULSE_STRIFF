package com.safepulse.ml

import android.speech.SpeechRecognizer
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Interface for voice-based emergency trigger detection.
 * This is designed to be replaced with a real ML model in production.
 */
interface VoiceTriggerModule {
    
    /**
     * Start listening for voice keywords
     */
    fun startListening()
    
    /**
     * Stop listening
     */
    fun stopListening()
    
    /**
     * Check if currently listening
     */
    fun isListening(): Boolean
    
    /**
     * Flow of keyword detection events
     */
    fun keywordDetectedFlow(): Flow<VoiceDetectionResult>
    
    /**
     * Get supported keywords
     */
    fun getSupportedKeywords(): List<String>
}

/**
 * Result of voice keyword detection
 */
data class VoiceDetectionResult(
    val detected: Boolean,
    val keyword: String?,
    val confidence: Float,
    val timestamp: Long = System.currentTimeMillis()
)

/**
 * Stub implementation of VoiceTriggerModule for prototype.
 * Simulates keyword detection for testing purposes.
 * 
 * In production, this would be replaced with:
 * - TensorFlow Lite model for keyword spotting
 * - or Google Speech Recognition API
 * - or PocketSphinx for offline recognition
 */
class StubVoiceTriggerModule : VoiceTriggerModule {
    
    companion object {
        val SUPPORTED_KEYWORDS = VoiceKeywordMatcher.supportedKeywords
    }
    
    private var listening = false
    
    private val _keywordDetected = MutableStateFlow(
        VoiceDetectionResult(false, null, 0f)
    )
    
    override fun startListening() {
        listening = true
    }
    
    override fun stopListening() {
        listening = false
        _keywordDetected.value = VoiceDetectionResult(false, null, 0f)
    }
    
    override fun isListening(): Boolean = listening
    
    override fun keywordDetectedFlow(): Flow<VoiceDetectionResult> = _keywordDetected.asStateFlow()
    
    override fun getSupportedKeywords(): List<String> = SUPPORTED_KEYWORDS
    
    /**
     * Simulate keyword detection (for testing/demo purposes)
     * In a real implementation, this would be triggered by audio analysis
     */
    fun simulateKeywordDetection(keyword: String = "help") {
        if (listening) {
            _keywordDetected.value = VoiceDetectionResult(
                detected = true,
                keyword = keyword,
                confidence = 0.85f
            )
        }
    }
    
    /**
     * Clear detection state
     */
    fun clearDetection() {
        _keywordDetected.value = VoiceDetectionResult(false, null, 0f)
    }
    
    /**
     * Simulate processing text input (for demo UI)
     * Returns true if any keyword is found in the text
     */
    fun processTextInput(text: String): Boolean {
        if (!listening) return false
        
        val match = VoiceKeywordMatcher.findBestMatch(listOf(text))
        if (match.matched && match.keyword != null) {
            simulateKeywordDetection(match.keyword)
            return true
        }
        return false
    }
}

/**
 * Factory for creating voice trigger modules
 */
object VoiceTriggerFactory {
    
    /**
     * Create the appropriate voice trigger implementation.
     * Returns TFLite implementation if model is available, otherwise stub.
     */
    fun create(context: android.content.Context): VoiceTriggerModule {
        if (SpeechRecognizer.isRecognitionAvailable(context)) {
            android.util.Log.d("VoiceTriggerFactory", "Using Android speech recognition voice trigger module")
            return AndroidSpeechVoiceTriggerModule(context)
        }

        return try {
            // Check if TFLite model exists
            context.assets.open("keyword_spotting_model.tflite").close()
            
            // Model exists, use TFLite implementation
            android.util.Log.d("VoiceTriggerFactory", "Using TFLite voice trigger module")
            TFLiteVoiceTriggerModule(context)
        } catch (e: Exception) {
            // Model not found, use stub for testing
            android.util.Log.w("VoiceTriggerFactory", "TFLite model not found, using stub implementation")
            StubVoiceTriggerModule()
        }
    }
}
