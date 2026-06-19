package com.safepulse.ml

import android.content.Context
import android.content.res.AssetFileDescriptor
import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.tensorflow.lite.Interpreter
import java.io.FileInputStream
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel
import kotlin.math.max

/**
 * TensorFlow Lite implementation of VoiceTriggerModule
 * Uses keyword spotting model for real-time voice trigger detection
 */
class TFLiteVoiceTriggerModule(private val context: Context) : VoiceTriggerModule {
    
    companion object {
        private const val TAG = "TFLiteVoiceTrigger"
        private const val MODEL_PATH = "keyword_spotting_model.tflite"
        
        // Audio configuration
        private const val SAMPLE_RATE = 16000
        private const val INPUT_LENGTH = 16000 // 1 second of audio at 16kHz
        
        // Energy-based detection thresholds
        // Adjust these values to change sensitivity:
        // - Lower = more sensitive (may trigger on normal speech)
        // - Higher = less sensitive (requires louder voice)
        private const val VOICE_ENERGY_THRESHOLD = 0.15f  // Normal voice sensitivity
        private const val MIN_SAMPLES_FOR_DETECTION = 5   // ~500ms sustained sound
        private const val TRIGGER_COOLDOWN_MS = 3000L    // 3 seconds between triggers
        
        // Legacy - kept for compatibility
        val KEYWORD_NAMES = listOf("loud_voice")  // Generic detection
        val SUPPORTED_KEYWORDS = KEYWORD_NAMES
    }
    
    private var interpreter: Interpreter? = null
    private val audioRecorder = AudioRecorder()
    private var listening = false
    private var processingScope: CoroutineScope? = null
    
    private val _keywordDetected = MutableStateFlow(
        VoiceDetectionResult(false, null, 0f)
    )
    
    //Debug counter for periodic logging
    private var debugLogCounter = 0
    
    // Energy history for sustained detection
    private val energyHistory = mutableListOf<Float>()
    
    private var audioBuffer = mutableListOf<Short>()
    private val bufferLock = Any()
    
    init {
        loadModel()
    }
    
    /**
     * Load TFLite model - DISABLED
     * Using energy-based detection instead of keyword spotting model
     */
    private fun loadModel() {
        Log.i(TAG, "🎤 Voice Detection Mode: Energy-based (no model required)")
        Log.i(TAG, "   Detects sustained loud vocalizations")
        Log.i(TAG, "   Energy threshold: $VOICE_ENERGY_THRESHOLD")
        Log.i(TAG, "   Works in any language - just shout loudly!")
        
        // Model loading disabled - corrupted file, using simple energy detection instead
        interpreter = null
    }
    
    /**
     * Load model file from assets
     */
    private fun loadModelFile(): MappedByteBuffer {
        val fileDescriptor: AssetFileDescriptor = context.assets.openFd(MODEL_PATH)
        val inputStream = FileInputStream(fileDescriptor.fileDescriptor)
        val fileChannel = inputStream.channel
        val startOffset = fileDescriptor.startOffset
        val declaredLength = fileDescriptor.length
        return fileChannel.map(FileChannel.MapMode.READ_ONLY, startOffset, declaredLength)
    }
    
    override fun startListening() {
        if (listening) {
            Log.w(TAG, "⚠️ Already listening")
            return
        }
        
        listening = true
        processingScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        
        Log.i(TAG, "🎤 Starting voice trigger with LOUD SHOUT detection")
        Log.i(TAG, "   Energy threshold: $VOICE_ENERGY_THRESHOLD")
        Log.i(TAG, "   Detection window: ~${MIN_SAMPLES_FOR_DETECTION * 100}ms")
        Log.i(TAG, "   Sample rate: $SAMPLE_RATE Hz")
        Log.i(TAG, "   💡 TIP: Shout loudly for emergency detection!")
        Log.i(TAG, "   Supported keywords: $SUPPORTED_KEYWORDS")
        
        // Start audio recording
        audioRecorder.startRecording(processingScope!!, bufferSize = 512)
        
        if (!audioRecorder.isRecording()) {
            Log.e(TAG, "❌ AudioRecorder failed to start! Check RECORD_AUDIO permission.")
            listening = false
            processingScope?.cancel()
            return
        }
        
        // Collect and process audio data
        processingScope!!.launch {
            Log.d(TAG, "👂 Audio collection started, waiting for audio chunks...")
            var chunkCount = 0
            audioRecorder.audioDataFlow.collect { audioChunk ->
                chunkCount++
                if (chunkCount % 50 == 0) {  // Log every 50 chunks to avoid spam
                    Log.v(TAG, "📊 Processed $chunkCount audio chunks (${'$'}{audioChunk.size} samples each)")
                }
                processAudioChunk(audioChunk)
            }
        }
        
        Log.i(TAG, "✅ Voice trigger listening started successfully!")
    }
    
    override fun stopListening() {
        if (!listening) return
        
        listening = false
        audioRecorder.stopRecording()
        processingScope?.cancel()
        processingScope = null
        
        synchronized(bufferLock) {
            audioBuffer.clear()
        }
        
        _keywordDetected.value = VoiceDetectionResult(false, null, 0f)
        Log.d(TAG, "Stopped listening")
    }
    
    override fun isListening(): Boolean = listening
    
    override fun keywordDetectedFlow(): Flow<VoiceDetectionResult> = _keywordDetected.asStateFlow()
    
    override fun getSupportedKeywords(): List<String> = SUPPORTED_KEYWORDS
    
    /**
     * Process incoming audio chunk
     */
    private fun processAudioChunk(chunk: ShortArray) {
        synchronized(bufferLock) {
            // Add new chunk to buffer
            audioBuffer.addAll(chunk.toList())
            
            // If we have enough samples, run inference
            if (audioBuffer.size >= INPUT_LENGTH) {
                val samples = audioBuffer.take(INPUT_LENGTH).toShortArray()
                
                // Remove processed samples (sliding window)
                val overlap = INPUT_LENGTH / 2 // 50% overlap for better detection
                val toRemove = INPUT_LENGTH - overlap
                repeat(toRemove) { audioBuffer.removeAt(0) }
                
                // Run inference on background thread
                runInference(samples)
            }
        }
    }
    
    /**
     * Process audio samples using energy-based detection
     * Detects sustained loud vocalizations (shouting/yelling) as emergency trigger
     */
    private fun runInference(samples: ShortArray) {
        try {
            // Calculate audio energy (RMS volume)
            val energy = AudioPreprocessor.calculateRMS(AudioPreprocessor.normalizeAudio(samples))
            
            // Log energy levels periodically
            if (System.currentTimeMillis() % 3000 < 100) { // Every ~3 seconds
                Log.d(TAG, "🔊 Audio energy: %.4f (threshold: $VOICE_ENERGY_THRESHOLD)".format(energy))
            }
            
            // Add energy to history for sustained detection
            energyHistory.add(energy)
            
            // Keep only recent history (last 10 samples = ~1 second)
            if (energyHistory.size > 10) {
                energyHistory.removeAt(0)
            }
            
            // Check for sustained loud vocalization
            if (energyHistory.size >= MIN_SAMPLES_FOR_DETECTION) {
                val avgEnergy = energyHistory.average().toFloat()
                val peakEnergy = energyHistory.maxOrNull() ?: 0f
                
                // Detection criteria:
                // 1. Average energy above threshold (sustained)
                // 2. Peak energy significantly high (loud enough)
                // 3. Consistent energy (not just a spike)
                
                val isSustained = avgEnergy >= VOICE_ENERGY_THRESHOLD
                val isLoudEnough = peakEnergy >= VOICE_ENERGY_THRESHOLD * 1.2f
                val isConsistent = energyHistory.count { it >= VOICE_ENERGY_THRESHOLD * 0.8f } >= MIN_SAMPLES_FOR_DETECTION / 2
                
                if (isSustained && isLoudEnough && isConsistent) {
                    // VOICE DETECTED! 
                    Log.i(TAG, "")
                    Log.i(TAG, "🔥🔥🔥 LOUD VOICE DETECTED! 🔥🔥🔥")
                    Log.i(TAG, "   Average Energy: %.3f".format(avgEnergy))
                    Log.i(TAG, "   Peak Energy: %.3f".format(peakEnergy))
                    Log.i(TAG, "   Duration: ~${'$'}{energyHistory.size * 100}ms")
                    Log.i(TAG, "   Threshold: $VOICE_ENERGY_THRESHOLD")
                    Log.i(TAG, "")
                    
                    // Emit detection event
                    _keywordDetected.value = VoiceDetectionResult(
                        detected = true,
                        keyword = "loud_voice", // Generic detection, not keyword-specific
                        confidence = avgEnergy / VOICE_ENERGY_THRESHOLD // Ratio above threshold
                    )
                    
                    // Clear history to prevent repeated triggers
                    energyHistory.clear()
                    
                    // Reset detection after cooldown
                    processingScope?.launch {
                        delay(TRIGGER_COOLDOWN_MS)
                        _keywordDetected.value = VoiceDetectionResult(false, null, 0f)
                    }
                }
            }
            
        } catch (e: Exception) {
            Log.e(TAG, "❌ Inference error: ${'$'}{e.message}", e)
            e.printStackTrace()
        }
    }
    
    /**
     * Release resources
     */
    fun release() {
        stopListening()
        interpreter?.close()
        interpreter = null
        Log.d(TAG, "Resources released")
    }
}
