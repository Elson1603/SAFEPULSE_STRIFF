package com.safepulse.wear.service

import android.app.*
import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.VibrationEffect
import android.os.Vibrator
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.safepulse.wear.R
import com.safepulse.wear.WearSafePulseApp
import com.safepulse.wear.data.PhoneCommunicationManager
import com.safepulse.wear.data.WearPreferences
import com.safepulse.wear.data.WearVoiceKeywordMatch
import com.safepulse.wear.data.WearVoiceKeywordMatcher
import com.safepulse.wear.presentation.WearMainActivity
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import java.util.Locale

/**
 * Foreground service for continuous safety monitoring on the watch.
 * Handles:
 * - Shake detection for SOS trigger
 * - Heart rate monitoring
 * - Phone connectivity monitoring
 * - Periodic status sync
 */
class WearSafetyService : Service(), SensorEventListener {

    companion object {
        private const val TAG = "WearSafetyService"
        private const val NOTIFICATION_ID = 1001
        private const val SHAKE_STATUS_NOTIFICATION_ID = 1002
        private const val VOICE_STATUS_NOTIFICATION_ID = 1003
        private const val SHAKE_THRESHOLD = 18f // m/s² threshold for wrist shake
        private const val SHAKE_TIME_WINDOW = 2500L // ms for triple shake
        private const val SHAKE_MIN_GAP_MS = 250L
        private const val SHAKE_SOS_COOLDOWN_MS = 15_000L
        private const val VOICE_SOS_COOLDOWN_MS = 15_000L
        private const val VOICE_RESTART_DELAY_MS = 900L
        private const val SHAKE_COUNT_TRIGGER = 3
        private const val HEART_RATE_INTERVAL = 30_000L // 30 seconds
        private const val STATUS_SYNC_INTERVAL = 60_000L // 1 minute

        private var instance: WearSafetyService? = null

        fun getInstance(): WearSafetyService? = instance

        fun start(context: Context): Boolean {
            return try {
                val appContext = context.applicationContext
                val intent = Intent(appContext, WearSafetyService::class.java)
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                    appContext.startForegroundService(intent)
                } else {
                    appContext.startService(intent)
                }
                true
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start wear safety service", e)
                false
            }
        }

        fun stop(context: Context) {
            val intent = Intent(context, WearSafetyService::class.java)
            context.stopService(intent)
        }
    }

    private val serviceScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private lateinit var sensorManager: SensorManager
    private lateinit var preferences: WearPreferences
    private lateinit var communicationManager: PhoneCommunicationManager
    private var wakeLock: PowerManager.WakeLock? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private val voiceRestartRunnable = Runnable { startBackgroundVoiceListening() }

    // Shake detection
    private val shakeTimes = mutableListOf<Long>()
    private var lastShakeTime = 0L
    private var lastShakeSosTime = 0L

    // Heart rate
    private var heartRateSensor: Sensor? = null
    private var lastHeartRate = 0
    private var heartRateMonitoringEnabled = true

    // Background voice SOS
    private var speechRecognizer: SpeechRecognizer? = null
    private var backgroundVoiceSOSEnabled = false
    private var voiceListening = false
    private var lastVoiceSosTime = 0L

    override fun onCreate() {
        super.onCreate()
        instance = this

        sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        preferences = WearPreferences(this)
        communicationManager = (application as WearSafePulseApp).communicationManager

        acquireWakeLock()
        startMonitoringForeground()
        registerSensors()
        startPeriodicSync()
        observeBackgroundVoiceSOS()

        Log.d(TAG, "WearSafetyService created")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        instance = null
        backgroundVoiceSOSEnabled = false
        stopBackgroundVoiceListening(destroyRecognizer = true)
        serviceScope.cancel()
        unregisterSensors()
        releaseWakeLock()
        super.onDestroy()
        Log.d(TAG, "WearSafetyService destroyed")
    }

    // ─── Sensors ─────────────────────────────────────────

    private fun registerSensors() {
        // Accelerometer for shake detection
        sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)?.let { sensor ->
            sensorManager.registerListener(this, sensor, SensorManager.SENSOR_DELAY_UI)
        }

        // Heart rate sensor
        heartRateSensor = sensorManager.getDefaultSensor(Sensor.TYPE_HEART_RATE)
        if (heartRateSensor != null && hasBodySensorPermission()) {
            serviceScope.launch {
                heartRateMonitoringEnabled = preferences.heartRateMonitoringFlow.first()
                if (heartRateMonitoringEnabled) {
                    startHeartRateMonitoring()
                }
            }
        }
    }

    private fun unregisterSensors() {
        sensorManager.unregisterListener(this)
    }

    private fun startHeartRateMonitoring() {
        heartRateSensor?.let { sensor ->
            sensorManager.registerListener(this, sensor, SensorManager.SENSOR_DELAY_NORMAL)
        }
    }

    override fun onSensorChanged(event: SensorEvent?) {
        event ?: return

        when (event.sensor.type) {
            Sensor.TYPE_ACCELEROMETER -> handleAccelerometer(event)
            Sensor.TYPE_HEART_RATE -> handleHeartRate(event)
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    // ─── Shake Detection ─────────────────────────────────

    private fun handleAccelerometer(event: SensorEvent) {
        val x = event.values[0]
        val y = event.values[1]
        val z = event.values[2]
        val magnitude = kotlin.math.sqrt((x * x + y * y + z * z).toDouble()).toFloat()

        val now = System.currentTimeMillis()

        if (magnitude > SHAKE_THRESHOLD && (now - lastShakeTime) > SHAKE_MIN_GAP_MS) {
            lastShakeTime = now
            shakeTimes.add(now)

            // Remove old shakes outside the time window
            shakeTimes.removeAll { now - it > SHAKE_TIME_WINDOW }

            if (shakeTimes.size >= SHAKE_COUNT_TRIGGER) {
                shakeTimes.clear()
                onShakeDetected()
            }
        }
    }

    private fun onShakeDetected() {
        serviceScope.launch {
            val shakeEnabled = preferences.shakeSOSEnabledFlow.first()
            if (!shakeEnabled) return@launch

            val now = System.currentTimeMillis()
            if (now - lastShakeSosTime < SHAKE_SOS_COOLDOWN_MS) return@launch
            lastShakeSosTime = now

            Log.w(TAG, "Shake SOS detected!")

            // Vibrate to confirm
            vibrateAlert()

            // Trigger SOS on phone
            val sent = communicationManager.triggerSOS("SHAKE_SOS", 0.9f)
            showShakeSosNotification(sent)
        }
    }

    // ─── Heart Rate ──────────────────────────────────────

    private fun handleHeartRate(event: SensorEvent) {
        val hr = event.values[0].toInt()
        if (hr > 0 && hr != lastHeartRate) {
            lastHeartRate = hr
            communicationManager.updateLocalHeartRate(hr)

            // Send to phone periodically
            serviceScope.launch {
                communicationManager.sendHeartRate(hr)
            }

            // Check for abnormal heart rate (potential distress)
            if (hr > 150 || hr < 40) {
                Log.w(TAG, "Abnormal heart rate detected: $hr BPM")
                // Could trigger alert — for now just log
            }
        }
    }

    // ─── Background Voice SOS ────────────────────────────

    private fun observeBackgroundVoiceSOS() {
        serviceScope.launch {
            preferences.backgroundVoiceSOSEnabledFlow.collect { enabled ->
                backgroundVoiceSOSEnabled = enabled
                mainHandler.post {
                    if (enabled) {
                        startBackgroundVoiceListening()
                    } else {
                        stopBackgroundVoiceListening(destroyRecognizer = true)
                        startMonitoringForeground()
                    }
                }
            }
        }
    }

    private fun startBackgroundVoiceListening() {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            mainHandler.post { startBackgroundVoiceListening() }
            return
        }

        mainHandler.removeCallbacks(voiceRestartRunnable)
        if (!backgroundVoiceSOSEnabled || voiceListening) return
        if (!hasRecordAudioPermission()) {
            Log.w(TAG, "Background Voice SOS needs RECORD_AUDIO permission")
            return
        }
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            Log.w(TAG, "Speech recognition unavailable on this watch")
            return
        }

        if (!startMonitoringForeground()) {
            scheduleVoiceRestart(5_000L)
            return
        }

        val recognizer = createBackgroundSpeechRecognizer()

        recognizer.setRecognitionListener(createBackgroundVoiceListener())
        voiceListening = true
        try {
            recognizer.startListening(createBackgroundVoiceIntent())
            Log.d(TAG, "Background Voice SOS listening")
        } catch (e: Exception) {
            voiceListening = false
            destroyBackgroundSpeechRecognizer()
            Log.e(TAG, "Failed to start background voice recognition", e)
            scheduleVoiceRestart(2_500L)
        }
    }

    private fun stopBackgroundVoiceListening(destroyRecognizer: Boolean) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            mainHandler.post { stopBackgroundVoiceListening(destroyRecognizer) }
            return
        }

        mainHandler.removeCallbacks(voiceRestartRunnable)
        voiceListening = false
        if (destroyRecognizer) {
            destroyBackgroundSpeechRecognizer()
        } else {
            runCatching { speechRecognizer?.cancel() }
        }
    }

    private fun createBackgroundSpeechRecognizer(): SpeechRecognizer {
        destroyBackgroundSpeechRecognizer()
        return SpeechRecognizer
            .createSpeechRecognizer(applicationContext)
            .also { speechRecognizer = it }
    }

    private fun destroyBackgroundSpeechRecognizer(cancelFirst: Boolean = true) {
        if (cancelFirst) {
            runCatching { speechRecognizer?.cancel() }
        }
        runCatching { speechRecognizer?.destroy() }
        speechRecognizer = null
    }

    private fun scheduleVoiceRestart(delayMillis: Long = VOICE_RESTART_DELAY_MS) {
        if (!backgroundVoiceSOSEnabled || !hasRecordAudioPermission()) return

        mainHandler.removeCallbacks(voiceRestartRunnable)
        mainHandler.postDelayed(voiceRestartRunnable, delayMillis)
    }

    private fun createBackgroundVoiceListener(): RecognitionListener {
        return object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {
                voiceListening = true
            }

            override fun onBeginningOfSpeech() = Unit
            override fun onRmsChanged(rmsdB: Float) = Unit
            override fun onBufferReceived(buffer: ByteArray?) = Unit
            override fun onEndOfSpeech() = Unit

            override fun onError(error: Int) {
                voiceListening = false
                Log.d(TAG, "Background voice recognizer error: ${voiceErrorName(error)}")
                destroyBackgroundSpeechRecognizer(cancelFirst = false)

                if (error == SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS) {
                    serviceScope.launch {
                        preferences.setBackgroundVoiceSOSEnabled(false)
                    }
                    return
                }

                val delay = when (error) {
                    SpeechRecognizer.ERROR_RECOGNIZER_BUSY,
                    SpeechRecognizer.ERROR_CLIENT -> 2_000L
                    SpeechRecognizer.ERROR_NETWORK,
                    SpeechRecognizer.ERROR_NETWORK_TIMEOUT,
                    SpeechRecognizer.ERROR_SERVER -> 3_000L
                    else -> VOICE_RESTART_DELAY_MS
                }
                scheduleVoiceRestart(delay)
            }

            override fun onResults(results: Bundle?) {
                voiceListening = false
                val matches = results
                    ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    .orEmpty()
                handleBackgroundVoiceMatches(matches, finalResult = true)
            }

            override fun onPartialResults(partialResults: Bundle?) {
                val matches = partialResults
                    ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    .orEmpty()
                handleBackgroundVoiceMatches(matches, finalResult = false)
            }

            override fun onEvent(eventType: Int, params: Bundle?) = Unit
        }
    }

    private fun handleBackgroundVoiceMatches(matches: List<String>, finalResult: Boolean) {
        val match = WearVoiceKeywordMatcher.findBestMatch(
            matches,
            minConfidence = if (finalResult) 0.62f else 0.78f
        )

        if (match.matched) {
            triggerBackgroundVoiceSOS(match)
        } else if (finalResult) {
            destroyBackgroundSpeechRecognizer(cancelFirst = false)
            scheduleVoiceRestart()
        }
    }

    private fun triggerBackgroundVoiceSOS(match: WearVoiceKeywordMatch) {
        val now = System.currentTimeMillis()
        if (now - lastVoiceSosTime < VOICE_SOS_COOLDOWN_MS) return
        lastVoiceSosTime = now

        voiceListening = false
        destroyBackgroundSpeechRecognizer()
        vibrateAlert()

        Log.w(TAG, "Background Voice SOS detected: ${match.keyword}")
        serviceScope.launch {
            val sent = communicationManager.triggerSOS(
                eventType = "VOICE_SOS",
                confidence = match.confidence.coerceAtLeast(0.9f)
            )
            showVoiceSosNotification(sent, match.keyword.orEmpty())
        }

        scheduleVoiceRestart(5_000L)
    }

    private fun createBackgroundVoiceIntent(): Intent {
        return Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, packageName)
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

    // ─── Periodic Sync ───────────────────────────────────

    private fun startPeriodicSync() {
        serviceScope.launch {
            while (isActive) {
                try {
                    communicationManager.checkPhoneConnection()
                    communicationManager.requestStatusUpdate()
                } catch (e: Exception) {
                    Log.e(TAG, "Periodic sync failed", e)
                }
                delay(STATUS_SYNC_INTERVAL)
            }
        }
    }

    // ─── Utilities ───────────────────────────────────────

    @Suppress("DEPRECATION")
    private fun vibrateAlert() {
        val vibrator = getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        val pattern = longArrayOf(0, 200, 100, 200, 100, 400)
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            vibrator.vibrate(VibrationEffect.createWaveform(pattern, -1))
        } else {
            vibrator.vibrate(pattern, -1)
        }
    }

    private fun acquireWakeLock() {
        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "SafePulse::WearSafetyWakeLock"
        ).apply { acquire(10 * 60 * 1000L) } // 10 minutes max
    }

    private fun releaseWakeLock() {
        wakeLock?.let {
            if (it.isHeld) it.release()
        }
        wakeLock = null
    }

    private fun createNotification(): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, WearMainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        return NotificationCompat.Builder(this, WearSafePulseApp.CHANNEL_SAFETY)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle("SafePulse Active")
            .setContentText(
                if (backgroundVoiceSOSEnabled) {
                    "Safety monitoring and Voice SOS running"
                } else {
                    "Safety monitoring running"
                }
            )
            .setOngoing(true)
            .setContentIntent(pendingIntent)
            .build()
    }

    private fun startMonitoringForeground(): Boolean {
        return try {
            ServiceCompat.startForeground(
                this,
                NOTIFICATION_ID,
                createNotification(),
                buildForegroundServiceType()
            )
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to update wear foreground service type", e)
            if (backgroundVoiceSOSEnabled) {
                val voiceWasEnabled = backgroundVoiceSOSEnabled
                backgroundVoiceSOSEnabled = false
                runCatching {
                    ServiceCompat.startForeground(
                        this,
                        NOTIFICATION_ID,
                        createNotification(),
                        buildForegroundServiceType()
                    )
                }
                backgroundVoiceSOSEnabled = voiceWasEnabled
            }
            false
        }
    }

    private fun buildForegroundServiceType(): Int {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return 0

        var serviceType = ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R &&
            backgroundVoiceSOSEnabled &&
            hasRecordAudioPermission()
        ) {
            serviceType = serviceType or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE &&
            hasBodySensorPermission()
        ) {
            serviceType = serviceType or ServiceInfo.FOREGROUND_SERVICE_TYPE_HEALTH
        }
        return serviceType
    }

    private fun showShakeSosNotification(sent: Boolean) {
        if (!canPostNotifications()) return

        val pendingIntent = PendingIntent.getActivity(
            this, 1,
            Intent(this, WearMainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val notification = NotificationCompat.Builder(this, WearSafePulseApp.CHANNEL_SOS)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(if (sent) "Shake SOS sent" else "Shake SOS failed")
            .setContentText(
                if (sent) {
                    "Phone emergency action requested"
                } else {
                    "Open SafePulse and check phone connection"
                }
            )
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .build()

        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(SHAKE_STATUS_NOTIFICATION_ID, notification)
    }

    private fun showVoiceSosNotification(sent: Boolean, keyword: String) {
        if (!canPostNotifications()) return

        val pendingIntent = PendingIntent.getActivity(
            this, 2,
            Intent(this, WearMainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val notification = NotificationCompat.Builder(this, WearSafePulseApp.CHANNEL_SOS)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(if (sent) "Voice SOS sent" else "Voice SOS failed")
            .setContentText(
                if (sent) {
                    "Detected ${keyword.ifBlank { "emergency phrase" }}"
                } else {
                    "Open SafePulse and check phone connection"
                }
            )
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .build()

        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(VOICE_STATUS_NOTIFICATION_ID, notification)
    }

    private fun canPostNotifications(): Boolean {
        return android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.TIRAMISU ||
                ContextCompat.checkSelfPermission(
                    this,
                    Manifest.permission.POST_NOTIFICATIONS
                ) == PackageManager.PERMISSION_GRANTED
    }

    private fun hasBodySensorPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.BODY_SENSORS
        ) == PackageManager.PERMISSION_GRANTED
    }

    private fun hasRecordAudioPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
    }

    private fun voiceErrorName(error: Int): String {
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
