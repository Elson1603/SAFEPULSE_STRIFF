package com.safepulse.wear.data

/**
 * Models used in the Wear OS companion app.
 * Simplified versions of the phone app models for watch-appropriate display.
 */

enum class WearRiskLevel {
    LOW, MEDIUM, HIGH;

    companion object {
        fun fromString(value: String): WearRiskLevel {
            return try { valueOf(value.uppercase()) } catch (e: Exception) { LOW }
        }
    }
}

enum class WearSafetyMode {
    NORMAL, HEIGHTENED;

    companion object {
        fun fromString(value: String): WearSafetyMode {
            return try { valueOf(value.uppercase()) } catch (e: Exception) { NORMAL }
        }
    }
}

data class WearSafetyState(
    val riskLevel: WearRiskLevel = WearRiskLevel.LOW,
    val riskScore: Float = 0f,
    val safetyMode: WearSafetyMode = WearSafetyMode.NORMAL,
    val isEmergency: Boolean = false,
    val isPhoneServiceRunning: Boolean = false,
    val isPhoneConnected: Boolean = false,
    val heartRate: Int = 0,
    val latitude: Double = 0.0,
    val longitude: Double = 0.0,
    val lastUpdateTimestamp: Long = 0L,
    val liveTrackingActive: Boolean = false,
    val liveTrackingSessionId: String = "",
    val pendingAckCount: Int = 0,
    val helpAckCount: Int = 0,
    val offlineModeEnabled: Boolean = false,
    val duressModeActive: Boolean = false,
    val trustedJourneyActive: Boolean = false,
    val trustedJourneyDestination: String = "",
    val trustedJourneyEtaMillis: Long = 0L,
    val safetyCheckInActive: Boolean = false,
    val safetyCheckInDueAtMillis: Long = 0L,
    val safetyCheckInLabel: String = "",
    val emergencyDrillActive: Boolean = false
)

data class WearEmergencyContact(
    val name: String,
    val phone: String,
    val relationship: String = ""
)
