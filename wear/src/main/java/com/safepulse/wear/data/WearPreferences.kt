package com.safepulse.wear.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "wear_preferences")

/**
 * Manages user preferences on the watch.
 */
class WearPreferences(private val context: Context) {

    companion object {
        private val KEY_SHAKE_SOS_ENABLED = booleanPreferencesKey("shake_sos_enabled")
        private val KEY_HEART_RATE_MONITORING = booleanPreferencesKey("heart_rate_monitoring")
        private val KEY_SERVICE_ENABLED = booleanPreferencesKey("service_enabled")
        private val KEY_BACKGROUND_VOICE_SOS_ENABLED = booleanPreferencesKey("background_voice_sos_enabled")
        private val KEY_SOS_COUNTDOWN_SECONDS = intPreferencesKey("sos_countdown_seconds")
        private val KEY_HAPTIC_FEEDBACK = booleanPreferencesKey("haptic_feedback")
        private val KEY_ALWAYS_ON_DISPLAY = booleanPreferencesKey("always_on_display")
    }

    val shakeSOSEnabledFlow: Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[KEY_SHAKE_SOS_ENABLED] ?: true
    }

    val heartRateMonitoringFlow: Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[KEY_HEART_RATE_MONITORING] ?: true
    }

    val serviceEnabledFlow: Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[KEY_SERVICE_ENABLED] ?: false
    }

    val backgroundVoiceSOSEnabledFlow: Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[KEY_BACKGROUND_VOICE_SOS_ENABLED] ?: false
    }

    val sosCountdownSecondsFlow: Flow<Int> = context.dataStore.data.map { prefs ->
        prefs[KEY_SOS_COUNTDOWN_SECONDS] ?: 5
    }

    val hapticFeedbackFlow: Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[KEY_HAPTIC_FEEDBACK] ?: true
    }

    val alwaysOnDisplayFlow: Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[KEY_ALWAYS_ON_DISPLAY] ?: false
    }

    suspend fun setShakeSOSEnabled(enabled: Boolean) {
        context.dataStore.edit { it[KEY_SHAKE_SOS_ENABLED] = enabled }
    }

    suspend fun setHeartRateMonitoring(enabled: Boolean) {
        context.dataStore.edit { it[KEY_HEART_RATE_MONITORING] = enabled }
    }

    suspend fun setServiceEnabled(enabled: Boolean) {
        context.dataStore.edit { it[KEY_SERVICE_ENABLED] = enabled }
    }

    suspend fun setBackgroundVoiceSOSEnabled(enabled: Boolean) {
        context.dataStore.edit { it[KEY_BACKGROUND_VOICE_SOS_ENABLED] = enabled }
    }

    suspend fun setSosCountdownSeconds(seconds: Int) {
        context.dataStore.edit { it[KEY_SOS_COUNTDOWN_SECONDS] = seconds }
    }

    suspend fun setHapticFeedback(enabled: Boolean) {
        context.dataStore.edit { it[KEY_HAPTIC_FEEDBACK] = enabled }
    }

    suspend fun setAlwaysOnDisplay(enabled: Boolean) {
        context.dataStore.edit { it[KEY_ALWAYS_ON_DISPLAY] = enabled }
    }
}
