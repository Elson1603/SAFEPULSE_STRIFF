package com.safepulse.utils

/**
 * Central configuration constants for SafePulse safety algorithms
 */
object SafetyConstants {
    
    // ============ Risk Level Thresholds ============
    const val HIGH_RISK_THRESHOLD = 0.7f
    const val MEDIUM_RISK_THRESHOLD = 0.4f
    const val LOW_RISK_THRESHOLD = 0.0f
    
    // ============ Emergency Thresholds ============
    const val EMERGENCY_CONFIDENCE_THRESHOLD = 0.75f
    const val CANCEL_WINDOW_SECONDS = 10
    const val CONFIRMATION_TIMEOUT_SECONDS = 10  // Voice confirmation timeout for shake gesture
    const val EMERGENCY_NUMBER = "112"
    
    // ============ Sensor Thresholds ============
    // Acceleration thresholds in m/s²
    const val IMPACT_ACCELERATION_THRESHOLD = 25f  // Major impact
    const val MODERATE_IMPACT_THRESHOLD = 15f      // Moderate impact
    const val FALL_ACCELERATION_THRESHOLD = 2f     // Near free-fall detection
    const val FALL_IMPACT_THRESHOLD = 20f          // Impact after free-fall
    
    // Gyroscope thresholds in rad/s
    const val VIOLENT_ROTATION_THRESHOLD = 5f
    
    // Inactivity detection
    const val INACTIVITY_TIMEOUT_MINUTES = 5
    const val MOVEMENT_VARIANCE_THRESHOLD = 0.5f
    
    // ============ Time-Based Multipliers ============
    const val DAY_MULTIPLIER = 0.7f      // 6am-6pm
    const val EVENING_MULTIPLIER = 1.0f   // 6pm-10pm
    const val NIGHT_MULTIPLIER = 1.5f     // 10pm-6am
    
    const val DAY_START_HOUR = 6
    const val EVENING_START_HOUR = 18
    const val NIGHT_START_HOUR = 22
    
    // ============ Confidence Score Weights ============
    const val WEIGHT_IMPACT = 0.40f
    const val WEIGHT_ZONE = 0.25f
    const val WEIGHT_TIME = 0.15f
    const val WEIGHT_INACTIVITY = 0.15f
    const val WEIGHT_VOICE = 0.05f
    
    // ============ Location Settings ============
    const val LOCATION_INTERVAL_NORMAL_MS = 30000L       // 30 seconds
    const val LOCATION_INTERVAL_HEIGHTENED_MS = 5000L    // 5 seconds
    const val LOCATION_FASTEST_INTERVAL_MS = 3000L       // 3 seconds minimum
    
    // Distance thresholds in meters
    const val ZONE_DETECTION_RADIUS_METERS = 100f
    const val MOVEMENT_THRESHOLD_METERS = 10f
    
    // ============ Sensor Sampling ============
    const val SENSOR_DELAY_NORMAL_US = 100000      // 100ms = 10Hz
    const val SENSOR_DELAY_HEIGHTENED_US = 50000   // 50ms = 20Hz
    
    // ============ Notification IDs ============
    const val NOTIFICATION_ID_FOREGROUND = 1001
    const val NOTIFICATION_ID_EMERGENCY = 1002
    const val NOTIFICATION_ID_RISK_ALERT = 1003
    const val NOTIFICATION_ID_EMERGENCY_CALL = 1004
    const val NOTIFICATION_ID_SAFETY_CHECK_IN = 1005
    const val NOTIFICATION_ID_TRANSITION_RISK = 1006
    
    // ============ Channel IDs ============
    const val CHANNEL_ID_SAFETY = "safety_monitoring"
    const val CHANNEL_ID_EMERGENCY = "emergency_alerts"
    
    // ============ WorkManager Tags ============
    const val WORK_TAG_SAFETY_CHECK = "safety_check_work"
    const val WORK_TAG_DATA_REFRESH = "data_refresh_work"
    
    // ============ Intent Actions ============
    const val ACTION_CANCEL_SOS = "com.safepulse.CANCEL_SOS"
    const val ACTION_STOP_SERVICE = "com.safepulse.STOP_SERVICE"
    const val ACTION_TRIGGER_SOS = "com.safepulse.TRIGGER_SOS"
    
    // ============ Preferences Keys ============
    const val PREF_KEY_GENDER = "user_gender"
    const val PREF_KEY_VOICE_TRIGGER_ENABLED = "voice_trigger_enabled"
    const val PREF_KEY_ONBOARDING_COMPLETE = "onboarding_complete"
    const val PREF_KEY_SERVICE_ENABLED = "service_enabled"
    const val PREF_KEY_DARK_MODE = "dark_mode_enabled"
    const val PREF_KEY_AUTO_START_ON_BOOT = "auto_start_on_boot"
}
