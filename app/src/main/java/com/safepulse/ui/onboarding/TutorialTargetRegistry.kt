package com.safepulse.ui.onboarding

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned

/**
 * Registry that tracks which tutorial target is currently highlighted.
 * Used by the onboarding overlay to spotlight specific UI elements.
 */
object TutorialTargetRegistry {
    val activeTargetId = mutableStateOf<String?>(null)

    private val targets = mutableMapOf<String, LayoutCoordinates>()

    fun register(id: String, coordinates: LayoutCoordinates) {
        targets[id] = coordinates
    }

    fun unregister(id: String) {
        targets.remove(id)
    }

    fun getCoordinates(id: String): LayoutCoordinates? = targets[id]

    fun setActiveTarget(id: String?) {
        activeTargetId.value = id
    }

    fun clear() {
        activeTargetId.value = null
        targets.clear()
    }
}

/**
 * Modifier extension that registers a composable as a tutorial target.
 * When the tutorial overlay is active, elements with matching IDs can be highlighted.
 */
fun Modifier.tutorialTarget(id: String): Modifier = this.then(
    Modifier.onGloballyPositioned { coordinates ->
        TutorialTargetRegistry.register(id, coordinates)
    }
)
