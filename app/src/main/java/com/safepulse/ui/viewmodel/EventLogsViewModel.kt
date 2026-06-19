package com.safepulse.ui.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.safepulse.SafePulseApplication
import com.safepulse.data.db.entity.EventLogEntity
import com.safepulse.data.repository.EventLogRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class EventLogsState(
    val events: List<EventLogEntity> = emptyList(),
    val isLoading: Boolean = true
)

class EventLogsViewModel(application: Application) : AndroidViewModel(application) {
    
    private val app = application as SafePulseApplication
    private val eventLogRepository = EventLogRepository(app.database.eventLogDao())
    
    private val _state = MutableStateFlow(EventLogsState())
    val state: StateFlow<EventLogsState> = _state.asStateFlow()
    
    init {
        loadEvents()
    }
    
    private fun loadEvents() {
        viewModelScope.launch {
            eventLogRepository.getRecentEvents(100).collect { events ->
                _state.value = EventLogsState(events = events, isLoading = false)
            }
        }
    }
    
    fun deleteEvent(event: EventLogEntity) {
        viewModelScope.launch {
            eventLogRepository.delete(event)
        }
    }
    
    fun clearAllEvents() {
        viewModelScope.launch {
            val events = _state.value.events
            events.forEach { eventLogRepository.delete(it) }
        }
    }
}
