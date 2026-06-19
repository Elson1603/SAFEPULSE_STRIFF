package com.safepulse.domain.journey

data class TransitionEvent(
    val id: Long = 0,
    val timestamp: Long,
    val transitionType: TransitionType,
    val latitude: Double,
    val longitude: Double,
    val confidence: Float,
    val description: String
)
