package com.safepulse.wear.data

/**
 * Constants for Wearable Data Layer API paths used for communication
 * between the phone and watch apps.
 */
object WearDataPaths {

    // Message paths (phone <-> watch)
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

    // Data item paths (synced state)
    const val PATH_SAFETY_STATUS = "/safepulse/status/safety"
    const val PATH_EMERGENCY_CONTACTS = "/safepulse/data/contacts"
    const val PATH_LOCATION_DATA = "/safepulse/data/location"
    const val PATH_HEART_RATE_DATA = "/safepulse/data/heart_rate"

    // Data item keys
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
    const val KEY_EVENT_TYPE = "event_type"
    const val KEY_CONFIDENCE = "confidence"
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
}
