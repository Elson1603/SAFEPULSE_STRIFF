package com.safepulse.wear.data

import android.content.Context
import android.util.Log
import com.google.android.gms.wearable.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.ConcurrentHashMap
import java.util.UUID

/**
 * Manages communication with the phone app via Wearable Data Layer API.
 * Handles sending messages (SOS, actions) and receiving data items (safety status, contacts).
 */
class PhoneCommunicationManager(context: Context) {

    companion object {
        private const val TAG = "PhoneComm"
        private const val PHONE_ACK_FRESH_MS = 30_000L
        private const val PING_TIMEOUT_MS = 2_500L
    }

    private val appContext = context.applicationContext
    private val messageClient: MessageClient = Wearable.getMessageClient(appContext)
    private val dataClient: DataClient = Wearable.getDataClient(appContext)
    private val nodeClient: NodeClient = Wearable.getNodeClient(appContext)

    private val _phoneConnected = MutableStateFlow(false)
    val phoneConnected: StateFlow<Boolean> = _phoneConnected.asStateFlow()

    private val _safetyState = MutableStateFlow(WearSafetyState())
    val safetyState: StateFlow<WearSafetyState> = _safetyState.asStateFlow()

    private val _localHeartRate = MutableStateFlow(0)
    val localHeartRate: StateFlow<Int> = _localHeartRate.asStateFlow()

    private val _emergencyContacts = MutableStateFlow<List<WearEmergencyContact>>(emptyList())
    val emergencyContacts: StateFlow<List<WearEmergencyContact>> = _emergencyContacts.asStateFlow()

    private val pendingPings = ConcurrentHashMap<String, CompletableDeferred<Boolean>>()
    @Volatile
    private var lastPhoneAckAtMillis: Long = 0L

    /**
     * Check if the SafePulse phone app is reachable, not just whether a Wear OS node exists.
     */
    suspend fun checkPhoneConnection(): Boolean {
        val nodes = getPhoneNodes()
        val connected = nodes.isNotEmpty() && pingPhone(nodes)
        _phoneConnected.value = connected
        return connected
    }

    /**
     * Get reachable phone nodes ordered by the most reliable connection first.
     */
    private suspend fun getPhoneNodes(): List<Node> {
        return try {
            nodeClient.connectedNodes.await()
                .sortedWith(compareByDescending<Node> { it.isNearby }.thenBy { it.displayName })
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get phone nodes", e)
            emptyList()
        }
    }

    /**
     * Send a message to the phone app.
     */
    private suspend fun sendMessage(
        path: String,
        data: ByteArray = ByteArray(0),
        nodes: List<Node>? = null
    ): Boolean {
        val phoneNodes = nodes ?: getPhoneNodes()
        if (phoneNodes.isEmpty()) {
            Log.w(TAG, "No phone node available for message: $path")
            _phoneConnected.value = false
            return false
        }

        var firstError: Exception? = null
        for (node in phoneNodes) {
            try {
                messageClient.sendMessage(node.id, path, data).await()
                _phoneConnected.value = true
                Log.d(TAG, "Message sent: $path to ${node.displayName}")
                return true
            } catch (e: Exception) {
                if (firstError == null) firstError = e
                Log.w(TAG, "Message failed: $path to ${node.displayName}", e)
            }
        }

        _phoneConnected.value = false
        Log.e(TAG, "Failed to send message to any phone node: $path", firstError)
        return false
    }

    /**
     * Send a watch action as both a live message and an urgent DataItem.
     * The DataItem gives the phone a durable fallback when MessageClient delivery is flaky.
     */
    private suspend fun sendCommand(path: String, payload: ByteArray = ByteArray(0)): Boolean {
        val nodes = getPhoneNodes()
        val phoneResponsive = nodes.isNotEmpty() && (hasFreshPhoneAck() || pingPhone(nodes))
        val commandId = UUID.randomUUID().toString()
        val envelope = buildCommandEnvelope(path, payload, commandId)
        val messageSent = sendMessage(path, envelope, nodes)
        val dataItemSent = putCommandDataItem(path, payload, commandId)
        if (dataItemSent && !phoneResponsive) {
            Log.w(TAG, "Command queued locally, but SafePulse phone app did not answer: $path")
        }
        return phoneResponsive && (messageSent || dataItemSent)
    }

    private suspend fun pingPhone(nodes: List<Node>): Boolean {
        if (nodes.isEmpty()) return false

        val pingId = UUID.randomUUID().toString()
        val ack = CompletableDeferred<Boolean>()
        pendingPings[pingId] = ack

        val messageSent = sendMessage(WearDataPaths.PATH_PING, pingId.toByteArray(), nodes)
        val dataItemSent = putPingDataItem(pingId)
        if (!messageSent && !dataItemSent) {
            pendingPings.remove(pingId)
            return false
        }

        val answered = withTimeoutOrNull(PING_TIMEOUT_MS) { ack.await() } == true
        pendingPings.remove(pingId)

        if (answered) {
            markPhoneResponsive()
        } else {
            _phoneConnected.value = false
            Log.w(TAG, "Phone ping timed out")
        }
        return answered
    }

    private suspend fun putPingDataItem(pingId: String): Boolean {
        return try {
            val request = PutDataMapRequest
                .create("${WearDataPaths.PATH_PING_REQUEST_PREFIX}/$pingId")
                .apply {
                    dataMap.putString(WearDataPaths.KEY_PING_ID, pingId)
                    dataMap.putLong(WearDataPaths.KEY_TIMESTAMP, System.currentTimeMillis())
                }
                .asPutDataRequest()
                .setUrgent()

            dataClient.putDataItem(request).await()
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to write ping DataItem", e)
            false
        }
    }

    private fun hasFreshPhoneAck(): Boolean {
        return System.currentTimeMillis() - lastPhoneAckAtMillis <= PHONE_ACK_FRESH_MS
    }

    private fun markPhoneResponsive() {
        lastPhoneAckAtMillis = System.currentTimeMillis()
        _phoneConnected.value = true
    }

    private fun buildCommandEnvelope(
        path: String,
        payload: ByteArray,
        commandId: String
    ): ByteArray {
        return DataMap().apply {
            putString(WearDataPaths.KEY_COMMAND_ID, commandId)
            putString(WearDataPaths.KEY_COMMAND_PATH, path)
            putByteArray(WearDataPaths.KEY_COMMAND_PAYLOAD, payload)
            putLong(WearDataPaths.KEY_TIMESTAMP, System.currentTimeMillis())
        }.toByteArray()
    }

    private suspend fun putCommandDataItem(
        path: String,
        payload: ByteArray,
        commandId: String
    ): Boolean {
        return try {
            val request = PutDataMapRequest
                .create("${WearDataPaths.PATH_COMMAND_PREFIX}/$commandId")
                .apply {
                    dataMap.putString(WearDataPaths.KEY_COMMAND_ID, commandId)
                    dataMap.putString(WearDataPaths.KEY_COMMAND_PATH, path)
                    dataMap.putByteArray(WearDataPaths.KEY_COMMAND_PAYLOAD, payload)
                    dataMap.putLong(WearDataPaths.KEY_TIMESTAMP, System.currentTimeMillis())
                }
                .asPutDataRequest()
                .setUrgent()

            dataClient.putDataItem(request).await()
            Log.d(TAG, "Command queued: $path ($commandId)")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to queue command: $path", e)
            false
        }
    }

    /**
     * Trigger SOS on the phone. Sends emergency event data.
     */
    suspend fun triggerSOS(eventType: String = "MANUAL_SOS", confidence: Float = 1.0f): Boolean {
        val data = "${eventType}|${confidence}|${System.currentTimeMillis()}".toByteArray()
        return sendCommand(WearDataPaths.PATH_SOS_TRIGGER, data)
    }

    /**
     * Cancel an active SOS.
     */
    suspend fun cancelSOS(): Boolean {
        return sendCommand(WearDataPaths.PATH_SOS_CANCEL)
    }

    /**
     * Trigger silent alert (SMS only, no call/sound).
     */
    suspend fun triggerSilentAlert(): Boolean {
        return sendCommand(WearDataPaths.PATH_SILENT_ALERT)
    }

    /**
     * Request fake call from phone.
     */
    suspend fun triggerFakeCall(): Boolean {
        return sendCommand(WearDataPaths.PATH_FAKE_CALL)
    }

    suspend fun scheduleFakeCall(delayMinutes: Int = 5): Boolean {
        return sendCommand(
            WearDataPaths.PATH_FAKE_CALL_SCHEDULE,
            delayMinutes.toString().toByteArray()
        )
    }

    /**
     * Request location sharing from phone.
     */
    suspend fun shareLocation(): Boolean {
        return sendCommand(WearDataPaths.PATH_SHARE_LOCATION)
    }

    suspend fun requestContactCall(contact: WearEmergencyContact): Boolean {
        val payload = "${contact.name}|${contact.phone}".toByteArray()
        return sendCommand(WearDataPaths.PATH_CONTACT_CALL, payload)
    }

    suspend fun toggleOfflineMode(): Boolean {
        return sendCommand(WearDataPaths.PATH_OFFLINE_MODE_TOGGLE)
    }

    suspend fun startSafetyCheckIn(delayMinutes: Int = 15): Boolean {
        return sendCommand(
            WearDataPaths.PATH_CHECK_IN_START,
            delayMinutes.toString().toByteArray()
        )
    }

    suspend fun cancelSafetyCheckIn(): Boolean {
        return sendCommand(WearDataPaths.PATH_CHECK_IN_CANCEL)
    }

    suspend fun runEmergencyDrill(): Boolean {
        return sendCommand(WearDataPaths.PATH_EMERGENCY_DRILL)
    }

    suspend fun shareEmergencyTimeline(): Boolean {
        return sendCommand(WearDataPaths.PATH_SHARE_TIMELINE)
    }

    suspend fun startTrustedJourney(durationMinutes: Int = 15): Boolean {
        val data = "Wear journey|$durationMinutes".toByteArray()
        return sendCommand(WearDataPaths.PATH_TRUSTED_JOURNEY_START, data)
    }

    suspend fun completeTrustedJourney(): Boolean {
        return sendCommand(WearDataPaths.PATH_TRUSTED_JOURNEY_COMPLETE)
    }

    /**
     * Request current safety status from phone.
     */
    suspend fun requestStatusUpdate(): Boolean {
        return sendCommand(WearDataPaths.PATH_REQUEST_STATUS)
    }

    suspend fun loadCachedPhoneData() {
        try {
            val dataItems = dataClient.dataItems.await()
            try {
                for (dataItem in dataItems) {
                    val dataMap = DataMapItem.fromDataItem(dataItem).dataMap
                    when (dataItem.uri.path) {
                        WearDataPaths.PATH_SAFETY_STATUS -> applySafetyStatus(dataMap)
                        WearDataPaths.PATH_LOCATION_DATA -> applyLocation(dataMap)
                        WearDataPaths.PATH_EMERGENCY_CONTACTS -> applyEmergencyContacts(dataMap)
                    }
                }
            } finally {
                dataItems.release()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load cached phone data", e)
        }
    }

    /**
     * Send heart rate data to phone for monitoring.
     */
    suspend fun sendHeartRate(heartRate: Int) {
        updateLocalHeartRate(heartRate)

        try {
            val request = PutDataMapRequest.create(WearDataPaths.PATH_HEART_RATE_DATA).apply {
                dataMap.putInt(WearDataPaths.KEY_HEART_RATE, heartRate)
                dataMap.putLong(WearDataPaths.KEY_TIMESTAMP, System.currentTimeMillis())
            }.asPutDataRequest().setUrgent()

            dataClient.putDataItem(request).await()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to send heart rate", e)
        }
    }

    fun updateLocalHeartRate(heartRate: Int) {
        if (heartRate <= 0) return
        _localHeartRate.value = heartRate
        _safetyState.value = _safetyState.value.copy(
            heartRate = heartRate,
            lastUpdateTimestamp = System.currentTimeMillis()
        )
    }

    /**
     * Process incoming data item changes from phone.
     */
    fun onDataChanged(dataEvents: DataEventBuffer) {
        for (event in dataEvents) {
            if (event.type == DataEvent.TYPE_CHANGED) {
                val dataItem = event.dataItem
                val path = dataItem.uri.path.orEmpty()
                val dataMap = DataMapItem.fromDataItem(dataItem).dataMap

                if (path.startsWith("${WearDataPaths.PATH_PING_RESPONSE_PREFIX}/")) {
                    handlePingResponse(
                        dataMap.getString(WearDataPaths.KEY_PING_ID, "")
                            .ifBlank { path.substringAfterLast("/") }
                            .toByteArray()
                    )
                    dataClient.deleteDataItems(dataItem.uri)
                    continue
                }

                when (path) {
                    WearDataPaths.PATH_SAFETY_STATUS -> {
                        applySafetyStatus(dataMap)
                    }

                    WearDataPaths.PATH_LOCATION_DATA -> {
                        applyLocation(dataMap)
                    }

                    WearDataPaths.PATH_EMERGENCY_CONTACTS -> {
                        applyEmergencyContacts(dataMap)
                    }
                }
            }
        }
    }

    /**
     * Process incoming message from phone.
     */
    fun onMessageReceived(path: String, data: ByteArray) {
        markPhoneResponsive()

        when (path) {
            WearDataPaths.PATH_SOS_CONFIRM -> {
                _safetyState.value = _safetyState.value.copy(isEmergency = true)
            }
            WearDataPaths.PATH_SOS_CANCEL -> {
                _safetyState.value = _safetyState.value.copy(isEmergency = false)
            }
            WearDataPaths.PATH_SAFETY_STATUS -> {
                applyMessageDataMap(data, ::applySafetyStatus)
            }
            WearDataPaths.PATH_LOCATION_DATA -> {
                applyMessageDataMap(data, ::applyLocation)
            }
            WearDataPaths.PATH_EMERGENCY_CONTACTS -> {
                applyMessageDataMap(data, ::applyEmergencyContacts)
            }
            WearDataPaths.PATH_PING -> {
                handlePingResponse(data)
            }
        }
    }

    private fun handlePingResponse(data: ByteArray) {
        val payload = data.decodeToString()
        val pingId = when {
            payload.startsWith("pong|") -> payload.removePrefix("pong|").takeIf { it.isNotBlank() }
            payload.isNotBlank() -> payload
            else -> null
        }
        if (pingId != null) {
            pendingPings.remove(pingId)?.complete(true)
        }
        markPhoneResponsive()
    }

    private fun applyMessageDataMap(
        data: ByteArray,
        apply: (DataMap) -> Unit
    ) {
        try {
            apply(DataMap.fromByteArray(data))
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse phone sync message", e)
        }
    }

    private fun applySafetyStatus(dataMap: DataMap) {
        val current = _safetyState.value
        _safetyState.value = current.copy(
            riskLevel = WearRiskLevel.fromString(
                dataMap.getString(WearDataPaths.KEY_RISK_LEVEL, "LOW")
            ),
            riskScore = dataMap.getFloat(WearDataPaths.KEY_RISK_SCORE, 0f),
            safetyMode = WearSafetyMode.fromString(
                dataMap.getString(WearDataPaths.KEY_SAFETY_MODE, "NORMAL")
            ),
            isEmergency = dataMap.getBoolean(WearDataPaths.KEY_IS_EMERGENCY, false),
            isPhoneServiceRunning = dataMap.getBoolean(WearDataPaths.KEY_SERVICE_RUNNING, false),
            isPhoneConnected = true,
            heartRate = current.heartRate,
            lastUpdateTimestamp = dataMap.getLong(
                WearDataPaths.KEY_TIMESTAMP,
                System.currentTimeMillis()
            ),
            liveTrackingActive = dataMap.getBoolean(WearDataPaths.KEY_LIVE_TRACKING_ACTIVE, false),
            liveTrackingSessionId = dataMap.getString(WearDataPaths.KEY_LIVE_TRACKING_SESSION, ""),
            pendingAckCount = dataMap.getInt(WearDataPaths.KEY_PENDING_ACK_COUNT, 0),
            helpAckCount = dataMap.getInt(WearDataPaths.KEY_HELP_ACK_COUNT, 0),
            offlineModeEnabled = dataMap.getBoolean(WearDataPaths.KEY_OFFLINE_MODE, false),
            duressModeActive = dataMap.getBoolean(WearDataPaths.KEY_DURESS_MODE, false),
            trustedJourneyActive = dataMap.getBoolean(WearDataPaths.KEY_JOURNEY_ACTIVE, false),
            trustedJourneyDestination = dataMap.getString(WearDataPaths.KEY_JOURNEY_DESTINATION, ""),
            trustedJourneyEtaMillis = dataMap.getLong(WearDataPaths.KEY_JOURNEY_ETA, 0L),
            safetyCheckInActive = dataMap.getBoolean(WearDataPaths.KEY_CHECK_IN_ACTIVE, false),
            safetyCheckInDueAtMillis = dataMap.getLong(WearDataPaths.KEY_CHECK_IN_DUE_AT, 0L),
            safetyCheckInLabel = dataMap.getString(WearDataPaths.KEY_CHECK_IN_LABEL, ""),
            emergencyDrillActive = dataMap.getBoolean(WearDataPaths.KEY_DRILL_ACTIVE, false)
        )
        _phoneConnected.value = true
    }

    private fun applyLocation(dataMap: DataMap) {
        _safetyState.value = _safetyState.value.copy(
            latitude = dataMap.getDouble(WearDataPaths.KEY_LATITUDE, 0.0),
            longitude = dataMap.getDouble(WearDataPaths.KEY_LONGITUDE, 0.0),
            lastUpdateTimestamp = dataMap.getLong(
                WearDataPaths.KEY_TIMESTAMP,
                System.currentTimeMillis()
            ),
            isPhoneConnected = true
        )
        _phoneConnected.value = true
    }

    private fun applyEmergencyContacts(dataMap: DataMap) {
        val json = dataMap.getString(WearDataPaths.KEY_CONTACTS_JSON, "[]")
        try {
            _emergencyContacts.value = parseContactsJson(json)
            _phoneConnected.value = true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse contacts", e)
        }
    }

    private fun parseContactsJson(json: String): List<WearEmergencyContact> {
        return try {
            val gson = com.google.gson.Gson()
            val type = object : com.google.gson.reflect.TypeToken<List<WearEmergencyContact>>() {}.type
            gson.fromJson(json, type)
        } catch (e: Exception) {
            emptyList()
        }
    }
}
