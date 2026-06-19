package com.safepulse.ml

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * Handles continuous audio recording for voice trigger detection
 */
class AudioRecorder {
    
    companion object {
        private const val TAG = "AudioRecorder"
        const val SAMPLE_RATE = 16000 // 16kHz for speech recognition
        const val CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO
        const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT
        const val RECORDING_BUFFER_SIZE_MULTIPLIER = 2
    }
    
    private var audioRecord: AudioRecord? = null
    private var recordingJob: Job? = null
    private var isRecording = false
    
    private val _audioDataFlow = MutableSharedFlow<ShortArray>(replay = 0)
    val audioDataFlow: SharedFlow<ShortArray> = _audioDataFlow.asSharedFlow()
    
    /**
     * Start recording audio
     */
    fun startRecording(scope: CoroutineScope, bufferSize: Int = 512) {
        if (isRecording) {
            Log.w(TAG, "Already recording")
            return
        }
        
        try {
            val minBufferSize = AudioRecord.getMinBufferSize(
                SAMPLE_RATE,
                CHANNEL_CONFIG,
                AUDIO_FORMAT
            )
            
            if (minBufferSize == AudioRecord.ERROR || minBufferSize == AudioRecord.ERROR_BAD_VALUE) {
                Log.e(TAG, "Invalid buffer size: $minBufferSize")
                return
            }
            
            val actualBufferSize = maxOf(minBufferSize, bufferSize * RECORDING_BUFFER_SIZE_MULTIPLIER)
            
            audioRecord = AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                SAMPLE_RATE,
                CHANNEL_CONFIG,
                AUDIO_FORMAT,
                actualBufferSize
            )
            
            if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
                Log.e(TAG, "AudioRecord initialization failed")
                audioRecord = null
                return
            }
            
            audioRecord?.startRecording()
            isRecording = true
            
            Log.d(TAG, "Started recording - Sample Rate: $SAMPLE_RATE, Buffer: $bufferSize")
            
            recordingJob = scope.launch(Dispatchers.IO) {
                val buffer = ShortArray(bufferSize)
                
                while (isActive && isRecording) {
                    val readSize = audioRecord?.read(buffer, 0, buffer.size) ?: 0
                    
                    if (readSize > 0) {
                        // Emit audio data
                        _audioDataFlow.emit(buffer.copyOf(readSize))
                    } else if (readSize < 0) {
                        Log.e(TAG, "Error reading audio: $readSize")
                        break
                    }
                }
            }
            
        } catch (e: SecurityException) {
            Log.e(TAG, "No RECORD_AUDIO permission", e)
        } catch (e: Exception) {
            Log.e(TAG, "Error starting recording", e)
            stopRecording()
        }
    }
    
    /**
     * Stop recording audio
     */
    fun stopRecording() {
        if (!isRecording) return
        
        isRecording = false
        recordingJob?.cancel()
        
        try {
            audioRecord?.apply {
                if (state == AudioRecord.STATE_INITIALIZED) {
                    stop()
                }
                release()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping recording", e)
        } finally {
            audioRecord = null
            Log.d(TAG, "Stopped recording")
        }
    }
    
    /**
     * Check if currently recording
     */
    fun isRecording(): Boolean = isRecording
}
