package com.safepulse.service

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.BatteryManager
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.google.android.gms.maps.model.LatLng
import com.google.gson.Gson
import com.safepulse.SafePulseApplication
import com.safepulse.data.cache.MapDataCacheManager
import com.safepulse.data.db.entity.EmergencyContactEntity
import com.safepulse.data.repository.EmergencyContactRepository
import com.safepulse.data.security.PinVerificationResult
import com.safepulse.data.security.SafetyPinRepository
import com.safepulse.domain.model.RiskLevel
import com.safepulse.domain.model.SafetyMode
import com.safepulse.ui.map.TileCacheManager
import com.safepulse.worker.SafetyCheckInWorker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

data class SafetyTimelineEntry(
    val timestamp: Long,
    val title: String,
    val detail: String,
    val lat: Double? = null,
    val lng: Double? = null,
    val batteryPercent: Int? = null,
    val riskLevel: String? = null,
    val safetyMode: String? = null,
    val eventType: String? = null,
    val hidden: Boolean = false
)

data class ContactAcknowledgement(
    val contactId: Long,
    val name: String,
    val phone: String,
    val status: String,
    val timestamp: Long = 0L
)

data class TrustedJourneyState(
    val active: Boolean = false,
    val destination: String = "",
    val etaMillis: Long = 0L,
    val startedAt: Long = 0L,
    val lastLocation: LatLng? = null
)

data class SafetyCheckInState(
    val active: Boolean = false,
    val dueAtMillis: Long = 0L,
    val startedAtMillis: Long = 0L,
    val label: String = ""
)

data class DuressState(
    val isDuressActive: Boolean = false,
    val activatedAt: Long = 0L,
    val lastLocation: LatLng? = null,
    val escalationLevel: Int = 0
)

data class SafetyFeatureState(
    val liveTrackingActive: Boolean = false,
    val liveTrackingSessionId: String = "",
    val liveTrackingStartedAt: Long = 0L,
    val lastTrackingLocation: LatLng? = null,
    val contactAcknowledgements: List<ContactAcknowledgement> = emptyList(),
    val timeline: List<SafetyTimelineEntry> = emptyList(),
    val offlineSafetyEnabled: Boolean = false,
    val offlineCacheSummary: String = "Not prepared",
    val duressPinEnabled: Boolean = true,
    val duressModeActive: Boolean = false,
    val duressState: DuressState = DuressState(),
    val trustedJourney: TrustedJourneyState = TrustedJourneyState(),
    val safetyCheckIn: SafetyCheckInState = SafetyCheckInState(),
    val nearbyHelperNetworkEnabled: Boolean = false,
    val sosMessageTemplate: String = SafetyFeatureManager.DEFAULT_SOS_MESSAGE,
    val fakeCallerName: String = SafetyFeatureManager.DEFAULT_FAKE_CALLER_NAME,
    val emergencyDrillActive: Boolean = false
) {
    val pendingAckCount: Int get() = contactAcknowledgements.count { it.status == "Pending" }
    val helpedAckCount: Int get() = contactAcknowledgements.count { it.status == "Help is coming" }
}

class SafetyFeatureManager private constructor(private val context: Context) {

    companion object {
        private const val TAG = "SafetyFeatures"
        private const val PREFS = "advanced_safety_features"
        private const val KEY_OFFLINE_MODE = "offline_mode"
        private const val KEY_CHECK_IN_ACTIVE = "check_in_active"
        private const val KEY_CHECK_IN_DUE_AT = "check_in_due_at"
        private const val KEY_CHECK_IN_STARTED_AT = "check_in_started_at"
        private const val KEY_CHECK_IN_LABEL = "check_in_label"
        private const val KEY_SOS_MESSAGE_TEMPLATE = "sos_message_template"
        private const val KEY_FAKE_CALLER_NAME = "fake_caller_name"
        private const val CHANNEL_ID = "advanced_safety_channel"
        private const val LIVE_TRACKING_NOTIFICATION_ID = 4401

        const val DEFAULT_SOS_MESSAGE = "Please check on me immediately."
        const val DEFAULT_FAKE_CALLER_NAME = "Mom"

        @Volatile
        private var INSTANCE: SafetyFeatureManager? = null

        fun getInstance(context: Context): SafetyFeatureManager {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: SafetyFeatureManager(context.applicationContext).also { INSTANCE = it }
            }
        }
    }

    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val safetyPinRepository = SafetyPinRepository.getInstance(context)
    private val gson = Gson()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val timelineFile = File(context.filesDir, "emergency_timeline.jsonl")
    private var trackingJob: Job? = null
    private var journeyJob: Job? = null
    private var duressEscalationJob: Job? = null
    private var locationProvider: (() -> LatLng?)? = null

    private val _state = MutableStateFlow(
        SafetyFeatureState(
            offlineSafetyEnabled = prefs.getBoolean(KEY_OFFLINE_MODE, false),
            offlineCacheSummary = buildOfflineCacheSummary(),
            timeline = readTimeline(includeHidden = false).takeLast(25),
            duressPinEnabled = safetyPinRepository.config.value.isEnabled,
            safetyCheckIn = readSafetyCheckInState(),
            sosMessageTemplate = getSosMessageTemplate(),
            fakeCallerName = getFakeCallerName()
        )
    )
    val state: StateFlow<SafetyFeatureState> = _state.asStateFlow()

    init {
        createNotificationChannel()
        scope.launch {
            safetyPinRepository.config.collect { config ->
                _state.value = _state.value.copy(duressPinEnabled = config.isEnabled)
            }
        }
    }

    fun setLocationProvider(provider: () -> LatLng?) {
        locationProvider = provider
    }

    fun startLiveTracking(trigger: String, contacts: List<EmergencyContactEntity>) {
        val sessionId = UUID.randomUUID().toString().take(8).uppercase()
        val acknowledgements = contacts.map {
            ContactAcknowledgement(
                contactId = it.id,
                name = it.name,
                phone = it.phone,
                status = "Pending"
            )
        }

        _state.value = _state.value.copy(
            liveTrackingActive = true,
            liveTrackingSessionId = sessionId,
            liveTrackingStartedAt = System.currentTimeMillis(),
            contactAcknowledgements = acknowledgements,
            duressModeActive = false
        )

        appendTimeline(
            "Live SOS tracking started",
            "Trigger: $trigger",
            locationProvider?.invoke(),
            eventType = "SOS_TRIGGERED"
        )
        showLiveTrackingNotification()

        trackingJob?.cancel()
        trackingJob = scope.launch {
            while (isActive && _state.value.liveTrackingActive) {
                val location = locationProvider?.invoke()
                if (location != null) {
                    _state.value = _state.value.copy(lastTrackingLocation = location)
                    appendTimeline("Tracking update", "Location refreshed", location)
                }
                delay(if (_state.value.duressModeActive) 2_000 else 5_000)
            }
        }
    }

    fun stopLiveTracking(reason: String) {
        if (_state.value.duressModeActive) {
            appendTimeline(
                "Live tracking kept active",
                "Stop request ignored because silent distress mode is active",
                locationProvider?.invoke(),
                eventType = "DURESS_MODE_ACTIVE",
                hidden = true
            )
            return
        }
        trackingJob?.cancel()
        trackingJob = null
        appendTimeline("Live SOS tracking stopped", reason, locationProvider?.invoke())
        _state.value = _state.value.copy(liveTrackingActive = false)
        cancelLiveTrackingNotification()
    }

    fun buildTrackingMessage(location: LatLng?): String {
        val sessionId = _state.value.liveTrackingSessionId.ifBlank { "pending" }
        val mapLink = location?.let {
            "https://maps.google.com/?q=${it.latitude},${it.longitude}"
        } ?: "Location is being acquired"

        return "SafePulse live SOS session $sessionId. Latest location: $mapLink. I may keep sending updates while SOS is active."
    }

    fun markContactAcknowledgement(contactId: Long, status: String) {
        val updated = _state.value.contactAcknowledgements.map {
            if (it.contactId == contactId) {
                it.copy(status = status, timestamp = System.currentTimeMillis())
            } else {
                it
            }
        }
        _state.value = _state.value.copy(contactAcknowledgements = updated)
        appendTimeline("Contact acknowledgement", status, locationProvider?.invoke())
    }

    fun enableOfflineSafety(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_OFFLINE_MODE, enabled).apply()
        scope.launch {
            if (enabled) {
                runCatching {
                    (context as? SafePulseApplication)?.database?.preloadSampleDataIfNeeded(context)
                }.onFailure {
                    Log.w(TAG, "Offline preload failed: ${it.message}")
                }
            }
            _state.value = _state.value.copy(
                offlineSafetyEnabled = enabled,
                offlineCacheSummary = buildOfflineCacheSummary()
            )
            appendTimeline(
                if (enabled) "Offline Safety Mode enabled" else "Offline Safety Mode disabled",
                buildOfflineCacheSummary(),
                locationProvider?.invoke()
            )
        }
    }

    fun getSosMessageTemplate(): String {
        return prefs.getString(KEY_SOS_MESSAGE_TEMPLATE, DEFAULT_SOS_MESSAGE)
            ?.ifBlank { DEFAULT_SOS_MESSAGE }
            ?: DEFAULT_SOS_MESSAGE
    }

    fun setSosMessageTemplate(message: String) {
        val cleanMessage = message.trim().ifBlank { DEFAULT_SOS_MESSAGE }.take(180)
        prefs.edit().putString(KEY_SOS_MESSAGE_TEMPLATE, cleanMessage).apply()
        _state.value = _state.value.copy(sosMessageTemplate = cleanMessage)
        appendTimeline("SOS template updated", "Emergency message text changed", locationProvider?.invoke())
    }

    fun getFakeCallerName(): String {
        return prefs.getString(KEY_FAKE_CALLER_NAME, DEFAULT_FAKE_CALLER_NAME)
            ?.ifBlank { DEFAULT_FAKE_CALLER_NAME }
            ?: DEFAULT_FAKE_CALLER_NAME
    }

    fun setFakeCallerName(name: String) {
        val cleanName = name.trim().ifBlank { DEFAULT_FAKE_CALLER_NAME }.take(32)
        prefs.edit().putString(KEY_FAKE_CALLER_NAME, cleanName).apply()
        _state.value = _state.value.copy(fakeCallerName = cleanName)
        appendTimeline("Fake caller updated", cleanName, locationProvider?.invoke())
    }

    fun setSafetyPinEnabled(enabled: Boolean) {
        safetyPinRepository.setEnabled(enabled)
        appendTimeline(
            if (enabled) "Silent Safety PIN enabled" else "Silent Safety PIN disabled",
            "PIN status changed",
            locationProvider?.invoke()
        )
    }

    fun setSafetyPins(normalPin: String, duressPin: String, enabled: Boolean = _state.value.duressPinEnabled): Boolean {
        val saved = safetyPinRepository.setPins(normalPin, duressPin, enabled)
        if (saved) {
            appendTimeline("Silent Safety PIN updated", "Normal and duress PIN hashes changed", locationProvider?.invoke())
        }
        return saved
    }

    fun handleCancelPin(pin: String, riskScore: Float = 0f): PinVerificationResult {
        return when (val result = safetyPinRepository.verifyPin(pin)) {
            PinVerificationResult.NORMAL_CANCELLED,
            PinVerificationResult.DISABLED_CANCELLED -> {
                appendTimeline(
                    title = "SOS cancelled",
                    detail = if (result == PinVerificationResult.DISABLED_CANCELLED) {
                        "Silent Safety PIN disabled"
                    } else {
                        "Normal cancel PIN accepted"
                    },
                    location = locationProvider?.invoke(),
                    eventType = "SOS_CANCELLED"
                )
                duressEscalationJob?.cancel()
                _state.value = _state.value.copy(
                    duressModeActive = false,
                    duressState = DuressState()
                )
                stopLiveTracking("Cancelled with normal PIN")
                result
            }
            PinVerificationResult.DURESS_ACTIVATED -> {
                activateDuressMode(riskScore)
                result
            }
            PinVerificationResult.INVALID -> result
        }
    }

    private fun activateDuressMode(riskScore: Float) {
        val now = System.currentTimeMillis()
        val location = locationProvider?.invoke()
        val duressState = DuressState(
            isDuressActive = true,
            activatedAt = now,
            lastLocation = location,
            escalationLevel = 0
        )

        _state.value = _state.value.copy(
            duressModeActive = true,
            duressState = duressState,
            liveTrackingActive = true,
            lastTrackingLocation = location ?: _state.value.lastTrackingLocation
        )

        appendTimeline(
            title = "Duress PIN used",
            detail = "Silent distress mode activated",
            location = location,
            eventType = "DURESS_PIN_USED",
            hidden = true
        )
        appendTimeline(
            title = "Duress mode active",
            detail = "Protection continues secretly in background",
            location = location,
            eventType = "DURESS_MODE_ACTIVE",
            hidden = true
        )
        showLiveTrackingNotification()
        silentlyNotifyTrustedContacts(level = 0, riskScore = riskScore)
        startDuressEscalation(riskScore)
    }

    private fun startDuressEscalation(riskScore: Float) {
        duressEscalationJob?.cancel()
        duressEscalationJob = scope.launch {
            listOf(
                1 to 5 * 60_000L,
                2 to 5 * 60_000L,
                3 to 5 * 60_000L
            ).forEach { (level, delayMillis) ->
                delay(delayMillis)
                if (!_state.value.duressModeActive) return@launch
                _state.value = _state.value.copy(
                    duressState = _state.value.duressState.copy(
                        lastLocation = locationProvider?.invoke() ?: _state.value.duressState.lastLocation,
                        escalationLevel = level
                    )
                )
                silentlyNotifyTrustedContacts(level = level, riskScore = riskScore)
                appendTimeline(
                    title = "Duress escalation level $level",
                    detail = "Silent protection escalation executed",
                    location = locationProvider?.invoke(),
                    eventType = "DURESS_MODE_ACTIVE",
                    hidden = true
                )

                if (level == 3) {
                    SafetyForegroundService.getInstance()?.triggerSilentSOS()
                }
            }
        }
    }

    private fun silentlyNotifyTrustedContacts(level: Int, riskScore: Float) {
        scope.launch {
            val app = context as? SafePulseApplication ?: return@launch
            val contacts = EmergencyContactRepository(app.database.emergencyContactDao()).getAllContactsList()
            if (contacts.isEmpty()) return@launch

            val location = locationProvider?.invoke() ?: _state.value.duressState.lastLocation
            val timestamp = SimpleDateFormat("dd MMM yyyy, HH:mm:ss", Locale.getDefault()).format(Date())
            val locationText = location?.let {
                "${it.latitude}, ${it.longitude}\nMap: https://maps.google.com/?q=${it.latitude},${it.longitude}"
            } ?: "Location is being acquired"
            val levelText = when (level) {
                1 -> "Level 1 escalation: silent distress still active."
                2 -> "Level 2 escalation: updated location shared."
                3 -> "Level 3 escalation: full emergency workflow triggered."
                else -> "Silent distress mode activated."
            }
            val message = """
                Silent distress mode activated.
                $levelText
                Time: $timestamp
                Location: $locationText
                Risk score: ${(riskScore * 100).toInt()}
            """.trimIndent()

            EmergencyManager(context).sendJourneyStatusMessages(contacts, message)
        }
    }

    fun startTrustedJourney(destination: String, durationMinutes: Int) {
        val now = System.currentTimeMillis()
        val eta = now + durationMinutes.coerceAtLeast(1) * 60_000L
        val journey = TrustedJourneyState(
            active = true,
            destination = destination.ifBlank { "Selected destination" },
            etaMillis = eta,
            startedAt = now,
            lastLocation = locationProvider?.invoke()
        )
        _state.value = _state.value.copy(trustedJourney = journey)
        appendTimeline("Trusted Journey started", "${journey.destination}, ETA ${durationMinutes} min", journey.lastLocation)

        journeyJob?.cancel()
        journeyJob = scope.launch {
            while (isActive && _state.value.trustedJourney.active) {
                val remaining = _state.value.trustedJourney.etaMillis - System.currentTimeMillis()
                if (remaining <= 0L) {
                    appendTimeline("Trusted Journey check-in missed", "Destination: ${_state.value.trustedJourney.destination}", locationProvider?.invoke())
                    SafetyForegroundService.getInstance()?.triggerSilentSOS()
                    _state.value = _state.value.copy(trustedJourney = _state.value.trustedJourney.copy(active = false))
                    break
                }
                delay(15_000)
            }
        }
    }

    fun completeTrustedJourney() {
        journeyJob?.cancel()
        appendTimeline("Trusted Journey completed", _state.value.trustedJourney.destination, locationProvider?.invoke())
        _state.value = _state.value.copy(trustedJourney = TrustedJourneyState())
    }

    fun startSafetyCheckIn(minutes: Int) {
        val safeMinutes = minutes.coerceIn(5, 240)
        val now = System.currentTimeMillis()
        val state = SafetyCheckInState(
            active = true,
            dueAtMillis = now + safeMinutes * 60_000L,
            startedAtMillis = now,
            label = "$safeMinutes minute check-in"
        )

        prefs.edit()
            .putBoolean(KEY_CHECK_IN_ACTIVE, true)
            .putLong(KEY_CHECK_IN_DUE_AT, state.dueAtMillis)
            .putLong(KEY_CHECK_IN_STARTED_AT, state.startedAtMillis)
            .putString(KEY_CHECK_IN_LABEL, state.label)
            .apply()

        SafetyCheckInWorker.schedule(context, safeMinutes)
        _state.value = _state.value.copy(safetyCheckIn = state)
        appendTimeline("Safety check-in started", state.label, locationProvider?.invoke())
    }

    fun cancelSafetyCheckIn(reason: String = "Cancelled by user") {
        SafetyCheckInWorker.cancel(context)
        clearSafetyCheckInPrefs()
        _state.value = _state.value.copy(safetyCheckIn = SafetyCheckInState())
        appendTimeline("Safety check-in cleared", reason, locationProvider?.invoke())
    }

    fun handleMissedSafetyCheckIn() {
        val checkIn = readSafetyCheckInState()
        if (!checkIn.active) return

        clearSafetyCheckInPrefs()
        _state.value = _state.value.copy(safetyCheckIn = SafetyCheckInState())
        appendTimeline("Safety check-in missed", checkIn.label, locationProvider?.invoke())
    }

    fun runEmergencyDrill(contacts: List<EmergencyContactEntity>) {
        val sessionId = "DRILL-${UUID.randomUUID().toString().take(4).uppercase()}"
        val acknowledgements = contacts.map {
            ContactAcknowledgement(
                contactId = it.id,
                name = it.name,
                phone = it.phone,
                status = "Drill only",
                timestamp = System.currentTimeMillis()
            )
        }

        _state.value = _state.value.copy(
            emergencyDrillActive = true,
            liveTrackingSessionId = sessionId,
            contactAcknowledgements = acknowledgements
        )

        appendTimeline(
            title = "Emergency drill started",
            detail = "No SMS or call sent. Contacts checked: ${contacts.size}",
            location = locationProvider?.invoke()
        )

        scope.launch {
            delay(8_000)
            _state.value = _state.value.copy(emergencyDrillActive = false)
            appendTimeline("Emergency drill completed", "All local checks finished", locationProvider?.invoke())
        }
    }

    fun exportTimelineText(): String {
        val entries = _state.value.timeline.ifEmpty { readTimeline(includeHidden = false).takeLast(25) }
        if (entries.isEmpty()) {
            return "SafePulse Emergency Timeline\n\nNo emergency timeline entries recorded yet."
        }

        val formatter = SimpleDateFormat("dd MMM yyyy, HH:mm:ss", Locale.getDefault())
        return buildString {
            appendLine("SafePulse Emergency Timeline")
            appendLine("Generated: ${formatter.format(Date())}")
            appendLine()
            entries.forEachIndexed { index, entry ->
                appendLine("${index + 1}. ${formatter.format(Date(entry.timestamp))}")
                appendLine("   ${entry.title}")
                appendLine("   ${entry.detail}")
                entry.batteryPercent?.let { appendLine("   Battery: $it%") }
                if (entry.riskLevel != null || entry.safetyMode != null) {
                    appendLine("   Risk: ${entry.riskLevel ?: "Unknown"} | Mode: ${entry.safetyMode ?: "Unknown"}")
                }
                if (entry.lat != null && entry.lng != null) {
                    appendLine("   Location: ${entry.lat}, ${entry.lng}")
                    appendLine("   Map: https://maps.google.com/?q=${entry.lat},${entry.lng}")
                }
                appendLine()
            }
        }
    }

    fun appendTimeline(
        title: String,
        detail: String,
        location: LatLng?,
        riskLevel: RiskLevel? = null,
        safetyMode: SafetyMode? = null,
        eventType: String? = null,
        hidden: Boolean = false
    ) {
        val entry = SafetyTimelineEntry(
            timestamp = System.currentTimeMillis(),
            title = title,
            detail = detail,
            lat = location?.latitude,
            lng = location?.longitude,
            batteryPercent = getBatteryPercent(),
            riskLevel = riskLevel?.name,
            safetyMode = safetyMode?.name,
            eventType = eventType,
            hidden = hidden
        )

        runCatching {
            timelineFile.appendText(gson.toJson(entry) + "\n")
        }.onFailure {
            Log.w(TAG, "Failed to persist timeline: ${it.message}")
        }

        if (!hidden) {
            _state.value = _state.value.copy(
                timeline = (_state.value.timeline + entry).takeLast(25)
            )
        }
    }

    private fun readTimeline(includeHidden: Boolean): List<SafetyTimelineEntry> {
        if (!timelineFile.exists()) return emptyList()
        return runCatching {
            timelineFile.readLines()
                .filter { it.isNotBlank() }
                .map { gson.fromJson(it, SafetyTimelineEntry::class.java) }
                .filter { includeHidden || !it.hidden }
        }.getOrElse { emptyList() }
    }

    private fun buildOfflineCacheSummary(): String {
        val tileCache = TileCacheManager(context)
        val mapDataCache = MapDataCacheManager(context)
        return "Maps: ${tileCache.getCacheSizeFormatted()} tiles, ${mapDataCache.getCacheSizeFormatted()} safety/route data cached"
    }

    private fun readSafetyCheckInState(): SafetyCheckInState {
        val active = prefs.getBoolean(KEY_CHECK_IN_ACTIVE, false)
        if (!active) return SafetyCheckInState()

        return SafetyCheckInState(
            active = true,
            dueAtMillis = prefs.getLong(KEY_CHECK_IN_DUE_AT, 0L),
            startedAtMillis = prefs.getLong(KEY_CHECK_IN_STARTED_AT, 0L),
            label = prefs.getString(KEY_CHECK_IN_LABEL, "Safety check-in") ?: "Safety check-in"
        )
    }

    private fun clearSafetyCheckInPrefs() {
        prefs.edit()
            .remove(KEY_CHECK_IN_ACTIVE)
            .remove(KEY_CHECK_IN_DUE_AT)
            .remove(KEY_CHECK_IN_STARTED_AT)
            .remove(KEY_CHECK_IN_LABEL)
            .apply()
    }

    private fun getBatteryPercent(): Int? {
        val batteryManager = context.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager
        return batteryManager?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = context.getSystemService(NotificationManager::class.java)
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Advanced Safety",
                NotificationManager.IMPORTANCE_HIGH
            )
            manager.createNotificationChannel(channel)
        }
    }

    private fun showLiveTrackingNotification() {
        if (!canPostNotifications()) return

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_map)
            .setContentTitle("Live SOS tracking active")
            .setContentText("Session ${_state.value.liveTrackingSessionId} is recording updates")
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()

        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(LIVE_TRACKING_NOTIFICATION_ID, notification)
    }

    private fun cancelLiveTrackingNotification() {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.cancel(LIVE_TRACKING_NOTIFICATION_ID)
    }

    private fun canPostNotifications(): Boolean {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
                ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.POST_NOTIFICATIONS
                ) == PackageManager.PERMISSION_GRANTED
    }
}
