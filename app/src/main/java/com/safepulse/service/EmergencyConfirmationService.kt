package com.safepulse.service

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import android.widget.Toast
import com.safepulse.ml.SpeechRecognitionSoundSuppressor
import com.safepulse.ml.VoiceKeywordMatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.Locale

/**
 * Confirms shake-triggered SOS with a short voice prompt.
 * User can say YES/help to proceed or NO/cancel/stop/nahi to cancel.
 */
class EmergencyConfirmationService(
    private val context: Context,
    private val scope: CoroutineScope
) {
    companion object {
        private const val TAG = "EmergencyConfirmation"
        private const val UTTERANCE_ID = "emergency_confirmation"
        private const val CONFIRMATION_TIMEOUT_SECONDS = 10
        private const val MAX_RESULTS = 8
        private const val COMPLETE_SILENCE_MS = 1_800L
        private const val POSSIBLE_COMPLETE_SILENCE_MS = 700L
        private const val MIN_SPEECH_MS = 500L
        private const val RESTART_LISTENING_MS = 900L
    }

    private var textToSpeech: TextToSpeech? = null
    private var speechRecognizer: SpeechRecognizer? = null
    private var isInitialized = false
    private var countdownJob: Job? = null
    private var confirmationCallback: ((Boolean) -> Unit)? = null
    private var isConfirmationInProgress = false
    private var isListeningForConfirmation = false
    private val mainHandler = Handler(Looper.getMainLooper())
    private val soundSuppressor = SpeechRecognitionSoundSuppressor(context)

    fun initialize(onReady: () -> Unit) {
        if (isInitialized) {
            onReady()
            return
        }

        textToSpeech = TextToSpeech(context) { status ->
            if (status == TextToSpeech.SUCCESS) {
                textToSpeech?.language = Locale.getDefault()
                textToSpeech?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) {
                        Log.d(TAG, "TTS started: $utteranceId")
                    }

                    override fun onDone(utteranceId: String?) {
                        Log.d(TAG, "TTS completed: $utteranceId")
                        if (utteranceId == UTTERANCE_ID) {
                            mainHandler.post { startListening() }
                        }
                    }

                    @Deprecated("Deprecated in Java")
                    override fun onError(utteranceId: String?) {
                        Log.e(TAG, "TTS error: $utteranceId")
                        if (utteranceId == UTTERANCE_ID) {
                            mainHandler.post { startListening() }
                        }
                    }
                })

                isInitialized = true
                Log.i(TAG, "TTS initialized successfully")
                onReady()
            } else {
                Log.e(TAG, "TTS initialization failed")
                isInitialized = false
            }
        }
    }

    fun startConfirmation(onConfirmed: (Boolean) -> Unit) {
        if (isConfirmationInProgress) {
            Log.w(TAG, "Confirmation already in progress")
            return
        }

        if (!isInitialized) {
            Log.e(TAG, "Confirmation service not initialized; proceeding with emergency")
            onConfirmed(true)
            return
        }

        isConfirmationInProgress = true
        confirmationCallback = onConfirmed

        Log.i(TAG, "Starting emergency confirmation")
        showToast("Emergency confirmation started - Say YES or NO")
        startCountdownTimer()

        textToSpeech?.stop()
        val params = Bundle().apply {
            putString(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID, UTTERANCE_ID)
        }
        textToSpeech?.speak("Do you need help?", TextToSpeech.QUEUE_FLUSH, params, UTTERANCE_ID)

        mainHandler.postDelayed({
            if (isConfirmationInProgress && !isListeningForConfirmation) {
                Log.i(TAG, "Starting speech recognition after TTS fallback delay")
                startListening()
            }
        }, 1_800L)
    }

    private fun showToast(message: String) {
        mainHandler.post {
            Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
        }
    }

    private fun startCountdownTimer() {
        countdownJob?.cancel()
        countdownJob = scope.launch {
            var secondsRemaining = CONFIRMATION_TIMEOUT_SECONDS
            while (secondsRemaining > 0 && isConfirmationInProgress) {
                Log.d(TAG, "Confirmation countdown: $secondsRemaining seconds remaining")
                delay(1_000L)
                secondsRemaining--
            }

            if (isConfirmationInProgress) {
                Log.i(TAG, "Confirmation timeout elapsed; proceeding with emergency")
                confirmEmergency()
            }
        }
    }

    private fun startListening() {
        try {
            if (!isConfirmationInProgress || isListeningForConfirmation) return

            if (!hasAudioPermission()) {
                Log.e(TAG, "RECORD_AUDIO permission missing; cannot listen for confirmation")
                textToSpeech?.speak(
                    "Microphone permission needed to cancel",
                    TextToSpeech.QUEUE_ADD,
                    null,
                    "permission_warning"
                )
                return
            }

            if (!SpeechRecognizer.isRecognitionAvailable(context)) {
                Log.e(TAG, "Speech recognition not available; waiting for timeout")
                return
            }

            showToast("Listening for YES or NO...")
            isListeningForConfirmation = true
            speechRecognizer?.destroy()
            speechRecognizer = SpeechRecognizer.createSpeechRecognizer(context).apply {
                setRecognitionListener(createRecognitionListener())
            }

            soundSuppressor.suppressStartTone()
            speechRecognizer?.startListening(createRecognitionIntent())
            Log.d(TAG, "Confirmation speech recognition started")
        } catch (e: Exception) {
            isListeningForConfirmation = false
            soundSuppressor.restore()
            Log.e(TAG, "Error starting confirmation recognition", e)
        }
    }

    private fun createRecognitionListener(): RecognitionListener {
        return object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {
                Log.d(TAG, "Ready for confirmation speech")
            }

            override fun onBeginningOfSpeech() {
                Log.d(TAG, "User started speaking")
            }

            override fun onRmsChanged(rmsdB: Float) = Unit
            override fun onBufferReceived(buffer: ByteArray?) = Unit

            override fun onEndOfSpeech() {
                Log.d(TAG, "User stopped speaking")
            }

            override fun onError(error: Int) {
                isListeningForConfirmation = false
                soundSuppressor.restore()
                Log.w(TAG, "Confirmation recognition error: ${errorName(error)}")

                if (error == SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS) {
                    showToast("Microphone permission denied")
                    return
                }

                if (isConfirmationInProgress) {
                    mainHandler.postDelayed({
                        if (isConfirmationInProgress) startListening()
                    }, RESTART_LISTENING_MS)
                }
            }

            override fun onResults(results: Bundle?) {
                isListeningForConfirmation = false
                soundSuppressor.restore()
                handleSpeechResults(
                    VoiceKeywordMatcher.prepareRecognitionResults(
                        results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION).orEmpty()
                    )
                )
            }

            override fun onPartialResults(partialResults: Bundle?) {
                handleSpeechResults(
                    VoiceKeywordMatcher.prepareRecognitionResults(
                        partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION).orEmpty()
                    ),
                    partial = true
                )
            }

            override fun onEvent(eventType: Int, params: Bundle?) = Unit
        }
    }

    private fun createRecognitionIntent(): android.content.Intent {
        return android.content.Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, preferredSpeechLanguageTag())
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, preferredSpeechLanguageTag())
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, MAX_RESULTS)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, false)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, COMPLETE_SILENCE_MS)
            putExtra(
                RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS,
                POSSIBLE_COMPLETE_SILENCE_MS
            )
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, MIN_SPEECH_MS)
        }
    }

    private fun handleSpeechResults(matches: List<String>, partial: Boolean = false) {
        if (!isConfirmationInProgress || matches.isEmpty()) return

        Log.i(TAG, "Confirmation speech results (${if (partial) "partial" else "final"}): $matches")

        val cancelMatch = VoiceKeywordMatcher.containsCancelWord(matches)
        if (cancelMatch.matched) {
            Log.i(TAG, "User cancelled emergency via voice: ${cancelMatch.sourceText}")
            Toast.makeText(context, "Emergency cancelled - monitoring still active", Toast.LENGTH_LONG).show()
            cancelEmergency()
            return
        }

        val confirmMatch = VoiceKeywordMatcher.containsConfirmationWord(matches)
        if (confirmMatch.matched || VoiceKeywordMatcher.findBestMatch(matches, minConfidence = 0.72f).matched) {
            Log.i(TAG, "User confirmed emergency via voice")
            showToast("Heard YES - Sending SOS")
            confirmEmergency()
            return
        }

        if (!partial) {
            Log.i(TAG, "No clear yes/no detected; continuing to listen until timeout")
            mainHandler.postDelayed({
                if (isConfirmationInProgress) startListening()
            }, RESTART_LISTENING_MS)
        }
    }

    private fun confirmEmergency() {
        if (!isConfirmationInProgress) return

        isConfirmationInProgress = false
        countdownJob?.cancel()
        stopListening()

        Log.i(TAG, "Emergency confirmed")
        confirmationCallback?.invoke(true)
        confirmationCallback = null
    }

    private fun cancelEmergency() {
        if (!isConfirmationInProgress) return

        isConfirmationInProgress = false
        countdownJob?.cancel()
        stopListening()

        Log.i(TAG, "Emergency cancelled by user")
        confirmationCallback?.invoke(false)
        confirmationCallback = null
    }

    private fun hasAudioPermission(): Boolean {
        return PackageManager.PERMISSION_GRANTED == context.checkSelfPermission(Manifest.permission.RECORD_AUDIO)
    }

    private fun stopListening() {
        try {
            isListeningForConfirmation = false
            speechRecognizer?.cancel()
            speechRecognizer?.destroy()
            speechRecognizer = null
            soundSuppressor.restore()
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping confirmation recognition", e)
        }
    }

    private fun preferredSpeechLanguageTag(): String {
        val locale = Locale.getDefault()
        return if (locale.language.equals("en", ignoreCase = true) || locale.language.isBlank()) {
            "en-IN"
        } else {
            locale.toLanguageTag()
        }
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

    fun cleanup() {
        isConfirmationInProgress = false
        confirmationCallback = null
        countdownJob?.cancel()
        mainHandler.removeCallbacksAndMessages(null)

        stopListening()
        soundSuppressor.release()

        textToSpeech?.stop()
        textToSpeech?.shutdown()
        textToSpeech = null

        isInitialized = false
        Log.d(TAG, "EmergencyConfirmationService cleaned up")
    }
}
