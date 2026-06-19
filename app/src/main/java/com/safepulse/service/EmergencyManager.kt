package com.safepulse.service

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.telecom.TelecomManager
import android.telephony.SmsManager
import android.util.Log
import androidx.core.app.ActivityCompat
import androidx.lifecycle.LifecycleOwner
import com.google.android.gms.maps.model.LatLng
import com.safepulse.data.db.entity.EmergencyContactEntity
import com.safepulse.domain.model.EmergencyEvent
import com.safepulse.domain.model.EventType
import com.safepulse.domain.model.LocationData
import com.safepulse.utils.NotificationHelper
import com.safepulse.utils.SafetyConstants
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

/**
 * Manages emergency response actions: SMS, Calls, event logging, and nearby services alerts
 */
class EmergencyManager(private val context: Context) {
    
    companion object {
        private const val TAG = "EmergencyManager"
        private const val CALL_DEDUPE_WINDOW_MS = 30_000L
        private var lastCallAttemptNumber: String = ""
        private var lastCallAttemptAtMillis: Long = 0L
    }
    
    private val dateFormat = SimpleDateFormat("dd MMM yyyy, HH:mm:ss", Locale.getDefault())
    
    // NearbyServicesManager will be injected when needed
    private var nearbyServicesManager: NearbyServicesManager? = null

    /**
     * Set the nearby services manager
     */
    fun setNearbyServicesManager(manager: NearbyServicesManager) {
        nearbyServicesManager = manager
    }
    
    /**
     * Send SOS SMS to all emergency contacts
     */
    fun sendSOSMessages(
        contacts: List<EmergencyContactEntity>,
        event: EmergencyEvent
    ): Boolean {
        if (!hasSMSPermission()) {
            return false
        }
        
        if (contacts.isEmpty()) {
            return false
        }
        
        val message = buildSOSMessage(event)
        var success = true
        
        for (contact in contacts) {
            try {
                sendSMS(contact.phone, message)
            } catch (e: Exception) {
                e.printStackTrace()
                success = false
            }
        }
        
        return success
    }

    fun sendJourneyStatusMessages(
        contacts: List<EmergencyContactEntity>,
        message: String
    ): Boolean {
        if (!hasSMSPermission()) {
            return false
        }

        if (contacts.isEmpty()) {
            return false
        }

        var success = true
        for (contact in contacts) {
            try {
                sendSMS(contact.phone, message)
            } catch (e: Exception) {
                e.printStackTrace()
                success = false
            }
        }

        return success
    }
    
    /**
     * Initiate emergency call
     */
    fun initiateEmergencyCall(contact: EmergencyContactEntity?): Boolean {
        val phoneNumber = contact?.phone?.takeIf { it.isNotBlank() } ?: SafetyConstants.EMERGENCY_NUMBER
        val contactName = contact?.name ?: "Emergency services"

        if (isDuplicateRecentCall(phoneNumber)) {
            Log.i(TAG, "Emergency call already requested recently for $contactName")
            return true
        }

        if (!hasCallPermission()) {
            Log.e(TAG, "Cannot start emergency call: CALL_PHONE permission is missing")
            NotificationHelper.showEmergencyCallActionNotification(
                context = context,
                phoneNumber = phoneNumber,
                contactName = contactName,
                reason = "Call permission is missing, so SafePulse could only send SMS automatically."
            )
            return false
        }

        if (!canDevicePlaceCalls()) {
            Log.e(TAG, "Cannot start emergency call: device does not report telephony support")
            NotificationHelper.showEmergencyCallActionNotification(
                context = context,
                phoneNumber = phoneNumber,
                contactName = contactName,
                reason = "This device may not support cellular calls."
            )
            return false
        }

        val callUri = Uri.fromParts("tel", phoneNumber, null)

        if (placeCallWithTelecom(callUri)) {
            recordSuccessfulCallAttempt(phoneNumber)
            Log.i(TAG, "Emergency call placed via TelecomManager to $contactName")
            return true
        }

        if (startCallActivity(callUri)) {
            recordSuccessfulCallAttempt(phoneNumber)
            Log.i(TAG, "Emergency call activity started for $contactName")
            return true
        }

        NotificationHelper.showEmergencyCallActionNotification(
            context = context,
            phoneNumber = phoneNumber,
            contactName = contactName,
            reason = "Android blocked automatic dialing while SafePulse was in the background."
        )
        return false
    }

    private fun isDuplicateRecentCall(phoneNumber: String): Boolean {
        val normalizedNumber = phoneNumber.filter { it.isDigit() || it == '+' }
            .ifBlank { phoneNumber }
        val now = System.currentTimeMillis()

        synchronized(EmergencyManager::class.java) {
            return lastCallAttemptNumber == normalizedNumber &&
                now - lastCallAttemptAtMillis < CALL_DEDUPE_WINDOW_MS
        }
    }

    private fun recordSuccessfulCallAttempt(phoneNumber: String) {
        val normalizedNumber = phoneNumber.filter { it.isDigit() || it == '+' }
            .ifBlank { phoneNumber }

        synchronized(EmergencyManager::class.java) {
            lastCallAttemptNumber = normalizedNumber
            lastCallAttemptAtMillis = System.currentTimeMillis()
        }
    }

    @SuppressLint("MissingPermission")
    private fun placeCallWithTelecom(callUri: Uri): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return false

        return try {
            val telecomManager = context.getSystemService(TelecomManager::class.java)
                ?: return false
            val extras = Bundle().apply {
                putBoolean(TelecomManager.EXTRA_START_CALL_WITH_SPEAKERPHONE, false)
            }
            telecomManager.placeCall(callUri, extras)
            true
        } catch (e: Exception) {
            Log.e(TAG, "TelecomManager.placeCall failed", e)
            false
        }
    }

    @SuppressLint("MissingPermission")
    private fun startCallActivity(callUri: Uri): Boolean {
        return try {
            val intent = Intent(Intent.ACTION_CALL).apply {
                data = callUri
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            context.startActivity(intent)
            true
        } catch (e: Exception) {
            Log.e(TAG, "ACTION_CALL activity launch failed", e)
            false
        }
    }

    private fun canDevicePlaceCalls(): Boolean {
        return context.packageManager.hasSystemFeature(PackageManager.FEATURE_TELEPHONY)
    }
    
    /**
     * Build SOS message with event details and location
     */
    private fun buildSOSMessage(event: EmergencyEvent): String {
        val eventTypeText = when (event.type) {
            EventType.ROAD_ACCIDENT -> "Road Accident"
            EventType.FALL -> "Fall Detected"
            EventType.POSSIBLE_ASSAULT -> "Possible Assault"
            EventType.INACTIVITY_ALERT -> "Inactivity Alert"
            EventType.VOICE_TRIGGER -> "Voice Emergency"
            EventType.MANUAL_SOS -> "Manual SOS"
            EventType.HIGH_RISK_ZONE -> "High Risk Alert"
        }
        
        val timeText = dateFormat.format(Date(event.timestamp))
        
        val locationText = event.location?.let { loc ->
            """
            📍 Location: ${String.format("%.6f", loc.latitude)}, ${String.format("%.6f", loc.longitude)}
            🗺️ Map: ${buildMapsLink(loc)}
            """.trimIndent()
        } ?: "📍 Location: Unknown"

        val liveTrackingText = SafetyFeatureManager.getInstance(context).buildTrackingMessage(
            event.location?.let { LatLng(it.latitude, it.longitude) }
        )
        val customInstruction = SafetyFeatureManager.getInstance(context).getSosMessageTemplate()
        
        return """
🆘 EMERGENCY ALERT from SafePulse

⚠️ Event: $eventTypeText
📊 Confidence: ${(event.confidence * 100).toInt()}%
⏰ Time: $timeText

$locationText

🔴 $liveTrackingText

⚡ $customInstruction

- Sent automatically by SafePulse Safety App
        """.trimIndent()
    }
    
    /**
     * Build Google Maps link for location
     */
    fun buildMapsLink(location: LocationData): String {
        return "https://maps.google.com/?q=${location.latitude},${location.longitude}"
    }
    
    /**
     * Send SMS using SmsManager
     */
    @Suppress("DEPRECATION")
    private fun sendSMS(phoneNumber: String, message: String) {
        val smsManager = SmsManager.getDefault()
        
        // Split message if too long
        val parts = smsManager.divideMessage(message)
        
        if (parts.size == 1) {
            smsManager.sendTextMessage(phoneNumber, null, message, null, null)
        } else {
            smsManager.sendMultipartTextMessage(phoneNumber, null, parts, null, null)
        }
    }
    
    /**
     * Check if SMS permission is granted
     */
    private fun hasSMSPermission(): Boolean {
        return ActivityCompat.checkSelfPermission(
            context,
            Manifest.permission.SEND_SMS
        ) == PackageManager.PERMISSION_GRANTED
    }
    
    /**
     * Check if call permission is granted
     */
    private fun hasCallPermission(): Boolean {
        return ActivityCompat.checkSelfPermission(
            context,
            Manifest.permission.CALL_PHONE
        ) == PackageManager.PERMISSION_GRANTED
    }
    
    /**
     * Send alert SMS to nearby emergency services
     */
    suspend fun sendAlertToNearbyServices(event: EmergencyEvent): Boolean {
        if (!hasSMSPermission()) {
            return false
        }
        
        val manager = nearbyServicesManager ?: return false
        val location = event.location ?: return false
        
        try {
            // Find nearby services based on event type
            val nearbyServices = manager.findNearbyServices(
                location = location,
                eventType = event.type,
                maxDistance = 5000f, // 5km
                maxResults = 2 // Alert top 2 nearby services
            )
            
            if (nearbyServices.isEmpty()) {
                return false
            }
            
            val message = buildEmergencyServiceMessage(event, nearbyServices.first().type)
            
            for (service in nearbyServices) {
                try {
                    sendSMS(service.phoneNumber, message)
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
            
            return true
        } catch (e: Exception) {
            e.printStackTrace()
            return false
        }
    }
    
    /**
     * Build message for emergency services (different from personal contacts)
     */
    private fun buildEmergencyServiceMessage(event: EmergencyEvent, serviceType: String): String {
        val eventTypeText = when (event.type) {
            EventType.ROAD_ACCIDENT -> "ROAD ACCIDENT"
            EventType.FALL -> "PERSON FALLEN"
            EventType.POSSIBLE_ASSAULT -> "POSSIBLE ASSAULT"
            EventType.INACTIVITY_ALERT -> "DISTRESS ALERT - No Movement"
            EventType.VOICE_TRIGGER -> "VOICE EMERGENCY"
            EventType.MANUAL_SOS -> "MANUAL SOS"
            EventType.HIGH_RISK_ZONE -> "HIGH RISK ALERT"
        }
        
        val timeText = dateFormat.format(Date(event.timestamp))
        
        val locationText = event.location?.let { loc ->
            """
            Location: ${String.format("%.6f", loc.latitude)}, ${String.format("%.6f", loc.longitude)}
            Maps: ${buildMapsLink(loc)}
            """.trimIndent()
        } ?: "Location: Unknown"
        
        return """
🚨 EMERGENCY ALERT - SafePulse App

Type: $eventTypeText
Time: $timeText
Confidence: ${(event.confidence * 100).toInt()}%

$locationText

IMMEDIATE ASSISTANCE REQUIRED
Auto-sent by SafePulse Safety System
        """.trimIndent()
    }
    
    /**
     * Triple volume button emergency - send SOS and initiate call
     */
    suspend fun triggerVolumeButtonEmergency(
        contacts: List<EmergencyContactEntity>,
        event: EmergencyEvent,
        lifecycleOwner: LifecycleOwner
    ): Boolean {
        Log.i(TAG, "")
        Log.i(TAG, "Starting volume button emergency response...")
        Log.i(TAG, "")

        // Step 1: Send immediate SMS
        Log.i(TAG, "Sending immediate SOS messages...")
        val smsSent = sendSOSMessages(contacts, event)
        Log.i(TAG, "SOS messages sent from gesture path: $smsSent")

        // Step 2: Initiate emergency call to primary contact
        val primaryContact = contacts.firstOrNull { it.isPrimary } ?: contacts.firstOrNull()
        var callStarted = false
        if (primaryContact != null) {
            Log.i(TAG, "Initiating emergency call to ${primaryContact.name}...")
            callStarted = initiateEmergencyCall(primaryContact)
        } else {
            Log.w(TAG, "No emergency contact available for emergency call")
        }

        Log.i(TAG, "Volume button emergency response completed. Call started: $callStarted")
        return smsSent || callStarted
    }
    
    /**
     * Send photo to emergency contacts via MMS
     * Opens messaging app with photo pre-attached - user taps Send
     */
    private fun sendPhotoToContacts(
        contacts: List<EmergencyContactEntity>,
        photoFile: File,
        event: EmergencyEvent
    ) {
        if (!hasSMSPermission()) {
            Log.e(TAG, "❌ No SMS permission for photo sending")
            return
        }
        
        val caption = "🚨 EMERGENCY! Photo captured at ${dateFormat.format(Date(event.timestamp))}"
        
        Log.i(TAG, "📸 Opening MMS composer for photo sending...")
        Log.i(TAG, "   Photo: ${photoFile.name} (${photoFile.length() / 1024}KB)")
        Log.i(TAG, "   Recipients: ${contacts.size} contacts")
        Log.i(TAG, "   ⚠️  User will need to tap SEND in messaging app")
        
        // Send to primary contact (first in list)
        if (contacts.isNotEmpty()) {
            val primaryContact = contacts[0]
            try {
                Log.d(TAG, "Opening MMS composer for ${primaryContact.name}...")
                sendPhotoMMS(primaryContact.phone, photoFile, caption)
                Log.i(TAG, "✅ MMS composer opened for ${primaryContact.name}")
            } catch (e: Exception) {
                Log.e(TAG, "❌ Failed to open MMS composer: ${e.message}", e)
            }
        }
    }
    
    /**
     * Open messaging app with photo pre-attached
     * User just needs to tap "Send" button once
     */
    @Suppress("DEPRECATION")
    private fun sendPhotoMMS(phoneNumber: String, photoFile: File, caption: String) {
        try {
            // Get content URI for photo
            val photoUri = androidx.core.content.FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                photoFile
            )
            
            Log.d(TAG, "📤 Opening MMS composer...")
            Log.d(TAG, "   To: $phoneNumber")
            Log.d(TAG, "   Photo: ${photoFile.name}")
            Log.d(TAG, "   Size: ${photoFile.length() / 1024}KB")
            
            // Create share intent with photo
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "image/*"
                putExtra("address", phoneNumber)
                putExtra("sms_body", caption)
                putExtra(Intent.EXTRA_STREAM, photoUri)
                putExtra(Intent.EXTRA_TEXT, caption)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            
            // Try to open default messaging app
            try {
                // First try Google Messages
                intent.setPackage("com.google.android.apps.messaging")
                context.startActivity(intent)
                Log.i(TAG, "✅ MMS composer opened (Google Messages)")
            } catch (e: Exception) {
                try {
                    // Fallback to default SMS app
                    intent.setPackage(null)
                    val chooserIntent = Intent.createChooser(intent, "Send emergency photo")
                    chooserIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    context.startActivity(chooserIntent)
                    Log.i(TAG, "✅ MMS composer opened (chooser)")
                } catch (e2: Exception) {
                    Log.e(TAG, "❌ Failed to open messaging app", e2)
                    throw e2
                }
            }
            
        } catch (e: Exception) {
            Log.e(TAG, "❌ MMS composer error: ${e.message}", e)
            throw e
        }
    }
    
    /**
     * Check if device has network connectivity (WiFi or Mobile Data)
     */
    private fun hasNetworkConnectivity(): Boolean {
        val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        
        return try {
            val network = connectivityManager.activeNetwork ?: return false
            val capabilities = connectivityManager.getNetworkCapabilities(network) ?: return false
            
            val hasConnectivity = capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
                    capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) ||
                    capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
            
            Log.d(TAG, "Network connectivity: $hasConnectivity")
            hasConnectivity
        } catch (e: Exception) {
            Log.e(TAG, "Error checking connectivity", e)
            false
        }
    }
    
    /**
     * Check if camera permission is granted
     */
    private fun hasCameraPermission(): Boolean {
        return ActivityCompat.checkSelfPermission(
            context,
            Manifest.permission.CAMERA
        ) == PackageManager.PERMISSION_GRANTED
    }
    
    /**
     * Cleanup resources
     */
    fun cleanup() {
        // No-op: reserved for future resource cleanup
    }
}

