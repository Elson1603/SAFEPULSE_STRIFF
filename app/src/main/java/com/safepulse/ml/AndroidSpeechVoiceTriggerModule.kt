package com.safepulse.ml

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Locale

/**
 * Real speech-recognition implementation for hands-free SOS.
 * Keeps short recognition sessions alive while monitoring is active and uses
 * VoiceKeywordMatcher for phrase-level detection.
 */
class AndroidSpeechVoiceTriggerModule(private val context: Context) : VoiceTriggerModule {

    companion object {
        private const val TAG = "AndroidSpeechVoice"
        private const val RESTART_DELAY_MS = 1_500L
        private const val QUIET_RESTART_DELAY_MS = 3_000L
        private const val BUSY_RESTART_DELAY_MS = 4_000L
        private const val POST_DETECTION_COOLDOWN_MS = 4_000L
        private const val COMPLETE_SILENCE_MS = 3_000L
        private const val POSSIBLE_COMPLETE_SILENCE_MS = 1_500L
        private const val MIN_SESSION_MS = 1_000L
    }

    private val appContext = context.applicationContext
    private val mainHandler = Handler(Looper.getMainLooper())
    private val soundSuppressor = SpeechRecognitionSoundSuppressor(appContext)
    private var speechRecognizer: SpeechRecognizer? = null
    private var listening = false
    private var restartScheduled = false
    private var lastDetectionAt = 0L

    private val _keywordDetected = MutableStateFlow(VoiceDetectionResult(false, null, 0f))

    override fun startListening() {
        if (listening) return
        listening = true
        mainHandler.post { startRecognitionSession() }
    }

    override fun stopListening() {
        listening = false
        restartScheduled = false
        mainHandler.removeCallbacksAndMessages(null)
        mainHandler.post {
            stopRecognizer()
            _keywordDetected.value = VoiceDetectionResult(false, null, 0f)
        }
    }

    override fun isListening(): Boolean = listening

    override fun keywordDetectedFlow(): Flow<VoiceDetectionResult> = _keywordDetected.asStateFlow()

    override fun getSupportedKeywords(): List<String> = VoiceKeywordMatcher.supportedKeywords

    private fun startRecognitionSession() {
        if (!listening) return

        if (!hasAudioPermission()) {
            Log.e(TAG, "RECORD_AUDIO permission missing; voice SOS cannot listen")
            listening = false
            return
        }

        if (!SpeechRecognizer.isRecognitionAvailable(appContext)) {
            Log.e(TAG, "Speech recognition is not available on this device")
            listening = false
            return
        }

        try {
            stopRecognizer()
            speechRecognizer = SpeechRecognizer.createSpeechRecognizer(appContext).apply {
                setRecognitionListener(createRecognitionListener())
                soundSuppressor.suppressStartTone()
                startListening(createRecognitionIntent())
            }
            Log.i(TAG, "Voice SOS recognition session started")
        } catch (e: Exception) {
            soundSuppressor.restore()
            Log.e(TAG, "Failed to start voice recognition", e)
            scheduleRestart()
        }
    }

    private fun createRecognitionListener(): RecognitionListener {
        return object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {
                Log.d(TAG, "Ready for voice SOS speech")
            }

            override fun onBeginningOfSpeech() {
                Log.d(TAG, "Voice input started")
            }

            override fun onRmsChanged(rmsdB: Float) = Unit

            override fun onBufferReceived(buffer: ByteArray?) = Unit

            override fun onEndOfSpeech() {
                Log.d(TAG, "Voice input ended")
            }

            override fun onError(error: Int) {
                Log.d(TAG, "Speech recognizer ended with ${errorName(error)}")
                val delayMs = when (error) {
                    SpeechRecognizer.ERROR_NO_MATCH,
                    SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> QUIET_RESTART_DELAY_MS
                    SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> BUSY_RESTART_DELAY_MS
                    SpeechRecognizer.ERROR_CLIENT -> {
                        stopRecognizer()
                        BUSY_RESTART_DELAY_MS
                    }
                    SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> {
                        listening = false
                        stopRecognizer()
                        _keywordDetected.value = VoiceDetectionResult(false, null, 0f)
                        return
                    }
                    else -> RESTART_DELAY_MS
                }
                scheduleRestart(delayMs)
            }

            override fun onResults(results: Bundle?) {
                handleRecognitionResults(
                    results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION).orEmpty(),
                    partial = false
                )
                scheduleRestart(RESTART_DELAY_MS)
            }

            override fun onPartialResults(partialResults: Bundle?) {
                handleRecognitionResults(
                    partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION).orEmpty(),
                    partial = true
                )
            }

            override fun onEvent(eventType: Int, params: Bundle?) = Unit
        }
    }

    private fun handleRecognitionResults(results: List<String>, partial: Boolean) {
        if (!listening || results.isEmpty()) return

        val match = VoiceKeywordMatcher.findBestMatch(
            results = results,
            minConfidence = if (partial) 0.74f else 0.62f
        )

        if (!match.matched) {
            if (!partial) Log.d(TAG, "No voice SOS keyword in results: $results")
            return
        }

        val now = System.currentTimeMillis()
        if (now - lastDetectionAt < POST_DETECTION_COOLDOWN_MS) return
        lastDetectionAt = now

        Log.i(
            TAG,
            "Voice SOS keyword detected: ${match.keyword}, confidence=${"%.2f".format(match.confidence)}, text=\"${match.sourceText}\""
        )

        _keywordDetected.value = VoiceDetectionResult(
            detected = true,
            keyword = match.keyword,
            confidence = match.confidence
        )

        mainHandler.postDelayed({
            if (listening) {
                _keywordDetected.value = VoiceDetectionResult(false, null, 0f)
            }
        }, POST_DETECTION_COOLDOWN_MS)
    }

    private fun scheduleRestart(delayMs: Long = RESTART_DELAY_MS) {
        if (!listening || restartScheduled) return
        restartScheduled = true
        mainHandler.postDelayed({
            restartScheduled = false
            if (listening) startRecognitionSession()
        }, delayMs)
    }

    private fun createRecognitionIntent(): Intent {
        return Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, appContext.packageName)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault())
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, Locale.getDefault())
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 5)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, false)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, COMPLETE_SILENCE_MS)
            putExtra(
                RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS,
                POSSIBLE_COMPLETE_SILENCE_MS
            )
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, MIN_SESSION_MS)
        }
    }

    private fun stopRecognizer() {
        try {
            speechRecognizer?.cancel()
            speechRecognizer?.destroy()
        } catch (e: Exception) {
            Log.w(TAG, "Error while stopping speech recognizer", e)
        } finally {
            soundSuppressor.restore()
            speechRecognizer = null
        }
    }

    private fun hasAudioPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            appContext,
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
    }

    private fun errorName(error: Int): String {
        return when (error) {
            SpeechRecognizer.ERROR_AUDIO -> "ERROR_AUDIO"
            SpeechRecognizer.ERROR_CLIENT -> "ERROR_CLIENT"
            SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "ERROR_INSUFFICIENT_PERMISSIONS"
            SpeechRecognizer.ERROR_NETWORK -> "ERROR_NETWORK"
            SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "ERROR_NETWORK_TIMEOUT"
            SpeechRecognizer.ERROR_NO_MATCH -> "ERROR_NO_MATCH"
            SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "ERROR_RECOGNIZER_BUSY"
            SpeechRecognizer.ERROR_SERVER -> "ERROR_SERVER"
            SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "ERROR_SPEECH_TIMEOUT"
            else -> "ERROR_$error"
        }
    }
}
