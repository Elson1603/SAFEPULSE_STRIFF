package com.safepulse.service

import android.Manifest
import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.google.android.gms.wearable.*
import com.safepulse.SafePulseApplication
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import com.google.android.gms.maps.model.LatLng
import com.safepulse.data.repository.EmergencyContactRepository
import com.safepulse.domain.model.EmergencyEvent
import com.safepulse.domain.model.EventType
import com.safepulse.domain.model.LocationData
import com.safepulse.worker.FakeCallWorker
import kotlinx.coroutines.*
import kotlinx.coroutines.tasks.await
import java.util.Collections
import java.util.LinkedHashMap

/**
 * Wearable listener service on the PHONE side.
 * Receives messages from the watch (SOS triggers, action requests)
 * and forwards them to the appropriate phone-side services.
 */
class PhoneWearListenerService : com.google.android.gms.wearable.WearableListenerService() {

    companion object {
        private const val TAG = "WearListener"
        private const val WATCH_ACTIONS_CHANNEL = "watch_actions_channel"
        private const val SHARE_LOCATION_NOTIFICATION_ID = 2201

        // Message paths (must match wear module WearDataPaths)
        const val PATH_SOS_TRIGGER = "/safepulse/sos/trigger"
        const val PATH_SOS_CANCEL = "/safepulse/sos/cancel"
        const val PATH_SOS_CONFIRM = "/safepulse/sos/confirm"
        const val PATH_SILENT_ALERT = "/safepulse/sos/silent"
        const val PATH_FAKE_CALL = "/safepulse/action/fake_call"
        const val PATH_FAKE_CALL_SCHEDULE = "/safepulse/action/fake_call_schedule"
        const val PATH_SHARE_LOCATION = "/safepulse/action/share_location"
        const val PATH_CONTACT_CALL = "/safepulse/action/contact_call"
        const val PATH_OFFLINE_MODE_TOGGLE = "/safepulse/action/offline_mode_toggle"
        const val PATH_CHECK_IN_START = "/safepulse/action/check_in_start"
        const val PATH_CHECK_IN_CANCEL = "/safepulse/action/check_in_cancel"
        const val PATH_EMERGENCY_DRILL = "/safepulse/action/emergency_drill"
        const val PATH_SHARE_TIMELINE = "/safepulse/action/share_timeline"
        const val PATH_TRUSTED_JOURNEY_START = "/safepulse/action/trusted_journey_start"
        const val PATH_TRUSTED_JOURNEY_COMPLETE = "/safepulse/action/trusted_journey_complete"
        const val PATH_REQUEST_STATUS = "/safepulse/status/request"
        const val PATH_PING = "/safepulse/ping"
        const val PATH_PING_REQUEST_PREFIX = "/safepulse/ping/request"
        const val PATH_PING_RESPONSE_PREFIX = "/safepulse/ping/response"
        const val PATH_COMMAND_PREFIX = "/safepulse/command"

        // Data paths for syncing TO watch
        const val PATH_SAFETY_STATUS = "/safepulse/status/safety"
        const val PATH_EMERGENCY_CONTACTS = "/safepulse/data/contacts"
        const val PATH_LOCATION_DATA = "/safepulse/data/location"
        const val PATH_HEART_RATE_DATA = "/safepulse/data/heart_rate"

        // Data keys
        const val KEY_RISK_LEVEL = "risk_level"
        const val KEY_RISK_SCORE = "risk_score"
        const val KEY_SAFETY_MODE = "safety_mode"
        const val KEY_IS_EMERGENCY = "is_emergency"
        const val KEY_SERVICE_RUNNING = "service_running"
        const val KEY_CONTACTS_JSON = "contacts_json"
        const val KEY_LATITUDE = "latitude"
        const val KEY_LONGITUDE = "longitude"
        const val KEY_HEART_RATE = "heart_rate"
        const val KEY_TIMESTAMP = "timestamp"
        const val KEY_LIVE_TRACKING_ACTIVE = "live_tracking_active"
        const val KEY_LIVE_TRACKING_SESSION = "live_tracking_session"
        const val KEY_PENDING_ACK_COUNT = "pending_ack_count"
        const val KEY_HELP_ACK_COUNT = "help_ack_count"
        const val KEY_OFFLINE_MODE = "offline_mode"
        const val KEY_DURESS_MODE = "duress_mode"
        const val KEY_JOURNEY_ACTIVE = "journey_active"
        const val KEY_JOURNEY_DESTINATION = "journey_destination"
        const val KEY_JOURNEY_ETA = "journey_eta"
        const val KEY_CHECK_IN_ACTIVE = "check_in_active"
        const val KEY_CHECK_IN_DUE_AT = "check_in_due_at"
        const val KEY_CHECK_IN_LABEL = "check_in_label"
        const val KEY_DRILL_ACTIVE = "drill_active"
        const val KEY_COMMAND_ID = "command_id"
        const val KEY_COMMAND_PATH = "command_path"
        const val KEY_COMMAND_PAYLOAD = "command_payload"
        const val KEY_PING_ID = "ping_id"

        private const val SOS_SERVICE_START_RETRIES = 8
        private const val SOS_SERVICE_START_RETRY_DELAY_MS = 500L

        private val commandScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        private val processedWearCommands = Collections.synchronizedMap(
            object : LinkedHashMap<String, Long>() {
                override fun removeEldestEntry(
                    eldest: MutableMap.MutableEntry<String, Long>?
                ): Boolean {
                    return size > 100
                }
            }
        )
    }

    private data class WearCommand(
        val path: String,
        val payload: ByteArray,
        val commandId: String?
    )

    override fun onMessageReceived(messageEvent: MessageEvent) {
        super.onMessageReceived(messageEvent)
        Log.d(TAG, "Message from watch: ${messageEvent.path}")

        val command = parseWearCommandEnvelope(messageEvent.path, messageEvent.data)
            ?: WearCommand(messageEvent.path, messageEvent.data, null)
        handleWearCommand(command, messageEvent.sourceNodeId)
    }

    override fun onDataChanged(dataEvents: DataEventBuffer) {
        super.onDataChanged(dataEvents)

        for (event in dataEvents) {
            if (event.type == DataEvent.TYPE_CHANGED) {
                val path = event.dataItem.uri.path.orEmpty()
                if (path.startsWith("$PATH_PING_REQUEST_PREFIX/")) {
                    handlePingDataItem(event.dataItem)
                    continue
                }

                if (path.startsWith("$PATH_COMMAND_PREFIX/")) {
                    handleWearCommandDataItem(event.dataItem)
                    continue
                }

                when (path) {
                    PATH_HEART_RATE_DATA -> {
                        val dataMap = DataMapItem.fromDataItem(event.dataItem).dataMap
                        val hr = dataMap.getInt(KEY_HEART_RATE, 0)
                        Log.d(TAG, "Heart rate from watch: $hr BPM")
                        // Could integrate this into SafetyEngine for distress detection
                    }
                }
            }
        }
    }

    private fun parseWearCommandEnvelope(fallbackPath: String, data: ByteArray): WearCommand? {
        return try {
            val dataMap = DataMap.fromByteArray(data)
            if (!dataMap.containsKey(KEY_COMMAND_PATH) && !dataMap.containsKey(KEY_COMMAND_ID)) {
                return null
            }
            val commandPath = dataMap.getString(KEY_COMMAND_PATH, fallbackPath)
            val commandId = dataMap.getString(KEY_COMMAND_ID, "")
            val payload = dataMap.getByteArray(KEY_COMMAND_PAYLOAD) ?: ByteArray(0)
            WearCommand(
                path = commandPath.ifBlank { fallbackPath },
                payload = payload,
                commandId = commandId.ifBlank { null }
            )
        } catch (_: Exception) {
            null
        }
    }

    private fun handleWearCommandDataItem(dataItem: DataItem) {
        val uri = dataItem.uri
        val dataMap = DataMapItem.fromDataItem(dataItem).dataMap
        val command = WearCommand(
            path = dataMap.getString(KEY_COMMAND_PATH, ""),
            payload = dataMap.getByteArray(KEY_COMMAND_PAYLOAD) ?: ByteArray(0),
            commandId = dataMap.getString(KEY_COMMAND_ID, "").ifBlank { null }
        )

        handleWearCommand(command, uri.host.orEmpty())
        commandScope.launch {
            try {
                Wearable.getDataClient(this@PhoneWearListenerService)
                    .deleteDataItems(uri)
                    .await()
            } catch (e: Exception) {
                Log.w(TAG, "Failed to delete processed wear command", e)
            }
        }
    }

    private fun handleWearCommand(command: WearCommand, sourceNodeId: String) {
        if (command.path.isBlank()) {
            Log.w(TAG, "Ignoring blank wear command: ${command.commandId}")
            return
        }

        if (!markWearCommandForProcessing(command.commandId)) {
            Log.d(TAG, "Ignoring duplicate wear command: ${command.path} (${command.commandId})")
            return
        }

        Log.d(TAG, "Wear command accepted: ${command.path} (${command.commandId ?: "legacy"})")
        when (command.path) {
            PATH_SOS_TRIGGER -> handleSOSTrigger(command.payload, sourceNodeId)
            PATH_SOS_CANCEL -> handleSOSCancel()
            PATH_SILENT_ALERT -> handleSilentAlert()
            PATH_FAKE_CALL -> handleFakeCall()
            PATH_FAKE_CALL_SCHEDULE -> handleScheduledFakeCall(command.payload)
            PATH_SHARE_LOCATION -> handleShareLocation()
            PATH_CONTACT_CALL -> handleContactCall(command.payload)
            PATH_OFFLINE_MODE_TOGGLE -> handleOfflineModeToggle()
            PATH_CHECK_IN_START -> handleCheckInStart(command.payload)
            PATH_CHECK_IN_CANCEL -> handleCheckInCancel()
            PATH_EMERGENCY_DRILL -> handleEmergencyDrill()
            PATH_SHARE_TIMELINE -> handleShareTimeline()
            PATH_TRUSTED_JOURNEY_START -> handleTrustedJourneyStart(command.payload)
            PATH_TRUSTED_JOURNEY_COMPLETE -> handleTrustedJourneyComplete()
            PATH_REQUEST_STATUS -> handleStatusRequest(sourceNodeId)
            PATH_PING -> handlePing(sourceNodeId, command.payload)
            else -> Log.w(TAG, "Unknown wear command: ${command.path}")
        }
    }

    private fun markWearCommandForProcessing(commandId: String?): Boolean {
        if (commandId.isNullOrBlank()) return true

        val now = System.currentTimeMillis()
        synchronized(processedWearCommands) {
            processedWearCommands.entries.removeAll { now - it.value > 10 * 60_000L }
            if (processedWearCommands.containsKey(commandId)) return false
            processedWearCommands[commandId] = now
        }
        return true
    }

    private fun handleSOSTrigger(data: ByteArray, sourceNodeId: String) {
        Log.w(TAG, "🚨 SOS triggered from watch!")
        val triggerPayload = data.decodeToString()

        commandScope.launch {
            val serviceTriggered = triggerSosThroughSafetyService()
            val directCallStarted = if (serviceTriggered) {
                placeWatchSosCall()
            } else {
                executeDirectWatchSosFallback(triggerPayload).second
            }

            SafetyFeatureManager.getInstance(applicationContext).appendTimeline(
                "Wear SOS received",
                "Phone service: $serviceTriggered, call requested: $directCallStarted",
                null
            )
            sendSosConfirmToWatch(sourceNodeId, serviceTriggered, directCallStarted)
            sendSafetyStatusToWatch()
        }
    }

    private suspend fun triggerSosThroughSafetyService(): Boolean {
        SafetyForegroundService.getInstance()?.let { service ->
            service.triggerManualSOS()
            return true
        }

        try {
            SafetyForegroundService.start(this)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start safety service for watch SOS", e)
            return false
        }

        for (attempt in 1..SOS_SERVICE_START_RETRIES) {
            delay(SOS_SERVICE_START_RETRY_DELAY_MS)
            SafetyForegroundService.getInstance()?.let { service ->
                service.triggerManualSOS()
                Log.d(TAG, "Safety service accepted watch SOS on attempt $attempt")
                return true
            }
        }

        Log.e(TAG, "Safety service did not become ready for watch SOS")
        return false
    }

    private suspend fun placeWatchSosCall(): Boolean {
        return try {
            val app = application as SafePulseApplication
            val contactRepo = EmergencyContactRepository(app.database.emergencyContactDao())
            val contacts = contactRepo.getAllContactsList()
            val primaryContact = contactRepo.getPrimaryContact() ?: contacts.firstOrNull()

            EmergencyManager(applicationContext).initiateEmergencyCall(primaryContact)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to place watch SOS call", e)
            false
        }
    }

    private suspend fun executeDirectWatchSosFallback(triggerPayload: String): Pair<Boolean, Boolean> {
        return try {
            val app = application as SafePulseApplication
            val contactRepo = EmergencyContactRepository(app.database.emergencyContactDao())
            val contacts = contactRepo.getAllContactsList()
            val primaryContact = contactRepo.getPrimaryContact() ?: contacts.firstOrNull()
            val phoneLocation = getCurrentPhoneLocation()
            val locationData = phoneLocation?.let {
                LocationData(
                    latitude = it.latitude,
                    longitude = it.longitude,
                    accuracy = it.accuracy,
                    speed = it.speed,
                    timestamp = it.time
                )
            }
            val event = EmergencyEvent(
                type = EventType.MANUAL_SOS,
                confidence = 1.0f,
                location = locationData,
                requiresConfirmation = false
            )
            val emergencyManager = EmergencyManager(applicationContext)
            val featureManager = SafetyFeatureManager.getInstance(applicationContext)

            featureManager.startLiveTracking(event.type.name, contacts)
            featureManager.appendTimeline(
                "Wear SOS fallback",
                triggerPayload.ifBlank { "Safety service was not ready, direct phone response started" },
                phoneLocation?.let { LatLng(it.latitude, it.longitude) }
            )

            val smsSent = emergencyManager.sendSOSMessages(contacts, event)
            val callStarted = emergencyManager.initiateEmergencyCall(primaryContact)
            Log.w(TAG, "Direct watch SOS fallback executed. SMS: $smsSent, call: $callStarted")
            smsSent to callStarted
        } catch (e: Exception) {
            Log.e(TAG, "Direct watch SOS fallback failed", e)
            false to false
        }
    }

    private suspend fun sendSosConfirmToWatch(
        nodeId: String,
        serviceTriggered: Boolean,
        callStarted: Boolean
    ) {
        try {
            val payload = "service=$serviceTriggered|call=$callStarted|time=${System.currentTimeMillis()}"
                .toByteArray()
            val messageClient = Wearable.getMessageClient(this@PhoneWearListenerService)
            if (nodeId.isNotBlank()) {
                messageClient.sendMessage(nodeId, PATH_SOS_CONFIRM, payload).await()
            } else {
                Wearable.getNodeClient(this@PhoneWearListenerService)
                    .connectedNodes
                    .await()
                    .forEach { node ->
                        runCatching {
                            messageClient.sendMessage(node.id, PATH_SOS_CONFIRM, payload).await()
                        }.onFailure {
                            Log.w(TAG, "Failed to send SOS confirm to ${node.displayName}", it)
                        }
                    }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to confirm watch SOS", e)
        }
    }

    private fun handleSOSCancel() {
        Log.d(TAG, "SOS cancelled from watch")
        SafetyForegroundService.getInstance()?.cancelEmergencyCountdown()
    }

    private fun handleSilentAlert() {
        Log.d(TAG, "Silent alert from watch")
        val service = SafetyForegroundService.getInstance()
        if (service != null) {
            service.triggerSilentSOS()
        } else {
            SafetyForegroundService.start(this)
            commandScope.launch {
                delay(1500)
                SafetyForegroundService.getInstance()?.triggerSilentSOS()
            }
        }
    }

    private fun handleFakeCall() {
        Log.d(TAG, "Fake call requested from watch")
        try {
            FakeCallWorker.showFakeCall(applicationContext)
            SafetyFeatureManager.getInstance(applicationContext).appendTimeline(
                "Fake call started",
                "Requested from watch",
                null
            )
        } catch (e: Exception) {
            Log.e(TAG, "Error creating fake call", e)
        }
    }

    private fun handleScheduledFakeCall(data: ByteArray) {
        val delayMinutes = data.decodeToString().toIntOrNull() ?: 5
        FakeCallWorker.schedule(applicationContext, delayMinutes)
        SafetyFeatureManager.getInstance(applicationContext).appendTimeline(
            "Fake call scheduled",
            "Requested from watch in $delayMinutes minutes",
            null
        )
    }

    private fun handleShareLocation() {
        Log.d(TAG, "Location share requested from watch")

        commandScope.launch {
            val location = getCurrentPhoneLocation()
            val message = if (location != null) {
                "My current location: https://maps.google.com/?q=${location.latitude},${location.longitude}"
            } else {
                "Sharing my location from SafePulse. Current GPS location is not available yet."
            }

            showShareLocationNotification(message)
        }
    }

    private fun handleContactCall(data: ByteArray) {
        val payload = data.decodeToString()
        val parts = payload.split("|", limit = 2)
        val name = parts.getOrNull(0).orEmpty().ifBlank { "Emergency contact" }
        val phone = parts.getOrNull(1).orEmpty()

        if (phone.isBlank()) {
            Log.w(TAG, "Contact call requested without phone number")
            return
        }

        showContactCallNotification(name, phone)
        SafetyFeatureManager.getInstance(applicationContext).appendTimeline(
            "Contact call requested",
            "Requested from watch: $name",
            null
        )
    }

    private fun handleOfflineModeToggle() {
        val manager = SafetyFeatureManager.getInstance(applicationContext)
        manager.enableOfflineSafety(!manager.state.value.offlineSafetyEnabled)
        commandScope.launch { sendSafetyStatusToWatch() }
    }

    private fun handleCheckInStart(data: ByteArray) {
        val minutes = data.decodeToString().toIntOrNull() ?: 15
        SafetyFeatureManager.getInstance(applicationContext).startSafetyCheckIn(minutes)
        commandScope.launch { sendSafetyStatusToWatch() }
    }

    private fun handleCheckInCancel() {
        SafetyFeatureManager.getInstance(applicationContext)
            .cancelSafetyCheckIn("Cancelled from watch")
        commandScope.launch { sendSafetyStatusToWatch() }
    }

    private fun handleEmergencyDrill() {
        commandScope.launch {
            val app = application as SafePulseApplication
            val contactRepo = EmergencyContactRepository(app.database.emergencyContactDao())
            SafetyFeatureManager.getInstance(applicationContext)
                .runEmergencyDrill(contactRepo.getAllContactsList())
            sendSafetyStatusToWatch()
        }
    }

    private fun handleShareTimeline() {
        val message = SafetyFeatureManager.getInstance(applicationContext).exportTimelineText()
        showShareTimelineNotification(message)
    }

    private fun handleTrustedJourneyStart(data: ByteArray) {
        val parts = data.decodeToString().split("|")
        val destination = parts.getOrNull(0).orEmpty().ifBlank { "Wear journey" }
        val minutes = parts.getOrNull(1)?.toIntOrNull() ?: 15
        SafetyFeatureManager.getInstance(applicationContext)
            .startTrustedJourney(destination, minutes)
        commandScope.launch { sendSafetyStatusToWatch() }
    }

    private fun handleTrustedJourneyComplete() {
        SafetyFeatureManager.getInstance(applicationContext).completeTrustedJourney()
        commandScope.launch { sendSafetyStatusToWatch() }
    }

    private fun handleStatusRequest(nodeId: String) {
        Log.d(TAG, "Status request from watch node: $nodeId")
        commandScope.launch {
            sendSafetyStatusToWatch()
            sendLocationToWatch()
            sendEmergencyContactsToWatch()
        }
    }

    private fun handlePingDataItem(dataItem: DataItem) {
        val uri = dataItem.uri
        val path = uri.path.orEmpty()
        val dataMap = DataMapItem.fromDataItem(dataItem).dataMap
        val pingId = dataMap.getString(KEY_PING_ID, "")
            .ifBlank { path.substringAfterLast("/") }

        respondToPing(uri.host.orEmpty(), pingId)
        commandScope.launch {
            try {
                Wearable.getDataClient(this@PhoneWearListenerService)
                    .deleteDataItems(uri)
                    .await()
            } catch (e: Exception) {
                Log.w(TAG, "Failed to delete processed ping request", e)
            }
        }
    }

    private fun handlePing(nodeId: String, payload: ByteArray) {
        Log.d(TAG, "Ping from watch, responding...")
        val pingId = payload.decodeToString().takeIf { it.isNotBlank() } ?: System.currentTimeMillis().toString()
        respondToPing(nodeId, pingId)
    }

    private fun respondToPing(nodeId: String, pingId: String) {
        commandScope.launch {
            try {
                val response = "pong|$pingId".toByteArray()
                Wearable.getMessageClient(this@PhoneWearListenerService)
                    .sendMessage(nodeId, PATH_PING, response)
                    .await()
            } catch (e: Exception) {
                Log.e(TAG, "Failed to respond to ping", e)
            }

            try {
                val request = PutDataMapRequest
                    .create("$PATH_PING_RESPONSE_PREFIX/$pingId")
                    .apply {
                        dataMap.putString(KEY_PING_ID, pingId)
                        dataMap.putLong(KEY_TIMESTAMP, System.currentTimeMillis())
                    }
                    .asPutDataRequest()
                    .setUrgent()
                Wearable.getDataClient(this@PhoneWearListenerService).putDataItem(request).await()
            } catch (e: Exception) {
                Log.e(TAG, "Failed to write ping response DataItem", e)
            }
        }
    }

    /**
     * Send current safety status to watch via Data Layer.
     */
    private suspend fun sendSafetyStatusToWatch() {
        try {
            val service = SafetyForegroundService.getInstance()
            val riskLevel = "LOW"  // Default; in real integration, read from SafetyEngine state
            val serviceRunning = service != null
            val featureState = SafetyFeatureManager.getInstance(applicationContext).state.value

            val request = PutDataMapRequest.create(PATH_SAFETY_STATUS).apply {
                dataMap.putString(KEY_RISK_LEVEL, riskLevel)
                dataMap.putFloat(KEY_RISK_SCORE, 0f)
                dataMap.putString(KEY_SAFETY_MODE, "NORMAL")
                dataMap.putBoolean(KEY_IS_EMERGENCY, false)
                dataMap.putBoolean(KEY_SERVICE_RUNNING, serviceRunning)
                dataMap.putBoolean(KEY_LIVE_TRACKING_ACTIVE, featureState.liveTrackingActive)
                dataMap.putString(KEY_LIVE_TRACKING_SESSION, featureState.liveTrackingSessionId)
                dataMap.putInt(KEY_PENDING_ACK_COUNT, featureState.pendingAckCount)
                dataMap.putInt(KEY_HELP_ACK_COUNT, featureState.helpedAckCount)
                dataMap.putBoolean(KEY_OFFLINE_MODE, featureState.offlineSafetyEnabled)
                dataMap.putBoolean(KEY_DURESS_MODE, featureState.duressModeActive)
                dataMap.putBoolean(KEY_JOURNEY_ACTIVE, featureState.trustedJourney.active)
                dataMap.putString(KEY_JOURNEY_DESTINATION, featureState.trustedJourney.destination)
                dataMap.putLong(KEY_JOURNEY_ETA, featureState.trustedJourney.etaMillis)
                dataMap.putBoolean(KEY_CHECK_IN_ACTIVE, featureState.safetyCheckIn.active)
                dataMap.putLong(KEY_CHECK_IN_DUE_AT, featureState.safetyCheckIn.dueAtMillis)
                dataMap.putString(KEY_CHECK_IN_LABEL, featureState.safetyCheckIn.label)
                dataMap.putBoolean(KEY_DRILL_ACTIVE, featureState.emergencyDrillActive)
                dataMap.putLong(KEY_TIMESTAMP, System.currentTimeMillis())
            }.asPutDataRequest().setUrgent()

            Wearable.getDataClient(this).putDataItem(request).await()
            Log.d(TAG, "Safety status sent to watch")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to send safety status", e)
        }
    }

    /**
     * Send emergency contacts to watch via Data Layer.
     */
    private suspend fun sendEmergencyContactsToWatch() {
        try {
            val app = application as SafePulseApplication
            val contactRepo = EmergencyContactRepository(app.database.emergencyContactDao())
            val contacts = contactRepo.getAllContactsList()

            val contactsJson = com.google.gson.Gson().toJson(
                contacts.map {
                    mapOf(
                        "name" to it.name,
                        "phone" to it.phone,
                        "relationship" to if (it.isPrimary) "Primary" else ""
                    )
                }
            )

            val request = PutDataMapRequest.create(PATH_EMERGENCY_CONTACTS).apply {
                dataMap.putString(KEY_CONTACTS_JSON, contactsJson)
                dataMap.putLong(KEY_TIMESTAMP, System.currentTimeMillis())
            }.asPutDataRequest().setUrgent()

            Wearable.getDataClient(this).putDataItem(request).await()
            Log.d(TAG, "Emergency contacts sent to watch (${contacts.size} contacts)")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to send contacts", e)
        }
    }

    @SuppressLint("MissingPermission")
    private suspend fun sendLocationToWatch() {
        try {
            val location = getCurrentPhoneLocation() ?: return

            val request = PutDataMapRequest.create(PATH_LOCATION_DATA).apply {
                dataMap.putDouble(KEY_LATITUDE, location.latitude)
                dataMap.putDouble(KEY_LONGITUDE, location.longitude)
                dataMap.putLong(KEY_TIMESTAMP, System.currentTimeMillis())
            }.asPutDataRequest().setUrgent()

            Wearable.getDataClient(this).putDataItem(request).await()
            Log.d(TAG, "Location sent to watch")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to send location", e)
        }
    }

    @SuppressLint("MissingPermission")
    private suspend fun getCurrentPhoneLocation(): android.location.Location? {
        val fineGranted = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        val coarseGranted = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.ACCESS_COARSE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED

        if (!fineGranted && !coarseGranted) return null

        return try {
            val fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)
            val tokenSource = CancellationTokenSource()
            fusedLocationClient.getCurrentLocation(
                Priority.PRIORITY_BALANCED_POWER_ACCURACY,
                tokenSource.token
            ).await() ?: fusedLocationClient.lastLocation.await()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to read phone location", e)
            null
        }
    }

    private fun showShareLocationNotification(message: String) {
        val notificationManager =
            getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                WATCH_ACTIONS_CHANNEL,
                "Watch Actions",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Actions requested from the SafePulse watch app"
            }
            notificationManager.createNotificationChannel(channel)
        }

        val shareIntent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, message)
        }
        val chooserIntent = Intent.createChooser(shareIntent, "Share Location").apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        val pendingIntent = PendingIntent.getActivity(
            this,
            SHARE_LOCATION_NOTIFICATION_ID,
            chooserIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(this, WATCH_ACTIONS_CHANNEL)
            .setSmallIcon(android.R.drawable.ic_dialog_map)
            .setContentTitle("Share location")
            .setContentText("Requested from your watch")
            .setStyle(NotificationCompat.BigTextStyle().bigText(message))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .build()

        if (canPostNotifications()) {
            notificationManager.notify(SHARE_LOCATION_NOTIFICATION_ID, notification)
        }
    }

    private fun showShareTimelineNotification(message: String) {
        val notificationManager =
            getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                WATCH_ACTIONS_CHANNEL,
                "Watch Actions",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Actions requested from the SafePulse watch app"
            }
            notificationManager.createNotificationChannel(channel)
        }

        val shareIntent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, message)
            putExtra(Intent.EXTRA_SUBJECT, "SafePulse Emergency Timeline")
        }
        val chooserIntent = Intent.createChooser(shareIntent, "Share Emergency Timeline").apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        val pendingIntent = PendingIntent.getActivity(
            this,
            SHARE_LOCATION_NOTIFICATION_ID + 1,
            chooserIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(this, WATCH_ACTIONS_CHANNEL)
            .setSmallIcon(android.R.drawable.ic_menu_share)
            .setContentTitle("Share emergency timeline")
            .setContentText("Requested from your watch")
            .setStyle(NotificationCompat.BigTextStyle().bigText("Tap to share the SafePulse emergency timeline."))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .build()

        if (canPostNotifications()) {
            notificationManager.notify(SHARE_LOCATION_NOTIFICATION_ID + 1, notification)
        }
    }

    private fun showContactCallNotification(name: String, phone: String) {
        val notificationManager =
            getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                WATCH_ACTIONS_CHANNEL,
                "Watch Actions",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Actions requested from the SafePulse watch app"
            }
            notificationManager.createNotificationChannel(channel)
        }

        val dialIntent = Intent(Intent.ACTION_DIAL).apply {
            data = Uri.parse("tel:$phone")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        val requestCode = SHARE_LOCATION_NOTIFICATION_ID + 100 +
                kotlin.math.abs(phone.hashCode() % 10_000)
        val pendingIntent = PendingIntent.getActivity(
            this,
            requestCode,
            dialIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(this, WATCH_ACTIONS_CHANNEL)
            .setSmallIcon(android.R.drawable.sym_action_call)
            .setContentTitle("Call $name")
            .setContentText("Requested from your watch")
            .setStyle(NotificationCompat.BigTextStyle().bigText("Tap to call $name at $phone."))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .build()

        if (canPostNotifications()) {
            notificationManager.notify(requestCode, notification)
        }
    }

    private fun canPostNotifications(): Boolean {
        return android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.TIRAMISU ||
                ContextCompat.checkSelfPermission(
                    this,
                    Manifest.permission.POST_NOTIFICATIONS
                ) == PackageManager.PERMISSION_GRANTED
    }

}
