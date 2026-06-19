package com.safepulse.service

import com.safepulse.domain.journey.JourneyActivityUpdate
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

object JourneyActivityRecognitionBus {
    private val _updates = MutableSharedFlow<JourneyActivityUpdate>(
        extraBufferCapacity = 16
    )
    val updates: SharedFlow<JourneyActivityUpdate> = _updates.asSharedFlow()

    fun emit(update: JourneyActivityUpdate) {
        _updates.tryEmit(update)
    }
}
