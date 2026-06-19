package com.safepulse.service

import android.content.Context
import android.util.Log
import com.google.android.gms.maps.model.LatLng
import com.google.android.gms.wearable.Node
import com.google.android.gms.wearable.PutDataMapRequest
import com.google.android.gms.wearable.Wearable
import com.google.gson.Gson
import com.safepulse.data.db.entity.EmergencyContactEntity
import com.safepulse.domain.model.RiskLevel
import com.safepulse.domain.model.SafetyMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext

data class WatchSyncUiState(
    val isSyncing: Boolean = false,
    val isConnected: Boolean = false,
    val nodeCount: Int = 0,
    val watchName: String = "",
    val lastSyncMillis: Long = 0L,
    val contactsSynced: Int = 0,
    val locationSynced: Boolean = false,
    val message: String = "Watch not checked"
)

class PhoneWatchSyncManager(context: Context) {

    companion object {
        private const val TAG = "PhoneWatchSync"
    }

    private val appContext = context.applicationContext
    private val nodeClient = Wearable.getNodeClient(appContext)
    private val dataClient = Wearable.getDataClient(appContext)
    private val messageClient = Wearable.getMessageClient(appContext)
    private val gson = Gson()

    suspend fun checkConnection(): WatchSyncUiState = withContext(Dispatchers.IO) {
        try {
            val nodes = getConnectedWatchNodes()
            val nearbyNode = nodes.firstOrNull { it.isNearby } ?: nodes.firstOrNull()
            if (nearbyNode == null) {
                WatchSyncUiState(
                    isConnected = false,
                    message = "No Wear OS watch connected"
                )
            } else {
                WatchSyncUiState(
                    isConnected = true,
                    nodeCount = nodes.size,
                    watchName = nearbyNode.displayName.orEmpty(),
                    message = "Watch connected"
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to check watch connection", e)
            WatchSyncUiState(
                isConnected = false,
                message = "Watch check failed"
            )
        }
    }

    suspend fun syncNow(
        riskLevel: RiskLevel,
        riskScore: Float,
        safetyMode: SafetyMode,
        serviceRunning: Boolean,
        location: LatLng?,
        contacts: List<EmergencyContactEntity>,
        featureState: SafetyFeatureState
    ): WatchSyncUiState = withContext(Dispatchers.IO) {
        val nodes = getConnectedWatchNodes()
        val nearbyNode = nodes.firstOrNull { it.isNearby } ?: nodes.firstOrNull()
            ?: return@withContext WatchSyncUiState(
                isConnected = false,
                message = "No Wear OS watch connected"
            )
        val connection = WatchSyncUiState(
            isConnected = true,
            nodeCount = nodes.size,
            watchName = nearbyNode.displayName.orEmpty(),
            message = "Watch connected"
        )

        val now = System.currentTimeMillis()
        return@withContext try {
            sendSafetyStatus(
                riskLevel = riskLevel,
                riskScore = riskScore,
                safetyMode = safetyMode,
                serviceRunning = serviceRunning,
                featureState = featureState,
                timestamp = now,
                nodes = nodes
            )
            val locationSynced = sendLocation(location, now, nodes)
            sendContacts(contacts, now, nodes)

            connection.copy(
                lastSyncMillis = now,
                contactsSynced = contacts.size,
                locationSynced = locationSynced,
                message = if (locationSynced) {
                    "Watch synced"
                } else {
                    "Watch synced, waiting for GPS"
                }
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to sync watch", e)
            connection.copy(message = "Watch sync failed")
        }
    }

    private suspend fun getConnectedWatchNodes(): List<Node> {
        return nodeClient.connectedNodes.await()
    }

    private suspend fun sendSafetyStatus(
        riskLevel: RiskLevel,
        riskScore: Float,
        safetyMode: SafetyMode,
        serviceRunning: Boolean,
        featureState: SafetyFeatureState,
        timestamp: Long,
        nodes: List<Node>
    ) {
        val request = PutDataMapRequest.create(PhoneWearListenerService.PATH_SAFETY_STATUS).apply {
            dataMap.putString(PhoneWearListenerService.KEY_RISK_LEVEL, riskLevel.name)
            dataMap.putFloat(PhoneWearListenerService.KEY_RISK_SCORE, riskScore)
            dataMap.putString(PhoneWearListenerService.KEY_SAFETY_MODE, safetyMode.name)
            dataMap.putBoolean(
                PhoneWearListenerService.KEY_IS_EMERGENCY,
                featureState.liveTrackingActive || featureState.duressModeActive
            )
            dataMap.putBoolean(PhoneWearListenerService.KEY_SERVICE_RUNNING, serviceRunning)
            dataMap.putBoolean(
                PhoneWearListenerService.KEY_LIVE_TRACKING_ACTIVE,
                featureState.liveTrackingActive
            )
            dataMap.putString(
                PhoneWearListenerService.KEY_LIVE_TRACKING_SESSION,
                featureState.liveTrackingSessionId
            )
            dataMap.putInt(
                PhoneWearListenerService.KEY_PENDING_ACK_COUNT,
                featureState.pendingAckCount
            )
            dataMap.putInt(
                PhoneWearListenerService.KEY_HELP_ACK_COUNT,
                featureState.helpedAckCount
            )
            dataMap.putBoolean(
                PhoneWearListenerService.KEY_OFFLINE_MODE,
                featureState.offlineSafetyEnabled
            )
            dataMap.putBoolean(
                PhoneWearListenerService.KEY_DURESS_MODE,
                featureState.duressModeActive
            )
            dataMap.putBoolean(
                PhoneWearListenerService.KEY_JOURNEY_ACTIVE,
                featureState.trustedJourney.active
            )
            dataMap.putString(
                PhoneWearListenerService.KEY_JOURNEY_DESTINATION,
                featureState.trustedJourney.destination
            )
            dataMap.putLong(
                PhoneWearListenerService.KEY_JOURNEY_ETA,
                featureState.trustedJourney.etaMillis
            )
            dataMap.putBoolean(
                PhoneWearListenerService.KEY_CHECK_IN_ACTIVE,
                featureState.safetyCheckIn.active
            )
            dataMap.putLong(
                PhoneWearListenerService.KEY_CHECK_IN_DUE_AT,
                featureState.safetyCheckIn.dueAtMillis
            )
            dataMap.putString(
                PhoneWearListenerService.KEY_CHECK_IN_LABEL,
                featureState.safetyCheckIn.label
            )
            dataMap.putBoolean(
                PhoneWearListenerService.KEY_DRILL_ACTIVE,
                featureState.emergencyDrillActive
            )
            dataMap.putLong(PhoneWearListenerService.KEY_TIMESTAMP, timestamp)
        }
        val payload = request.dataMap.toByteArray()

        dataClient.putDataItem(request.asPutDataRequest().setUrgent()).await()
        sendMessageToWatchNodes(
            path = PhoneWearListenerService.PATH_SAFETY_STATUS,
            payload = payload,
            nodes = nodes
        )
    }

    private suspend fun sendLocation(
        location: LatLng?,
        timestamp: Long,
        nodes: List<Node>
    ): Boolean {
        if (location == null) return false

        val request = PutDataMapRequest.create(PhoneWearListenerService.PATH_LOCATION_DATA).apply {
            dataMap.putDouble(PhoneWearListenerService.KEY_LATITUDE, location.latitude)
            dataMap.putDouble(PhoneWearListenerService.KEY_LONGITUDE, location.longitude)
            dataMap.putLong(PhoneWearListenerService.KEY_TIMESTAMP, timestamp)
        }
        val payload = request.dataMap.toByteArray()

        dataClient.putDataItem(request.asPutDataRequest().setUrgent()).await()
        sendMessageToWatchNodes(
            path = PhoneWearListenerService.PATH_LOCATION_DATA,
            payload = payload,
            nodes = nodes
        )
        return true
    }

    private suspend fun sendContacts(
        contacts: List<EmergencyContactEntity>,
        timestamp: Long,
        nodes: List<Node>
    ) {
        val contactsJson = gson.toJson(
            contacts.map { contact ->
                mapOf(
                    "name" to contact.name,
                    "phone" to contact.phone,
                    "relationship" to if (contact.isPrimary) "Primary" else ""
                )
            }
        )

        val request = PutDataMapRequest.create(PhoneWearListenerService.PATH_EMERGENCY_CONTACTS).apply {
            dataMap.putString(PhoneWearListenerService.KEY_CONTACTS_JSON, contactsJson)
            dataMap.putLong(PhoneWearListenerService.KEY_TIMESTAMP, timestamp)
        }
        val payload = request.dataMap.toByteArray()

        dataClient.putDataItem(request.asPutDataRequest().setUrgent()).await()
        sendMessageToWatchNodes(
            path = PhoneWearListenerService.PATH_EMERGENCY_CONTACTS,
            payload = payload,
            nodes = nodes
        )
    }

    private suspend fun sendMessageToWatchNodes(
        path: String,
        payload: ByteArray,
        nodes: List<Node>
    ) {
        var delivered = false
        for (node in nodes) {
            try {
                messageClient.sendMessage(node.id, path, payload).await()
                delivered = true
            } catch (e: Exception) {
                Log.w(TAG, "Message sync failed for ${node.displayName}: $path", e)
            }
        }

        if (!delivered && nodes.isNotEmpty()) {
            Log.w(TAG, "DataItem written but direct message sync failed for $path")
        }
    }
}
