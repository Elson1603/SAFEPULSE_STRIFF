package com.safepulse.wear.presentation

import android.Manifest
import android.app.Application
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.safepulse.wear.WearSafePulseApp
import com.safepulse.wear.data.*
import com.safepulse.wear.service.WearSafetyService
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.util.Locale
import kotlin.math.roundToInt

/**
 * UI state for the watch home screen.
 */
data class WearHomeState(
    val safetyState: WearSafetyState = WearSafetyState(),
    val emergencyContacts: List<WearEmergencyContact> = emptyList(),
    val isServiceRunning: Boolean = false,
    val isSosActive: Boolean = false,
    val sosCountdown: Int = -1, // -1 means not counting
    val heartRate: Int = 0,
    val heartRateSensorAvailable: Boolean = true,
    val heartRatePermissionGranted: Boolean = true,
    val heartRateMonitoringEnabled: Boolean = true,
    val isPhoneConnected: Boolean = false,
    val actionInProgress: Boolean = false,
    val lastActionMessage: String = "",
    val lastActionSuccessful: Boolean = true,
    val voiceSosAvailable: Boolean = true,
    val voiceSosPermissionGranted: Boolean = true,
    val voiceSosListening: Boolean = false,
    val voiceSosTranscript: String = "",
    val backgroundVoiceSosEnabled: Boolean = false
)

class WearHomeViewModel(application: Application) : AndroidViewModel(application), SensorEventListener {

    companion object {
        private const val TAG = "WearVoiceSOS"
    }

    private val preferences = WearPreferences(application)
    private val communicationManager: PhoneCommunicationManager =
        (application as WearSafePulseApp).communicationManager
    private val sensorManager =
        application.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val heartRateSensor = sensorManager.getDefaultSensor(Sensor.TYPE_HEART_RATE)
    private var heartRateListenerRegistered = false
    private var heartRateMonitoringEnabled = true
    private var lastHeartRateSentAtMillis = 0L
    private var speechRecognizer: SpeechRecognizer? = null
    private var lastVoiceSosSentAtMillis = 0L

    private val _state = MutableStateFlow(WearHomeState())
    val state: StateFlow<WearHomeState> = _state.asStateFlow()

    init {
        refreshHeartRateSensorStatus()
        refreshVoiceSosStatus()
        observePhoneData()
        observePreferences()
        ensureSafetyServiceAfterLaunch()
        startHeartRateRetryLoop()
        checkConnection()
    }

    private fun observePhoneData() {
        viewModelScope.launch {
            communicationManager.safetyState.collect { safety ->
                val current = _state.value
                _state.value = current.copy(
                    safetyState = safety,
                    heartRate = safety.heartRate.takeIf { it > 0 } ?: current.heartRate
                ).withPhoneConnection(safety.isPhoneConnected)
            }
        }

        viewModelScope.launch {
            communicationManager.localHeartRate.collect { heartRate ->
                if (heartRate > 0) {
                    _state.value = _state.value.copy(heartRate = heartRate)
                }
            }
        }

        viewModelScope.launch {
            communicationManager.emergencyContacts.collect { contacts ->
                _state.value = _state.value.copy(emergencyContacts = contacts)
            }
        }

        viewModelScope.launch {
            communicationManager.phoneConnected.collect { connected ->
                _state.value = _state.value.withPhoneConnection(connected)
            }
        }
    }

    private fun WearHomeState.withPhoneConnection(connected: Boolean): WearHomeState {
        if (!connected) return copy(isPhoneConnected = false)

        val hadStalePhoneFailure = lastActionMessage.contains("phone not responding", ignoreCase = true)
        return copy(
            isPhoneConnected = true,
            actionInProgress = if (hadStalePhoneFailure) false else actionInProgress,
            lastActionMessage = if (hadStalePhoneFailure) "Phone connected" else lastActionMessage,
            lastActionSuccessful = if (hadStalePhoneFailure) true else lastActionSuccessful
        )
    }

    private fun observePreferences() {
        viewModelScope.launch {
            preferences.serviceEnabledFlow.collect { enabled ->
                if (enabled && WearSafetyService.getInstance() == null) {
                    WearSafetyService.start(getApplication())
                }
                _state.value = _state.value.copy(isServiceRunning = enabled)
            }
        }

        viewModelScope.launch {
            var firstEmission = true
            preferences.shakeSOSEnabledFlow.collect { enabled ->
                if (enabled && !firstEmission) {
                    ensureSafetyServiceActive()
                }
                firstEmission = false
            }
        }

        viewModelScope.launch {
            preferences.heartRateMonitoringFlow.collect { enabled ->
                heartRateMonitoringEnabled = enabled
                _state.value = _state.value.copy(heartRateMonitoringEnabled = enabled)
                if (enabled) {
                    startHeartRateForUi()
                } else {
                    stopHeartRateForUi()
                    _state.value = _state.value.copy(heartRate = 0)
                }
            }
        }

        viewModelScope.launch {
            preferences.backgroundVoiceSOSEnabledFlow.collect { enabled ->
                _state.value = _state.value.copy(backgroundVoiceSosEnabled = enabled)
                if (enabled) {
                    ensureSafetyServiceActive()
                }
            }
        }
    }

    private fun checkConnection() {
        viewModelScope.launch {
            communicationManager.checkPhoneConnection()
            communicationManager.loadCachedPhoneData()
            communicationManager.requestStatusUpdate()
        }
    }

    /**
     * Toggle the watch safety monitoring service.
     */
    fun toggleService() {
        viewModelScope.launch {
            val newState = !_state.value.isServiceRunning
            val started = if (newState) {
                ensureSafetyServiceActive()
            } else {
                WearSafetyService.stop(getApplication())
                true
            }

            preferences.setServiceEnabled(newState && started)
            setActionResult(
                success = started,
                message = when {
                    newState && started -> "Watch monitoring on"
                    newState -> "Monitoring failed: check permissions"
                    else -> "Watch monitoring off"
                }
            )
        }
    }

    private fun ensureSafetyServiceAfterLaunch() {
        viewModelScope.launch {
            val monitoringEnabled = preferences.serviceEnabledFlow.first()
            val shakeEnabled = preferences.shakeSOSEnabledFlow.first()
            val backgroundVoiceEnabled = preferences.backgroundVoiceSOSEnabledFlow.first()
            if (monitoringEnabled || shakeEnabled || backgroundVoiceEnabled) {
                ensureSafetyServiceActive()
            }
        }
    }

    private suspend fun ensureSafetyServiceActive(): Boolean {
        val started = WearSafetyService.getInstance() != null ||
                WearSafetyService.start(getApplication())
        if (started) {
            preferences.setServiceEnabled(true)
            _state.value = _state.value.copy(isServiceRunning = true)
        }
        return started
    }

    /**
     * Trigger manual SOS with countdown.
     */
    fun triggerSOS() {
        if (_state.value.isSosActive) return

        viewModelScope.launch {
            val countdownSeconds = preferences.sosCountdownSecondsFlow.first()
            _state.value = _state.value.copy(isSosActive = true, sosCountdown = countdownSeconds)

            // Countdown
            for (i in countdownSeconds downTo 1) {
                _state.value = _state.value.copy(sosCountdown = i)
                delay(1000)

                // Check if cancelled
                if (!_state.value.isSosActive) return@launch
            }

            // Execute SOS
            _state.value = _state.value.copy(sosCountdown = 0)
            val sent = communicationManager.triggerSOS()
            setActionResult(
                success = sent,
                message = if (sent) "SOS sent to phone" else "Phone not responding"
            )

            // Reset after a delay
            delay(3000)
            _state.value = _state.value.copy(isSosActive = false, sosCountdown = -1)
        }
    }

    /**
     * Cancel an active SOS countdown.
     */
    fun cancelSOS() {
        viewModelScope.launch {
            _state.value = _state.value.copy(isSosActive = false, sosCountdown = -1)
            val sent = communicationManager.cancelSOS()
            setActionResult(
                success = sent,
                message = if (sent) "SOS cancel sent" else "Cancel failed: open phone app"
            )
        }
    }

    /**
     * Send silent alert (SMS only).
     */
    fun triggerSilentAlert() {
        launchPhoneAction(
            pendingMessage = "Sending silent alert...",
            successMessage = "Silent alert sent",
            failureMessage = "Silent alert failed"
        ) { communicationManager.triggerSilentAlert() }
    }

    /**
     * Trigger fake call via phone.
     */
    fun triggerFakeCall() {
        launchPhoneAction(
            pendingMessage = "Requesting fake call...",
            successMessage = "Fake call requested",
            failureMessage = "Fake call failed"
        ) { communicationManager.triggerFakeCall() }
    }

    fun scheduleFakeCall(delayMinutes: Int = 5) {
        launchPhoneAction(
            pendingMessage = "Scheduling fake call...",
            successMessage = "Fake call in $delayMinutes min",
            failureMessage = "Schedule failed"
        ) { communicationManager.scheduleFakeCall(delayMinutes) }
    }

    /**
     * Share current location via phone.
     */
    fun shareLocation() {
        launchPhoneAction(
            pendingMessage = "Requesting location share...",
            successMessage = "Location share opened on phone",
            failureMessage = "Share location failed"
        ) { communicationManager.shareLocation() }
    }

    fun requestContactCall(contact: WearEmergencyContact) {
        launchPhoneAction(
            pendingMessage = "Requesting call...",
            successMessage = "Call prompt sent to phone",
            failureMessage = "Call request failed"
        ) { communicationManager.requestContactCall(contact) }
    }

    fun toggleOfflineMode() {
        launchPhoneAction(
            pendingMessage = "Toggling offline mode...",
            successMessage = "Offline mode updated",
            failureMessage = "Offline mode failed"
        ) { communicationManager.toggleOfflineMode() }
    }

    fun startSafetyCheckIn(minutes: Int = 15) {
        launchPhoneAction(
            pendingMessage = "Starting check-in...",
            successMessage = "Check-in started",
            failureMessage = "Check-in failed"
        ) { communicationManager.startSafetyCheckIn(minutes) }
    }

    fun cancelSafetyCheckIn() {
        launchPhoneAction(
            pendingMessage = "Cancelling check-in...",
            successMessage = "Check-in cancelled",
            failureMessage = "Cancel check-in failed"
        ) { communicationManager.cancelSafetyCheckIn() }
    }

    fun runEmergencyDrill() {
        launchPhoneAction(
            pendingMessage = "Starting drill...",
            successMessage = "Emergency drill requested",
            failureMessage = "Drill request failed"
        ) { communicationManager.runEmergencyDrill() }
    }

    fun shareEmergencyTimeline() {
        launchPhoneAction(
            pendingMessage = "Preparing timeline...",
            successMessage = "Timeline opened on phone",
            failureMessage = "Timeline failed"
        ) { communicationManager.shareEmergencyTimeline() }
    }

    fun startTrustedJourney() {
        launchPhoneAction(
            pendingMessage = "Starting journey...",
            successMessage = "Trusted journey started",
            failureMessage = "Journey start failed"
        ) { communicationManager.startTrustedJourney() }
    }

    fun completeTrustedJourney() {
        launchPhoneAction(
            pendingMessage = "Completing journey...",
            successMessage = "Trusted journey completed",
            failureMessage = "Journey complete failed"
        ) { communicationManager.completeTrustedJourney() }
    }

    fun toggleBackgroundVoiceSOS() {
        viewModelScope.launch {
            refreshVoiceSosStatus()
            val newState = !_state.value.backgroundVoiceSosEnabled

            if (newState && !hasRecordAudioPermission()) {
                setActionResult(false, "Allow microphone on watch")
                return@launch
            }

            if (newState && !SpeechRecognizer.isRecognitionAvailable(getApplication())) {
                setActionResult(false, "Voice recognition unavailable")
                return@launch
            }

            preferences.setBackgroundVoiceSOSEnabled(newState)
            if (newState) {
                ensureSafetyServiceActive()
            }
            _state.value = _state.value.copy(backgroundVoiceSosEnabled = newState)
            setActionResult(
                success = true,
                message = if (newState) "Background Voice SOS on" else "Background Voice SOS off"
            )
        }
    }

    fun startWatchVoiceSOS() {
        refreshVoiceSosStatus()
        if (_state.value.voiceSosListening) return

        if (_state.value.backgroundVoiceSosEnabled) {
            viewModelScope.launch {
                preferences.setBackgroundVoiceSOSEnabled(false)
                delay(350)
                startWatchVoiceSOS()
            }
            return
        }

        if (!_state.value.voiceSosPermissionGranted) {
            setActionResult(false, "Allow microphone on watch")
            return
        }

        if (!_state.value.voiceSosAvailable) {
            setActionResult(false, "Voice recognition unavailable")
            return
        }

        destroyManualSpeechRecognizer()
        val recognizer = SpeechRecognizer
            .createSpeechRecognizer(getApplication())
            .also { speechRecognizer = it }

        recognizer.setRecognitionListener(createVoiceRecognitionListener())
        _state.value = _state.value.copy(
            actionInProgress = true,
            lastActionMessage = "Listening: say Help or Emergency",
            lastActionSuccessful = true,
            voiceSosListening = true,
            voiceSosTranscript = ""
        )

        try {
            recognizer.startListening(createVoiceRecognitionIntent())
            Log.d(TAG, "Manual Watch Voice SOS listening started")
        } catch (e: Exception) {
            _state.value = _state.value.copy(voiceSosListening = false)
            destroyManualSpeechRecognizer()
            Log.e(TAG, "Manual Watch Voice SOS failed to start", e)
            setActionResult(false, "Voice start failed")
        }
    }

    fun stopWatchVoiceSOS() {
        destroyManualSpeechRecognizer()
        _state.value = _state.value.copy(
            actionInProgress = false,
            voiceSosListening = false,
            lastActionMessage = "Voice SOS stopped",
            lastActionSuccessful = true
        )
    }

    fun onVoicePermissionDenied() {
        refreshVoiceSosStatus()
        setActionResult(false, "Allow microphone on watch")
    }

    /**
     * Refresh phone connection and data.
     */
    fun refresh() {
        viewModelScope.launch {
            _state.value = _state.value.copy(
                actionInProgress = true,
                lastActionMessage = "Refreshing phone data...",
                lastActionSuccessful = true
            )
            val connected = communicationManager.checkPhoneConnection()
            val requested = connected && communicationManager.requestStatusUpdate()
            setActionResult(
                success = connected,
                message = when {
                    requested -> "Phone connected"
                    connected -> "Phone connected, sync pending"
                    else -> "Phone not responding"
                }
            )
        }
    }

    private fun launchPhoneAction(
        pendingMessage: String,
        successMessage: String,
        failureMessage: String,
        action: suspend () -> Boolean
    ) {
        viewModelScope.launch {
            _state.value = _state.value.copy(
                actionInProgress = true,
                lastActionMessage = pendingMessage,
                lastActionSuccessful = true
            )

            val sent = action()
            if (!sent) {
                communicationManager.checkPhoneConnection()
            }
            setActionResult(
                success = sent,
                message = if (sent) successMessage else "$failureMessage: phone not responding"
            )
            if (sent) {
                communicationManager.requestStatusUpdate()
            }
        }
    }

    private fun setActionResult(success: Boolean, message: String) {
        _state.value = _state.value.copy(
            actionInProgress = false,
            lastActionMessage = message,
            lastActionSuccessful = success,
            isPhoneConnected = if (success) true else _state.value.isPhoneConnected
        )
    }

    private fun createVoiceRecognitionListener(): RecognitionListener {
        return object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {
                Log.d(TAG, "Ready for watch voice speech")
                _state.value = _state.value.copy(
                    voiceSosListening = true,
                    lastActionMessage = "Listening: say Help or Emergency"
                )
            }

            override fun onBeginningOfSpeech() {
                Log.d(TAG, "Watch voice detected speech")
                _state.value = _state.value.copy(lastActionMessage = "Voice detected")
            }

            override fun onRmsChanged(rmsdB: Float) = Unit
            override fun onBufferReceived(buffer: ByteArray?) = Unit
            override fun onEndOfSpeech() {
                Log.d(TAG, "Watch voice speech ended")
                _state.value = _state.value.copy(lastActionMessage = "Checking voice...")
            }

            override fun onError(error: Int) {
                Log.w(TAG, "Watch voice recognizer error: ${voiceRecognitionErrorMessage(error)}")
                destroyManualSpeechRecognizer()
                _state.value = _state.value.copy(voiceSosListening = false)
                setActionResult(false, voiceRecognitionErrorMessage(error))
            }

            override fun onResults(results: Bundle?) {
                val matches = results
                    ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    .orEmpty()
                Log.d(TAG, "Watch voice results: $matches")
                handleVoiceMatches(matches, finalResult = true)
            }

            override fun onPartialResults(partialResults: Bundle?) {
                val matches = partialResults
                    ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    .orEmpty()
                if (matches.isNotEmpty()) {
                    Log.d(TAG, "Watch voice partial results: $matches")
                }
                handleVoiceMatches(matches, finalResult = false)
            }

            override fun onEvent(eventType: Int, params: Bundle?) = Unit
        }
    }

    private fun handleVoiceMatches(matches: List<String>, finalResult: Boolean) {
        val transcript = matches.firstOrNull().orEmpty()
        if (transcript.isNotBlank()) {
            _state.value = _state.value.copy(voiceSosTranscript = transcript)
        }

        val match = WearVoiceKeywordMatcher.findBestMatch(
            matches,
            minConfidence = if (finalResult) 0.62f else 0.78f
        )

        if (match.matched) {
            triggerVoiceSOS(match)
        } else if (finalResult) {
            _state.value = _state.value.copy(voiceSosListening = false)
            setActionResult(false, "No SOS phrase heard")
        }
    }

    private fun triggerVoiceSOS(match: WearVoiceKeywordMatch) {
        val now = System.currentTimeMillis()
        if (now - lastVoiceSosSentAtMillis < 5_000L) return
        lastVoiceSosSentAtMillis = now

        speechRecognizer?.stopListening()
        destroyManualSpeechRecognizer()
        _state.value = _state.value.copy(
            voiceSosListening = false,
            voiceSosTranscript = match.sourceText,
            lastActionMessage = "Voice SOS detected: ${match.keyword}"
        )

        viewModelScope.launch {
            val sent = communicationManager.triggerSOS("VOICE_SOS", match.confidence.coerceAtLeast(0.9f))
            setActionResult(
                success = sent,
                message = if (sent) {
                    "Voice SOS sent: ${match.keyword}"
                } else {
                    "Voice SOS failed: open phone app"
                }
            )
        }
    }

    private fun createVoiceRecognitionIntent(): Intent {
        return Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, getApplication<Application>().packageName)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault())
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, Locale.getDefault())
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 5)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, false)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 1500L)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 900L)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, 800L)
        }
    }

    private fun destroyManualSpeechRecognizer() {
        runCatching { speechRecognizer?.cancel() }
        runCatching { speechRecognizer?.destroy() }
        speechRecognizer = null
    }

    private fun refreshVoiceSosStatus() {
        _state.value = _state.value.copy(
            voiceSosAvailable = SpeechRecognizer.isRecognitionAvailable(getApplication()),
            voiceSosPermissionGranted = hasRecordAudioPermission()
        )
    }

    private fun refreshHeartRateSensorStatus() {
        _state.value = _state.value.copy(
            heartRateSensorAvailable = heartRateSensor != null,
            heartRatePermissionGranted = hasBodySensorPermission(),
            heartRateMonitoringEnabled = heartRateMonitoringEnabled
        )
    }

    private fun startHeartRateRetryLoop() {
        viewModelScope.launch {
            while (true) {
                if (heartRateMonitoringEnabled && !heartRateListenerRegistered) {
                    startHeartRateForUi()
                } else {
                    refreshHeartRateSensorStatus()
                }
                delay(3000)
            }
        }
    }

    private fun startHeartRateForUi() {
        refreshHeartRateSensorStatus()
        if (!heartRateMonitoringEnabled || heartRateListenerRegistered) return
        if (heartRateSensor == null || !hasBodySensorPermission()) return

        heartRateListenerRegistered = sensorManager.registerListener(
            this,
            heartRateSensor,
            SensorManager.SENSOR_DELAY_NORMAL
        )
    }

    private fun stopHeartRateForUi() {
        if (heartRateListenerRegistered) {
            sensorManager.unregisterListener(this, heartRateSensor)
            heartRateListenerRegistered = false
        }
    }

    private fun hasBodySensorPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            getApplication(),
            Manifest.permission.BODY_SENSORS
        ) == PackageManager.PERMISSION_GRANTED
    }

    private fun hasRecordAudioPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            getApplication(),
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
    }

    private fun voiceRecognitionErrorMessage(error: Int): String {
        return when (error) {
            SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Allow microphone on watch"
            SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "Voice recognizer busy"
            SpeechRecognizer.ERROR_NO_MATCH,
            SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "No SOS phrase heard"
            SpeechRecognizer.ERROR_NETWORK,
            SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "Voice network error"
            SpeechRecognizer.ERROR_AUDIO -> "Mic error"
            else -> "Voice recognition failed"
        }
    }

    override fun onSensorChanged(event: SensorEvent?) {
        if (event?.sensor?.type != Sensor.TYPE_HEART_RATE) return

        val heartRate = event.values.firstOrNull()?.roundToInt() ?: return
        if (heartRate !in 25..240) return

        communicationManager.updateLocalHeartRate(heartRate)
        _state.value = _state.value.copy(
            heartRate = heartRate,
            heartRatePermissionGranted = true,
            heartRateSensorAvailable = true
        )

        val now = System.currentTimeMillis()
        if (now - lastHeartRateSentAtMillis >= 10_000L) {
            lastHeartRateSentAtMillis = now
            viewModelScope.launch {
                communicationManager.sendHeartRate(heartRate)
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    override fun onCleared() {
        stopHeartRateForUi()
        destroyManualSpeechRecognizer()
        super.onCleared()
    }
}
