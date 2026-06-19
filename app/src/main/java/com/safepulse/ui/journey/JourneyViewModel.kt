package com.safepulse.ui.journey

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.safepulse.SafePulseApplication
import com.safepulse.domain.journey.JourneySessionState
import com.safepulse.domain.journey.JourneySessionStatus
import com.safepulse.domain.transition.TransitionRiskState
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

class JourneyViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application as SafePulseApplication
    private val journeySessionManager = app.journeySessionManager

    val state: StateFlow<JourneySessionState> = journeySessionManager.state
    val transitionRiskState: StateFlow<TransitionRiskState> = app.transitionRiskEngine.state

    fun completeJourney() {
        viewModelScope.launch {
            journeySessionManager.endJourney(JourneySessionStatus.COMPLETED)
        }
    }

    fun cancelJourney() {
        viewModelScope.launch {
            journeySessionManager.endJourney(JourneySessionStatus.CANCELLED)
        }
    }
}
