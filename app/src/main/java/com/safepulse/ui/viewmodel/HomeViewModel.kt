package com.safepulse.ui.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.google.android.gms.maps.model.LatLng
import com.safepulse.SafePulseApplication
import com.safepulse.data.db.entity.EmergencyContactEntity
import com.safepulse.data.db.entity.HotspotEntity
import com.safepulse.data.prefs.UserPreferences
import com.safepulse.data.repository.DisasterRepository
import com.safepulse.data.repository.EmergencyContactRepository
import com.safepulse.data.repository.EventLogRepository
import com.safepulse.data.repository.HotspotRepository
import com.safepulse.domain.model.RiskLevel
import com.safepulse.domain.model.SafetyMode
import com.safepulse.domain.journey.JourneySessionState
import com.safepulse.domain.journey.JourneySessionStatus
import com.safepulse.domain.journey.JourneyLiveState
import com.safepulse.domain.saferoutes.DisasterAlert
import com.safepulse.service.SafetyForegroundService
import com.safepulse.service.SafetyFeatureManager
import com.safepulse.service.JourneyShareManager
import com.safepulse.service.SafetyFeatureState
import com.safepulse.service.PhoneWatchSyncManager
import com.safepulse.service.WatchSyncUiState
import com.safepulse.worker.FakeCallWorker
import com.safepulse.worker.SafetyCheckWorker
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

data class HomeState(
    val isServiceRunning: Boolean = false,
    val riskLevel: RiskLevel = RiskLevel.LOW,
    val riskScore: Float = 0f,
    val safetyMode: SafetyMode = SafetyMode.NORMAL,
    val eventCount: Int = 0,
    val sosCount: Int = 0,
    val emergencyContacts: List<EmergencyContactEntity> = emptyList(),
    val isOnboardingComplete: Boolean = true,
    val voiceTriggerEnabled: Boolean = false,
    // Map state
    val currentLocation: LatLng? = null,
    val crimeHotspots: List<HotspotEntity> = emptyList(),
    val disasters: List<DisasterAlert> = emptyList(),
    val safetyFeatures: SafetyFeatureState = SafetyFeatureState(),
    val watchSync: WatchSyncUiState = WatchSyncUiState(),
    val journeyState: JourneySessionState = JourneySessionState(),
    val companionJourneyState: JourneyLiveState = JourneyLiveState()
)

class HomeViewModel(application: Application) : AndroidViewModel(application) {
    
    private val app = application as SafePulseApplication
    private val userPreferences = UserPreferences(application)
    private val contactRepository = EmergencyContactRepository(app.database.emergencyContactDao())
    private val eventLogRepository = EventLogRepository(app.database.eventLogDao())
    private val hotspotRepository = HotspotRepository(app.database.hotspotDao())
    private val disasterRepository = DisasterRepository()
    private val safetyFeatureManager = SafetyFeatureManager.getInstance(application)
    private val journeySessionManager = app.journeySessionManager
    private val journeyShareManager = app.journeyShareManager
    private val watchSyncManager = PhoneWatchSyncManager(application)
    
    private val _state = MutableStateFlow(HomeState())
    val state: StateFlow<HomeState> = _state.asStateFlow()
    
    init {
        loadInitialState()
        safetyFeatureManager.setLocationProvider { _state.value.currentLocation }
        journeyShareManager.setLocationProvider {
            _state.value.currentLocation?.let { location ->
                com.safepulse.domain.model.LocationData(
                    latitude = location.latitude,
                    longitude = location.longitude
                )
            }
        }
        observeData()
        refreshWatchConnection()
    }
    
    private fun loadInitialState() {
        viewModelScope.launch {
            val settings = userPreferences.userSettingsFlow.first()
            _state.value = _state.value.copy(
                isServiceRunning = settings.serviceEnabled,
                isOnboardingComplete = settings.onboardingComplete,
                voiceTriggerEnabled = settings.voiceTriggerEnabled
            )
        }
    }
    
    private fun observeData() {
        viewModelScope.launch {
            userPreferences.serviceEnabledFlow.collect { enabled ->
                _state.value = _state.value.copy(isServiceRunning = enabled)
            }
        }
        
        viewModelScope.launch {
            contactRepository.getAllContacts().collect { contacts ->
                _state.value = _state.value.copy(emergencyContacts = contacts)
            }
        }
        
        viewModelScope.launch {
            eventLogRepository.getAllEvents().collect { events ->
                _state.value = _state.value.copy(
                    eventCount = events.size,
                    sosCount = events.count { it.wasSOSSent }
                )
            }
        }
        
        // Load crime hotspots
        viewModelScope.launch {
            val hotspots = hotspotRepository.getAllHotspotsList()
            _state.value = _state.value.copy(crimeHotspots = hotspots)
        }
        
        // Load disaster alerts
        viewModelScope.launch {
            disasterRepository.getActiveDisasters().collect { disasters ->
                _state.value = _state.value.copy(disasters = disasters)
            }
        }

        viewModelScope.launch {
            safetyFeatureManager.state.collect { featureState ->
                _state.value = _state.value.copy(safetyFeatures = featureState)
            }
        }

        viewModelScope.launch {
            journeySessionManager.state.collect { journeyState ->
                _state.value = _state.value.copy(journeyState = journeyState)
            }
        }

        viewModelScope.launch {
            journeyShareManager.state.collect { companionState ->
                _state.value = _state.value.copy(companionJourneyState = companionState)
            }
        }
    }
    
    fun toggleService() {
        viewModelScope.launch {
            val newState = !_state.value.isServiceRunning
            
            if (newState) {
                SafetyForegroundService.start(app)
                SafetyCheckWorker.schedule(app)
            } else {
                SafetyForegroundService.stop(app)
                SafetyCheckWorker.cancel(app)
            }
            
            userPreferences.setServiceEnabled(newState)
        }
    }
    
    fun triggerManualSOS() {
        val service = SafetyForegroundService.getInstance()
        if (service != null) {
            service.triggerManualSOS()
        } else {
            // Service not running, start it first then trigger
            viewModelScope.launch {
                if (!_state.value.isServiceRunning) {
                    SafetyForegroundService.start(app)
                    userPreferences.setServiceEnabled(true)
                    // Give service time to initialize
                    kotlinx.coroutines.delay(1000)
                }
                SafetyForegroundService.getInstance()?.triggerManualSOS()
            }
        }
    }
    
    fun shareLocation() {
        viewModelScope.launch {
            val location = _state.value.currentLocation
            val message = if (location != null) {
                "📍 My current location: https://maps.google.com/?q=${location.latitude},${location.longitude}"
            } else {
                "📍 Sharing my location from SafePulse app"
            }
            
            // Share via Android sharing intent
            val shareIntent = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(android.content.Intent.EXTRA_TEXT, message)
                putExtra(android.content.Intent.EXTRA_SUBJECT, "My Location - SafePulse")
                addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            
            try {
                val chooser = android.content.Intent.createChooser(shareIntent, "Share Location")
                chooser.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                app.startActivity(chooser)
            } catch (e: Exception) {
                android.util.Log.e("HomeViewModel", "Error sharing location", e)
            }
        }
    }

    fun shareEmergencyTimeline() {
        viewModelScope.launch {
            val timelineText = safetyFeatureManager.exportTimelineText()
            val shareIntent = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(android.content.Intent.EXTRA_TEXT, timelineText)
                putExtra(android.content.Intent.EXTRA_SUBJECT, "SafePulse Emergency Timeline")
                addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            }

            try {
                val chooser = android.content.Intent.createChooser(shareIntent, "Share Emergency Timeline")
                chooser.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                app.startActivity(chooser)
            } catch (e: Exception) {
                android.util.Log.e("HomeViewModel", "Error sharing emergency timeline", e)
            }
        }
    }
    
    /**
     * Trigger a fake incoming call to help escape uncomfortable situations
     */
    fun triggerFakeCall() {
        viewModelScope.launch {
            try {
                FakeCallWorker.showFakeCall(app)
                android.util.Log.i("HomeViewModel", "Fake call triggered")
            } catch (e: Exception) {
                android.util.Log.e("HomeViewModel", "Error triggering fake call", e)
            }
        }
    }
    
    /**
     * Trigger silent alert - sends SOS without sound/vibration for discreet situations
     */
    fun triggerSilentAlert() {
        viewModelScope.launch {
            val service = SafetyForegroundService.getInstance()
            if (service != null) {
                android.util.Log.i("HomeViewModel", "🔇 Triggering silent alert...")
                service.triggerSilentSOS()
            } else {
                // Service not running, start it first
                if (!_state.value.isServiceRunning) {
                    SafetyForegroundService.start(app)
                    userPreferences.setServiceEnabled(true)
                    kotlinx.coroutines.delay(1000)
                }
                SafetyForegroundService.getInstance()?.triggerSilentSOS()
            }
        }
    }
    
    fun updateRiskLevel(level: RiskLevel, score: Float) {
        _state.value = _state.value.copy(riskLevel = level, riskScore = score)
    }
    
    fun updateSafetyMode(mode: SafetyMode) {
        _state.value = _state.value.copy(safetyMode = mode)
    }
    
    // Demo functions for testing
    fun simulateHighRisk() {
        _state.value = _state.value.copy(
            riskLevel = RiskLevel.HIGH,
            riskScore = 0.85f
        )
    }
    
    fun simulateHeightenedMode() {
        _state.value = _state.value.copy(safetyMode = SafetyMode.HEIGHTENED)
    }
    
    fun resetDemo() {
        _state.value = _state.value.copy(
            riskLevel = RiskLevel.LOW,
            riskScore = 0f,
            safetyMode = SafetyMode.NORMAL
        )
    }
    
    // Map functions
    fun updateLocation(location: LatLng) {
        _state.value = _state.value.copy(currentLocation = location)
    }

    fun startSafeJourney(destination: String? = null) {
        viewModelScope.launch {
            if (!_state.value.isServiceRunning) {
                SafetyForegroundService.start(app)
                userPreferences.setServiceEnabled(true)
            }
            journeySessionManager.startJourney(destination)
        }
    }

    fun startCompanionJourney(destination: String, durationMinutes: Int) {
        viewModelScope.launch {
            if (!_state.value.isServiceRunning) {
                SafetyForegroundService.start(app)
                userPreferences.setServiceEnabled(true)
            }
            journeyShareManager.startLiveJourney(destination, durationMinutes)
            journeyShareManager.shareJourney()
        }
    }

    fun shareCompanionJourney(targetContactIds: Set<Long> = emptySet()) {
        viewModelScope.launch {
            journeyShareManager.shareJourney(targetContactIds)
        }
    }

    fun shareActiveSafeJourney(targetContactIds: Set<Long> = emptySet()) {
        viewModelScope.launch {
            journeyShareManager.shareActiveJourneyViaWhatsAppAndSms(targetContactIds)
        }
    }

    fun requestJourneyCheckIn(label: String) {
        viewModelScope.launch {
            journeyShareManager.requestCheckIn(label)
        }
    }

    fun acknowledgeJourneyCheckIn() {
        viewModelScope.launch {
            journeyShareManager.acknowledgeCheckIn()
        }
    }

    fun completeCompanionJourney() {
        viewModelScope.launch {
            journeyShareManager.completeJourney()
        }
    }

    fun completeSafeJourney() {
        viewModelScope.launch {
            if (_state.value.companionJourneyState.liveSession?.status == JourneySessionStatus.ACTIVE) {
                journeyShareManager.completeJourney()
            } else {
                journeySessionManager.endJourney(JourneySessionStatus.COMPLETED)
            }
        }
    }

    fun cancelSafeJourney() {
        viewModelScope.launch {
            if (_state.value.companionJourneyState.liveSession?.status == JourneySessionStatus.ACTIVE) {
                journeyShareManager.cancelJourney()
            } else {
                journeySessionManager.endJourney(JourneySessionStatus.CANCELLED)
            }
        }
    }

    fun refreshWatchConnection() {
        viewModelScope.launch {
            val previousSync = _state.value.watchSync
            _state.value = _state.value.copy(
                watchSync = previousSync.copy(isSyncing = true)
            )
            val status = watchSyncManager.checkConnection()
            _state.value = _state.value.copy(
                watchSync = status.copy(
                    isSyncing = false,
                    lastSyncMillis = previousSync.lastSyncMillis,
                    contactsSynced = previousSync.contactsSynced,
                    locationSynced = previousSync.locationSynced
                )
            )
        }
    }

    fun syncWatchNow() {
        viewModelScope.launch {
            val currentState = _state.value
            _state.value = currentState.copy(
                watchSync = currentState.watchSync.copy(
                    isSyncing = true,
                    message = "Syncing watch..."
                )
            )

            val syncStatus = watchSyncManager.syncNow(
                riskLevel = currentState.riskLevel,
                riskScore = currentState.riskScore,
                safetyMode = currentState.safetyMode,
                serviceRunning = currentState.isServiceRunning,
                location = currentState.currentLocation
                    ?: currentState.safetyFeatures.lastTrackingLocation,
                contacts = currentState.emergencyContacts,
                featureState = currentState.safetyFeatures
            )

            _state.value = _state.value.copy(
                watchSync = syncStatus.copy(isSyncing = false)
            )
        }
    }

    fun scheduleFakeCall(delayMinutes: Int) {
        FakeCallWorker.schedule(app, delayMinutes)
        safetyFeatureManager.appendTimeline(
            "Fake call scheduled",
            "Scheduled in $delayMinutes minutes",
            _state.value.currentLocation
        )
    }

    fun setOfflineSafetyMode(enabled: Boolean) {
        safetyFeatureManager.enableOfflineSafety(enabled)
    }

    fun startTrustedJourney(destination: String, durationMinutes: Int) {
        safetyFeatureManager.startTrustedJourney(destination, durationMinutes)
    }

    fun completeTrustedJourney() {
        safetyFeatureManager.completeTrustedJourney()
    }

    fun startSafetyCheckIn(minutes: Int) {
        safetyFeatureManager.startSafetyCheckIn(minutes)
    }

    fun cancelSafetyCheckIn() {
        safetyFeatureManager.cancelSafetyCheckIn()
    }

    fun runEmergencyDrill() {
        safetyFeatureManager.runEmergencyDrill(_state.value.emergencyContacts)
    }

    fun handleCancelPin(pin: String): Boolean {
        val cancelled = safetyFeatureManager.handleCancelPin(pin)
        if (cancelled) {
            SafetyForegroundService.getInstance()?.cancelEmergencyCountdown()
        }
        return cancelled
    }

    fun simulateContactHelpComing() {
        val contact = _state.value.safetyFeatures.contactAcknowledgements.firstOrNull()
        if (contact != null) {
            safetyFeatureManager.markContactAcknowledgement(contact.contactId, "Help is coming")
        }
    }
    
    // Voice trigger functions
    fun setVoiceTriggerEnabled(enabled: Boolean) {
        viewModelScope.launch {
            userPreferences.setVoiceTriggerEnabled(enabled)
            _state.value = _state.value.copy(voiceTriggerEnabled = enabled)
        }
    }
}
