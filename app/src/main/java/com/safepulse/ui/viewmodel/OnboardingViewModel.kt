package com.safepulse.ui.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.safepulse.SafePulseApplication
import com.safepulse.data.db.entity.EmergencyContactEntity
import com.safepulse.data.prefs.UserPreferences
import com.safepulse.data.repository.EmergencyContactRepository
import com.safepulse.domain.model.Gender
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class OnboardingState(
    val currentStep: Int = 0,
    val gender: Gender = Gender.UNSPECIFIED,
    val contacts: List<EmergencyContactEntity> = emptyList(),
    val voiceTriggerEnabled: Boolean = false,
    val isComplete: Boolean = false,
    val error: String? = null
)

class OnboardingViewModel(application: Application) : AndroidViewModel(application) {
    
    private val app = application as SafePulseApplication
    private val contactRepository = EmergencyContactRepository(app.database.emergencyContactDao())
    private val userPreferences = UserPreferences(application)
    
    private val _state = MutableStateFlow(OnboardingState())
    val state: StateFlow<OnboardingState> = _state.asStateFlow()
    
    init {
        loadExistingContacts()
    }
    
    private fun loadExistingContacts() {
        viewModelScope.launch {
            contactRepository.getAllContacts().collect { contacts ->
                _state.value = _state.value.copy(contacts = contacts)
            }
        }
    }
    
    fun setGender(gender: Gender) {
        viewModelScope.launch {
            userPreferences.setGender(gender)
            _state.value = _state.value.copy(gender = gender)
        }
    }
    
    fun addContact(name: String, phone: String, isPrimary: Boolean = false) {
        if (name.isBlank() || phone.isBlank()) {
            _state.value = _state.value.copy(error = "Enter both name and phone number")
            return
        }
        
        viewModelScope.launch {
            val contact = EmergencyContactEntity(
                name = name.trim(),
                phone = phone.trim(),
                isPrimary = isPrimary || _state.value.contacts.isEmpty()
            )
            contactRepository.insert(contact)
            _state.value = _state.value.copy(error = null)
        }
    }
    
    fun removeContact(contact: EmergencyContactEntity) {
        viewModelScope.launch {
            contactRepository.delete(contact)
        }
    }
    
    fun setVoiceTriggerEnabled(enabled: Boolean) {
        viewModelScope.launch {
            userPreferences.setVoiceTriggerEnabled(enabled)
            _state.value = _state.value.copy(voiceTriggerEnabled = enabled)
        }
    }
    
    fun nextStep() {
        val current = _state.value.currentStep
        when (current) {
            0 -> { // Gender step
                if (_state.value.gender == Gender.UNSPECIFIED) {
                    _state.value = _state.value.copy(error = "Select a safety profile to continue")
                    return
                }
            }
            1 -> { // Contacts step
                if (_state.value.contacts.size < 2) {
                    _state.value = _state.value.copy(error = "Add at least 2 emergency contacts to continue")
                    return
                }
            }
        }
        
        if (current < 2) {
            _state.value = _state.value.copy(currentStep = current + 1, error = null)
        } else {
            completeOnboarding()
        }
    }
    
    fun previousStep() {
        if (_state.value.currentStep > 0) {
            _state.value = _state.value.copy(
                currentStep = _state.value.currentStep - 1,
                error = null
            )
        }
    }
    
    fun completeOnboarding() {
        viewModelScope.launch {
            userPreferences.setOnboardingComplete(true)
            _state.value = _state.value.copy(isComplete = true)
        }
    }
    
    fun clearError() {
        _state.value = _state.value.copy(error = null)
    }
}
