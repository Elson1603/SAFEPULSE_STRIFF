package com.safepulse.service

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
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import com.google.android.gms.maps.model.LatLng
import com.safepulse.SafePulseApplication
import com.safepulse.data.db.entity.EventLogEntity
import com.safepulse.data.prefs.UserPreferences
import com.safepulse.data.repository.EmergencyContactRepository
import com.safepulse.data.repository.EmergencyServiceRepository
import com.safepulse.data.repository.EventLogRepository
import com.safepulse.data.repository.HotspotRepository
import com.safepulse.data.repository.RiskZoneRepository
import com.safepulse.data.repository.UnsafeZoneRepository
import com.safepulse.domain.engine.SafetyEngine
import com.safepulse.domain.model.*
import com.safepulse.ml.StubVoiceTriggerModule
import com.safepulse.ml.VoiceTriggerFactory
import com.safepulse.ml.VoiceTriggerModule
import com.safepulse.utils.NotificationHelper
import com.safepulse.utils.SafetyConstants
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first

/**
 * Foreground service for continuous safety monitoring.
 * Runs sensors, location tracking, and safety engine in background.
 */
class SafetyForegroundService : LifecycleService(), SensorEventListener {
    
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    
    // Components
    private lateinit var safetyEngine: SafetyEngine
    private lateinit var locationTracker: LocationTracker
    private lateinit var emergencyManager: EmergencyManager
    private lateinit var safetyFeatureManager: SafetyFeatureManager
    private lateinit var voiceTriggerModule: VoiceTriggerModule
    private lateinit var shakeDetector: ShakeDetector
    private lateinit var confirmationService: EmergencyConfirmationService
    private lateinit var journeyTrackingService: JourneyTrackingService
    private lateinit var journeyShareManager: JourneyShareManager
    
    // Repositories
    private lateinit var hotspotRepository: HotspotRepository
    private lateinit var unsafeZoneRepository: UnsafeZoneRepository
    private lateinit var contactRepository: EmergencyContactRepository
    private lateinit var eventLogRepository: EventLogRepository
    private lateinit var emergencyServiceRepository: EmergencyServiceRepository
    private lateinit var riskZoneRepository: RiskZoneRepository
    private lateinit var userPreferences: UserPreferences
    
    // Sensors
    private lateinit var sensorManager: SensorManager
    private var accelerometer: Sensor? = null
    private var gyroscope: Sensor? = null
    
    // State
    private var wakeLock: PowerManager.WakeLock? = null
    private var isEmergencyCountdownActive = false
    private var countdownJob: Job? = null
    private var lastRiskLevel: RiskLevel = RiskLevel.LOW
    
    override fun onCreate() {
        super.onCreate()
        
        initializeComponents()
        initializeSensors()
        startSafetyForeground()
        
        // Store instance for external access
        instance = this
    }
    
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        when (intent?.action) {
            SafetyConstants.ACTION_STOP_SERVICE -> {
                stopSelf()
                return START_NOT_STICKY
            }
            SafetyConstants.ACTION_TRIGGER_SOS -> {
                handleVoiceEmergency()
            }
        }
        
        startMonitoring()
        
        return START_STICKY
    }
    
    override fun onBind(intent: Intent): IBinder? {
        super.onBind(intent)
        return null
    }
    
    override fun onDestroy() {
        super.onDestroy()
        stopMonitoring()
        confirmationService.cleanup()
        serviceScope.cancel()
        releaseWakeLock()
        
        // Clear instance
        instance = null
    }
    
    private fun initializeComponents() {
        // Initialize repositories
        val safePulseApplication = application as SafePulseApplication
        val database = safePulseApplication.database
        hotspotRepository = HotspotRepository(database.hotspotDao())
        unsafeZoneRepository = UnsafeZoneRepository(database.unsafeZoneDao())
        contactRepository = EmergencyContactRepository(database.emergencyContactDao())
        eventLogRepository = EventLogRepository(database.eventLogDao())
        emergencyServiceRepository = EmergencyServiceRepository(database.emergencyServiceDao())
        riskZoneRepository = RiskZoneRepository(applicationContext)
        userPreferences = UserPreferences(this@SafetyForegroundService)
        
        // Initialize managers
        locationTracker = LocationTracker(this)
        emergencyManager = EmergencyManager(this)
        safetyFeatureManager = SafetyFeatureManager.getInstance(applicationContext)
        journeyShareManager = safePulseApplication.journeyShareManager
        safetyFeatureManager.setLocationProvider {
            locationTracker.currentLocation.value?.let { LatLng(it.latitude, it.longitude) }
        }
        journeyShareManager.setLocationProvider {
            locationTracker.currentLocation.value
        }
        
        // Create and link nearby services manager
        val nearbyServicesManager = NearbyServicesManager(emergencyServiceRepository)
        emergencyManager.setNearbyServicesManager(nearbyServicesManager)
        
        // Initialize safety engine
        safetyEngine = SafetyEngine()
        
        // Initialize voice trigger module
        voiceTriggerModule = VoiceTriggerFactory.create(this@SafetyForegroundService)
        Log.d("SafetyService", "Voice trigger module initialized: ${voiceTriggerModule.javaClass.simpleName}")
        
        // Initialize shake detector
        shakeDetector = ShakeDetector(serviceScope)
        Log.d("SafetyService", "Shake detector initialized")
        
        // Initialize emergency confirmation service
        confirmationService = EmergencyConfirmationService(this@SafetyForegroundService, serviceScope)
        confirmationService.initialize {
            Log.d("SafetyService", "Emergency confirmation service initialized")
        }

        journeyTrackingService = JourneyTrackingService(
            context = applicationContext,
            locationTracker = locationTracker,
            journeySessionManager = safePulseApplication.journeySessionManager,
            journeyShareManager = journeyShareManager,
            unsafeZoneRepository = unsafeZoneRepository,
            riskZoneRepository = riskZoneRepository,
            transitionRiskEngine = safePulseApplication.transitionRiskEngine,
            scope = serviceScope
        )
    }
    
    private fun initializeSensors() {
        sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        gyroscope = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
    }
    
    private fun startSafetyForeground(includeMicrophone: Boolean = false): Boolean {
        if (includeMicrophone && !hasRecordAudioPermission()) {
            Log.e("SafetyService", "Cannot start voice trigger: RECORD_AUDIO permission is missing")
            return false
        }

        val notification = NotificationHelper.createForegroundNotification(this)
        return try {
            ServiceCompat.startForeground(
                this,
                SafetyConstants.NOTIFICATION_ID_FOREGROUND,
                notification,
                buildForegroundServiceType(includeMicrophone)
            )
            acquireWakeLock()
            true
        } catch (e: Exception) {
            Log.e("SafetyService", "Failed to start foreground service with requested type", e)
            false
        }
    }

    private fun buildForegroundServiceType(includeMicrophone: Boolean): Int {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return 0

        var serviceType = 0
        if (hasLocationPermission()) {
            serviceType = serviceType or ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R &&
            includeMicrophone &&
            hasRecordAudioPermission()
        ) {
            serviceType = serviceType or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
        }
        return serviceType
    }
    
    private fun startMonitoring() {
        // Load zone data
        serviceScope.launch {
            loadZoneData()
            loadUserProfile()
        }
        
        // Start location tracking
        locationTracker.startTracking(SafetyMode.NORMAL)
        journeyTrackingService.start()
        
        // Register sensor listeners
        val sensorDelay = SafetyConstants.SENSOR_DELAY_NORMAL_US
        accelerometer?.let {
            sensorManager.registerListener(this, it, sensorDelay)
        }
        gyroscope?.let {
            sensorManager.registerListener(this, it, sensorDelay)
        }
        
        // Start voice trigger if enabled
        serviceScope.launch {
            userPreferences.voiceTriggerEnabledFlow.collect { enabled ->
                Log.d("SafetyService", "Voice trigger enabled setting changed: $enabled")
                Log.d("SafetyService", "Voice trigger module type: ${voiceTriggerModule::class.simpleName}")
                if (enabled) {
                    Log.i("SafetyService", "Starting voice trigger listening...")
                    if (startSafetyForeground(includeMicrophone = true)) {
                        voiceTriggerModule.startListening()
                        Log.i("SafetyService", "Voice trigger listening started, isListening=${voiceTriggerModule.isListening()}")
                    } else {
                        voiceTriggerModule.stopListening()
                        Log.e("SafetyService", "Voice trigger not started because microphone foreground mode failed")
                    }
                } else {
                    Log.i("SafetyService", "Stopping voice trigger...")
                    voiceTriggerModule.stopListening()
                    startSafetyForeground(includeMicrophone = false)
                }
            }
        }
        
        // Observe location updates
        serviceScope.launch {
            locationTracker.currentLocation.collect { location ->
                location?.let {
                    safetyEngine.processLocation(it)
                    journeyTrackingService.onLocationUpdated(it)
                }
            }
        }
        
        // Observe safety state changes
        serviceScope.launch {
            safetyEngine.safetyState.collect { state ->
                handleSafetyStateChange(state)
                journeyTrackingService.onSafetyStateChanged(state)
            }
        }
        
        // Observe emergency events
        serviceScope.launch {
            safetyEngine.emergencyEvent.collect { event ->
                event?.let { handleEmergencyEvent(it) }
            }
        }
        
        // Observe voice trigger
        serviceScope.launch {
            voiceTriggerModule.keywordDetectedFlow().collect { result ->
                Log.d("SafetyService", "Voice trigger result received: detected=${result.detected}, keyword=${result.keyword}, confidence=${result.confidence}")
                if (result.detected) {
                    Log.i("SafetyService", "🔥 VOICE TRIGGER DETECTED! Activating emergency response...")
                    handleVoiceEmergency()
                }
            }
        }
        
        // Observe triple shake gesture
        serviceScope.launch {
            shakeDetector.tripleShakeDetected.collect {
                Log.i("SafetyService", "🚨 TRIPLE SHAKE DETECTED!")
                handleShakeEmergency()
            }
        }
    }
    
    private fun stopMonitoring() {
        locationTracker.stopTracking()
        sensorManager.unregisterListener(this)
        voiceTriggerModule.stopListening()
        countdownJob?.cancel()
        journeyTrackingService.stop()
    }
    
    private suspend fun loadZoneData() {
        val hotspots = hotspotRepository.getAllHotspotsList()
        val unsafeZones = unsafeZoneRepository.getAllUnsafeZonesList()
        safetyEngine.updateZoneData(hotspots, unsafeZones)
    }
    
    private suspend fun loadUserProfile() {
        val settings = userPreferences.userSettingsFlow.first()
        safetyEngine.updateUserProfile(
            UserProfile(settings.gender, settings.voiceTriggerEnabled)
        )
    }
    
    private fun handleSafetyStateChange(state: SafetyState) {
        // Update location tracking mode
        if (state.mode == SafetyMode.HEIGHTENED) {
            locationTracker.updateMode(SafetyMode.HEIGHTENED)
            updateSensorDelay(SafetyConstants.SENSOR_DELAY_HEIGHTENED_US)
        } else {
            locationTracker.updateMode(SafetyMode.NORMAL)
            updateSensorDelay(SafetyConstants.SENSOR_DELAY_NORMAL_US)
        }
        
        // Show high risk notification
        if (state.riskLevel == RiskLevel.HIGH && lastRiskLevel != RiskLevel.HIGH) {
            NotificationHelper.showRiskAlertNotification(this, state.riskLevel)
        } else if (state.riskLevel != RiskLevel.HIGH && lastRiskLevel == RiskLevel.HIGH) {
            NotificationHelper.cancelRiskAlertNotification(this)
        }
        
        lastRiskLevel = state.riskLevel
    }
    
    private fun handleEmergencyEvent(event: EmergencyEvent) {
        if (isEmergencyCountdownActive) return
        
        if (event.requiresConfirmation) {
            startEmergencyCountdown(event)
        } else {
            executeEmergencyResponse(event)
        }
    }
    
    private fun startEmergencyCountdown(event: EmergencyEvent) {
        isEmergencyCountdownActive = true
        
        countdownJob = serviceScope.launch {
            var secondsRemaining = SafetyConstants.CANCEL_WINDOW_SECONDS
            
            while (secondsRemaining > 0 && isActive) {
                NotificationHelper.showEmergencyCountdownNotification(
                    this@SafetyForegroundService,
                    event.type,
                    secondsRemaining
                )
                delay(1000)
                secondsRemaining--
            }
            
            if (isActive) {
                NotificationHelper.cancelEmergencyNotification(this@SafetyForegroundService)
                executeEmergencyResponse(event)
            }
            
            isEmergencyCountdownActive = false
        }
    }
    
    fun cancelEmergencyCountdown() {
        countdownJob?.cancel()
        isEmergencyCountdownActive = false
        NotificationHelper.cancelEmergencyNotification(this)
        safetyFeatureManager.stopLiveTracking("Emergency countdown cancelled")
        safetyEngine.clearEmergency()
    }
    
    /**
     * Trigger manual SOS from UI
     */
    fun triggerManualSOS() {
        safetyEngine.triggerManualSOS()
    }
    
    /**
     * Trigger silent SOS - sends SMS without call, for discreet emergencies
     */
    fun triggerSilentSOS() {
        safetyEngine.triggerSilentSOS()
    }
    
    private fun executeEmergencyResponse(event: EmergencyEvent) {
        serviceScope.launch {
            // Get emergency contacts
            val contacts = contactRepository.getAllContactsList()
            val primaryContact = contactRepository.getPrimaryContact()
                ?: contacts.firstOrNull()

            safetyFeatureManager.startLiveTracking(event.type.name, contacts)
            safetyFeatureManager.appendTimeline(
                title = if (event.silent) "Silent SOS triggered" else "SOS triggered",
                detail = "Confidence ${(event.confidence * 100).toInt()}%",
                location = event.location?.let { LatLng(it.latitude, it.longitude) },
                riskLevel = safetyEngine.safetyState.value.riskLevel,
                safetyMode = safetyEngine.safetyState.value.mode
            )
            
            Log.i("SafetyService", if (event.silent) "🔇 Silent Alert - SMS only" else "🚨 Full Alert - SMS + Call")
            
            // Send SMS to all emergency contacts
            val smsSent = emergencyManager.sendSOSMessages(contacts, event)
            
            // Send SMS to nearby emergency services (police/hospital)
            emergencyManager.sendAlertToNearbyServices(event)
            
            // Only initiate call if NOT silent mode
            var callStarted = false
            if (!event.silent) {
                Log.i("SafetyService", "📞 Initiating emergency call...")
                callStarted = emergencyManager.initiateEmergencyCall(primaryContact)
                Log.i("SafetyService", "📞 Emergency call result: $callStarted")
            } else {
                Log.i("SafetyService", "🔇 Skipping call (silent mode)")
            }
            
            // Log event
            eventLogRepository.logEvent(
                type = event.type.name,
                confidence = event.confidence,
                lat = event.location?.latitude ?: 0.0,
                lng = event.location?.longitude ?: 0.0,
                mode = safetyEngine.safetyState.value.mode.name,
                wasSOSSent = smsSent
            )
            journeyTrackingService.onEmergencyTriggered(
                if (event.silent) "Silent SOS triggered" else "SOS triggered"
            )

            safetyFeatureManager.appendTimeline(
                title = "Emergency response executed",
                detail = "SMS sent: $smsSent, call started: $callStarted",
                location = event.location?.let { LatLng(it.latitude, it.longitude) },
                riskLevel = safetyEngine.safetyState.value.riskLevel,
                safetyMode = safetyEngine.safetyState.value.mode
            )
            
            // Clear emergency state
            safetyEngine.clearEmergency()
        }
    }
    
    private fun updateSensorDelay(delayUs: Int) {
        sensorManager.unregisterListener(this)
        accelerometer?.let {
            sensorManager.registerListener(this, it, delayUs)
        }
        gyroscope?.let {
            sensorManager.registerListener(this, it, delayUs)
        }
    }
    
    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return

        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "SafePulse::SafetyMonitoringLock"
        )
        wakeLock?.acquire(10 * 60 * 1000L) // 10 minutes max
    }

    private fun hasLocationPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.ACCESS_COARSE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED
    }

    private fun hasRecordAudioPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
    }
    
    private fun releaseWakeLock() {
        wakeLock?.let {
            if (it.isHeld) {
                it.release()
            }
        }
        wakeLock = null
    }
    
    // Sensor data accumulator
    private var lastAccelData = floatArrayOf(0f, 0f, 0f)
    private var lastGyroData = floatArrayOf(0f, 0f, 0f)
    
    override fun onSensorChanged(event: SensorEvent) {
        when (event.sensor.type) {
            Sensor.TYPE_ACCELEROMETER -> {
                lastAccelData = event.values.clone()
                
                // Pass to shake detector for emergency gesture detection
                shakeDetector.onAccelerometerChanged(event)
            }
            Sensor.TYPE_GYROSCOPE -> {
                lastGyroData = event.values.clone()
                
                // Process combined sensor data
                val sensorData = SensorData(
                    accelerometerX = lastAccelData[0],
                    accelerometerY = lastAccelData[1],
                    accelerometerZ = lastAccelData[2],
                    gyroscopeX = lastGyroData[0],
                    gyroscopeY = lastGyroData[1],
                    gyroscopeZ = lastGyroData[2]
                )
                
                safetyEngine.processSensorData(sensorData, locationTracker.currentLocation.value)
            }
        }
    }
    
    /**
     * Handle shake gesture emergency trigger
     * Uses voice confirmation to prevent false positives
     */
    private fun handleShakeEmergency() {
        Log.i("SafetyService", "🚨 Triple shake detected - starting confirmation...")
        
        // Start voice confirmation
        // Note: Voice trigger is NOT paused because it's only active when manually triggered via button
        confirmationService.startConfirmation { confirmed ->
            if (confirmed) {
                Log.i("SafetyService", "✅ Emergency confirmed - proceeding")
                proceedWithShakeEmergency()
            } else {
                Log.i("SafetyService", "🚫 User said NO - cancelling this shake request")
                shakeDetector.reset()

                safetyFeatureManager.appendTimeline(
                    title = "Shake SOS cancelled",
                    detail = "User said no during voice confirmation. Monitoring remains active.",
                    location = locationTracker.currentLocation.value?.let {
                        LatLng(it.latitude, it.longitude)
                    },
                    riskLevel = safetyEngine.safetyState.value.riskLevel,
                    safetyMode = safetyEngine.safetyState.value.mode
                )
            }
        }
    }
    
    /**
     * Proceed with shake emergency after confirmation
     */
    private fun proceedWithShakeEmergency() {
        serviceScope.launch {
            try {
                // Get emergency contacts
                val contacts = contactRepository.getAllContactsList()
                
                if (contacts.isEmpty()) {
                    Log.w("SafetyService", "⚠️ No emergency contacts configured")
                    // Reset shake detection to allow retry
                    shakeDetector.reset()
                    return@launch
                }
                
                // Get current location
                val location = locationTracker.currentLocation.value
                
                // Create emergency event
                val event = EmergencyEvent(
                    timestamp = System.currentTimeMillis(),
                    type = EventType.MANUAL_SOS,
                    confidence = 1.0f, // User triggered
                    location = location
                )
                
                Log.i("SafetyService", "")
                Log.i("SafetyService", "🚨 Processing shake gesture emergency...")
                Log.i("SafetyService", "   Contacts: ${contacts.size}")
                Log.i("SafetyService", "   Location: ${if (location != null) "Available" else "Unavailable"}")
                Log.i("SafetyService", "")
                
                // Trigger emergency response with photo capture
                emergencyManager.triggerVolumeButtonEmergency(
                    contacts = contacts,
                    event = event,
                    lifecycleOwner = this@SafetyForegroundService
                )
                
                // Save event to log
                eventLogRepository.logEvent(
                    type = event.type.name,
                    confidence = event.confidence,
                    lat = location?.latitude ?: 0.0,
                    lng = location?.longitude ?: 0.0,
                    mode = safetyEngine.safetyState.value.mode.name,
                    wasSOSSent = true
                )
                journeyTrackingService.onEmergencyTriggered("Shake SOS triggered")
                
                Log.i("SafetyService", "✅ Shake gesture emergency response completed")
                
            } catch (e: Exception) {
                Log.e("SafetyService", "❌ Error handling shake emergency", e)
                // Reset shake detection to allow retry
                shakeDetector.reset()
            }
        }
    }
    
    /**
     * Handle voice trigger emergency
     */
    private fun handleVoiceEmergency() {
        serviceScope.launch {
            try {
                // Get emergency contacts
                val contacts = contactRepository.getAllContactsList()
                
                if (contacts.isEmpty()) {
                    Log.w("SafetyService", "⚠️ No emergency contacts configured")
                    return@launch
                }
                
                // Get current location
                val location = locationTracker.currentLocation.value
                
                // Create emergency event
                val event = EmergencyEvent(
                    timestamp = System.currentTimeMillis(),
                    type = EventType.MANUAL_SOS,
                    confidence = 1.0f, // User triggered
                    location = location
                )
                
                Log.i("SafetyService", "")
                Log.i("SafetyService", "🗣️ Processing voice emergency trigger...")
                Log.i("SafetyService", "   Contacts: ${contacts.size}")
                Log.i("SafetyService", "   Location: ${if (location != null) "Available" else "Unavailable"}")
                Log.i("SafetyService", "")
                
                // Trigger emergency response with photo capture
                emergencyManager.triggerVolumeButtonEmergency(
                    contacts = contacts,
                    event = event,
                    lifecycleOwner = this@SafetyForegroundService
                )
                
                // Save event to log
                eventLogRepository.logEvent(
                    type = event.type.name,
                    confidence = event.confidence,
                    lat = location?.latitude ?: 0.0,
                    lng = location?.longitude ?: 0.0,
                    mode = safetyEngine.safetyState.value.mode.name,
                    wasSOSSent = true
                )
                journeyTrackingService.onEmergencyTriggered("Voice emergency triggered")
                
                Log.i("SafetyService", "✅ Voice emergency response completed")
                
            } catch (e: Exception) {
                Log.e("SafetyService", "❌ Error handling voice emergency", e)
            }
        }
    }
    
    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
        // Not needed for this implementation
    }
    
    companion object {
        private var instance: SafetyForegroundService? = null
        
        fun getInstance(): SafetyForegroundService? = instance
        
        fun start(context: Context) {
            val intent = Intent(context, SafetyForegroundService::class.java)
            context.startForegroundService(intent)
        }
        
        fun stop(context: Context) {
            val intent = Intent(context, SafetyForegroundService::class.java).apply {
                action = SafetyConstants.ACTION_STOP_SERVICE
            }
            context.startService(intent)
        }
    }
}
