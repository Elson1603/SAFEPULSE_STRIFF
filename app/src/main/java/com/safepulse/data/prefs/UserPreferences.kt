package com.safepulse.data.prefs

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import com.safepulse.domain.model.Gender
import com.safepulse.utils.SafetyConstants
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "safepulse_settings")

class UserPreferences(private val context: Context) {
    
    private object PreferencesKeys {
        val GENDER = stringPreferencesKey(SafetyConstants.PREF_KEY_GENDER)
        val VOICE_TRIGGER_ENABLED = booleanPreferencesKey(SafetyConstants.PREF_KEY_VOICE_TRIGGER_ENABLED)
        val ONBOARDING_COMPLETE = booleanPreferencesKey(SafetyConstants.PREF_KEY_ONBOARDING_COMPLETE)
        val ONBOARDING_TUTORIAL_COMPLETE = booleanPreferencesKey("onboarding_tutorial_complete")
        val SERVICE_ENABLED = booleanPreferencesKey(SafetyConstants.PREF_KEY_SERVICE_ENABLED)
        val DARK_MODE = booleanPreferencesKey(SafetyConstants.PREF_KEY_DARK_MODE)
        val AUTO_START_ON_BOOT = booleanPreferencesKey(SafetyConstants.PREF_KEY_AUTO_START_ON_BOOT)
    }
    
    // Gender
    val genderFlow: Flow<Gender> = context.dataStore.data.map { prefs ->
        when (prefs[PreferencesKeys.GENDER]) {
            "MALE" -> Gender.MALE
            "FEMALE" -> Gender.FEMALE
            else -> Gender.UNSPECIFIED
        }
    }
    
    suspend fun setGender(gender: Gender) {
        context.dataStore.edit { prefs ->
            prefs[PreferencesKeys.GENDER] = gender.name
        }
    }
    
    // Voice Trigger
    val voiceTriggerEnabledFlow: Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[PreferencesKeys.VOICE_TRIGGER_ENABLED] ?: false
    }
    
    suspend fun setVoiceTriggerEnabled(enabled: Boolean) {
        context.dataStore.edit { prefs ->
            prefs[PreferencesKeys.VOICE_TRIGGER_ENABLED] = enabled
        }
    }
    
    // Onboarding Complete
    val onboardingCompleteFlow: Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[PreferencesKeys.ONBOARDING_COMPLETE] ?: false
    }
    
    suspend fun setOnboardingComplete(complete: Boolean) {
        context.dataStore.edit { prefs ->
            prefs[PreferencesKeys.ONBOARDING_COMPLETE] = complete
        }
    }
    
    // Service Enabled
    val serviceEnabledFlow: Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[PreferencesKeys.SERVICE_ENABLED] ?: false
    }
    
    suspend fun setServiceEnabled(enabled: Boolean) {
        context.dataStore.edit { prefs ->
            prefs[PreferencesKeys.SERVICE_ENABLED] = enabled
        }
    }
    
    // Dark Mode
    val darkModeEnabledFlow: Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[PreferencesKeys.DARK_MODE] ?: false
    }
    
    suspend fun setDarkModeEnabled(enabled: Boolean) {
        context.dataStore.edit { prefs ->
            prefs[PreferencesKeys.DARK_MODE] = enabled
        }
    }

    // Auto Start on Boot
    val autoStartOnBootFlow: Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[PreferencesKeys.AUTO_START_ON_BOOT] ?: true
    }

    suspend fun setAutoStartOnBoot(enabled: Boolean) {
        context.dataStore.edit { prefs ->
            prefs[PreferencesKeys.AUTO_START_ON_BOOT] = enabled
        }
    }
    
    // Onboarding Tutorial Complete
    val onboardingTutorialCompleteFlow: Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[PreferencesKeys.ONBOARDING_TUTORIAL_COMPLETE] ?: false
    }
    
    suspend fun setOnboardingTutorialComplete(complete: Boolean) {
        context.dataStore.edit { prefs ->
            prefs[PreferencesKeys.ONBOARDING_TUTORIAL_COMPLETE] = complete
        }
    }
    
    // Get all settings as UserSettings object
    val userSettingsFlow: Flow<UserSettings> = context.dataStore.data.map { prefs ->
        UserSettings(
            gender = when (prefs[PreferencesKeys.GENDER]) {
                "MALE" -> Gender.MALE
                "FEMALE" -> Gender.FEMALE
                else -> Gender.UNSPECIFIED
            },
            voiceTriggerEnabled = prefs[PreferencesKeys.VOICE_TRIGGER_ENABLED] ?: false,
            onboardingComplete = prefs[PreferencesKeys.ONBOARDING_COMPLETE] ?: false,
            onboardingTutorialComplete = prefs[PreferencesKeys.ONBOARDING_TUTORIAL_COMPLETE] ?: false,
            serviceEnabled = prefs[PreferencesKeys.SERVICE_ENABLED] ?: false,
            darkModeEnabled = prefs[PreferencesKeys.DARK_MODE] ?: false,
            autoStartOnBoot = prefs[PreferencesKeys.AUTO_START_ON_BOOT] ?: true
        )
    }
}

data class UserSettings(
    val gender: Gender,
    val voiceTriggerEnabled: Boolean,
    val onboardingComplete: Boolean,
    val onboardingTutorialComplete: Boolean,
    val serviceEnabled: Boolean,
    val darkModeEnabled: Boolean,
    val autoStartOnBoot: Boolean
)
