package com.safepulse.wear.ui.screens

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import androidx.wear.compose.material.*
import com.safepulse.wear.presentation.WearHomeViewModel
import com.safepulse.wear.ui.theme.*

/**
 * Quick actions screen providing one-tap access to:
 * - Silent Alert (SMS only, no sound)
 * - Fake Call (triggers on phone)
 * - Share Location
 */
@Composable
fun WearQuickActionsScreen(
    viewModel: WearHomeViewModel,
    onBack: () -> Unit
) {
    val listState = rememberScalingLazyListState()
    val state by viewModel.state.collectAsState()
    var pendingVoicePermissionAction by remember {
        mutableStateOf<VoicePermissionAction?>(null)
    }
    val microphonePermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        val action = pendingVoicePermissionAction
        pendingVoicePermissionAction = null

        if (granted) {
            when (action) {
                VoicePermissionAction.ListenNow -> viewModel.startWatchVoiceSOS()
                VoicePermissionAction.Background -> viewModel.toggleBackgroundVoiceSOS()
                null -> Unit
            }
        } else {
            viewModel.onVoicePermissionDenied()
        }
    }

    Scaffold(
        timeText = { TimeText() },
        vignette = { Vignette(vignettePosition = VignettePosition.TopAndBottom) },
        positionIndicator = { PositionIndicator(scalingLazyListState = listState) }
    ) {
        ScalingLazyColumn(
            modifier = Modifier.fillMaxSize(),
            state = listState,
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            // Title
            item {
                ListHeader {
                    Text(
                        text = "Quick Actions",
                        fontSize = 14.sp,
                        color = MaterialTheme.colors.primary
                    )
                }
            }

            if (state.lastActionMessage.isNotBlank()) {
                item {
                    ActionStatusChip(
                        message = state.lastActionMessage,
                        success = state.lastActionSuccessful,
                        inProgress = state.actionInProgress
                    )
                }
            }

            item {
                val voiceSubtitle = when {
                    !state.voiceSosPermissionGranted -> "Allow microphone permission"
                    !state.voiceSosAvailable -> "Speech recognition unavailable"
                    state.voiceSosListening -> state.voiceSosTranscript.ifBlank { "Listening..." }
                    state.voiceSosTranscript.isNotBlank() -> state.voiceSosTranscript
                    else -> "Say Help, Emergency, SOS"
                }

                Chip(
                    onClick = {
                        when {
                            state.voiceSosListening -> viewModel.stopWatchVoiceSOS()
                            !state.voiceSosPermissionGranted -> {
                                pendingVoicePermissionAction = VoicePermissionAction.ListenNow
                                microphonePermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                            }
                            else -> viewModel.startWatchVoiceSOS()
                        }
                    },
                    label = {
                        Text(
                            text = if (state.voiceSosListening) "Stop Listening" else "Listen Now",
                            fontSize = 13.sp
                        )
                    },
                    secondaryLabel = { Text(voiceSubtitle, fontSize = 10.sp) },
                    colors = if (state.voiceSosListening) {
                        ChipDefaults.chipColors(backgroundColor = SafeGreen.copy(alpha = 0.28f))
                    } else {
                        ChipDefaults.secondaryChipColors()
                    },
                    modifier = Modifier.fillMaxWidth(0.9f)
                )
            }

            item {
                val voiceSubtitle = when {
                    !state.voiceSosPermissionGranted -> "Allow microphone permission"
                    !state.voiceSosAvailable -> "Speech recognition unavailable"
                    state.backgroundVoiceSosEnabled -> "Listening in background"
                    else -> "Tap to arm background listening"
                }

                Chip(
                    onClick = {
                        if (!state.voiceSosPermissionGranted) {
                            pendingVoicePermissionAction = VoicePermissionAction.Background
                            microphonePermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                        } else {
                            viewModel.toggleBackgroundVoiceSOS()
                        }
                    },
                    label = {
                        Text(
                            text = if (state.backgroundVoiceSosEnabled) "Background Voice On" else "Background Voice",
                            fontSize = 13.sp
                        )
                    },
                    secondaryLabel = { Text(voiceSubtitle, fontSize = 10.sp) },
                    colors = if (state.backgroundVoiceSosEnabled) {
                        ChipDefaults.chipColors(backgroundColor = SafeGreen.copy(alpha = 0.28f))
                    } else {
                        ChipDefaults.secondaryChipColors()
                    },
                    modifier = Modifier.fillMaxWidth(0.9f)
                )
            }

            // Silent Alert
            item {
                Chip(
                    onClick = { viewModel.triggerSilentAlert() },
                    label = {
                        Text(
                            text = "Silent Alert",
                            fontSize = 13.sp
                        )
                    },
                    secondaryLabel = {
                        Text("SMS only, no sound", fontSize = 10.sp)
                    },
                    colors = ChipDefaults.chipColors(backgroundColor = WarningYellow.copy(alpha = 0.3f)),
                    modifier = Modifier.fillMaxWidth(0.9f)
                )
            }

            // Fake Call
            item {
                Chip(
                    onClick = { viewModel.triggerFakeCall() },
                    label = {
                        Text(
                            text = "Fake Call",
                            fontSize = 13.sp
                        )
                    },
                    secondaryLabel = {
                        Text("Incoming call on phone", fontSize = 10.sp)
                    },
                    colors = ChipDefaults.secondaryChipColors(),
                    modifier = Modifier.fillMaxWidth(0.9f)
                )
            }

            item {
                Chip(
                    onClick = { viewModel.scheduleFakeCall(5) },
                    label = {
                        Text(
                            text = "Schedule Call",
                            fontSize = 13.sp
                        )
                    },
                    secondaryLabel = { Text("Fake call after 5 min", fontSize = 10.sp) },
                    colors = ChipDefaults.secondaryChipColors(),
                    modifier = Modifier.fillMaxWidth(0.9f)
                )
            }

            // Share Location
            item {
                Chip(
                    onClick = { viewModel.shareLocation() },
                    label = {
                        Text(
                            text = "Share Location",
                            fontSize = 13.sp
                        )
                    },
                    secondaryLabel = {
                        Text("Send via phone", fontSize = 10.sp)
                    },
                    colors = ChipDefaults.secondaryChipColors(),
                    modifier = Modifier.fillMaxWidth(0.9f)
                )
            }

            item {
                ToggleChip(
                    checked = state.safetyState.offlineModeEnabled,
                    onCheckedChange = { viewModel.toggleOfflineMode() },
                    label = { Text("Offline Mode", fontSize = 13.sp) },
                    secondaryLabel = { Text("Cache safety data", fontSize = 10.sp) },
                    toggleControl = { Switch(checked = state.safetyState.offlineModeEnabled) },
                    modifier = Modifier.fillMaxWidth(0.9f)
                )
            }

            item {
                Chip(
                    onClick = {
                        if (state.safetyState.safetyCheckInActive) {
                            viewModel.cancelSafetyCheckIn()
                        } else {
                            viewModel.startSafetyCheckIn(15)
                        }
                    },
                    label = {
                        Text(
                            text = if (state.safetyState.safetyCheckInActive) "I Am Safe" else "Check-in Timer",
                            fontSize = 13.sp
                        )
                    },
                    secondaryLabel = {
                        Text(
                            if (state.safetyState.safetyCheckInActive) "Cancel active timer" else "Silent SOS if missed",
                            fontSize = 10.sp
                        )
                    },
                    colors = if (state.safetyState.safetyCheckInActive) {
                        ChipDefaults.chipColors(backgroundColor = WarningYellow.copy(alpha = 0.3f))
                    } else {
                        ChipDefaults.secondaryChipColors()
                    },
                    modifier = Modifier.fillMaxWidth(0.9f)
                )
            }

            item {
                Chip(
                    onClick = { viewModel.shareEmergencyTimeline() },
                    label = {
                        Text(
                            text = "Share Timeline",
                            fontSize = 13.sp
                        )
                    },
                    secondaryLabel = { Text("Open share on phone", fontSize = 10.sp) },
                    colors = ChipDefaults.secondaryChipColors(),
                    modifier = Modifier.fillMaxWidth(0.9f)
                )
            }

            item {
                Chip(
                    onClick = { viewModel.runEmergencyDrill() },
                    label = {
                        Text(
                            text = if (state.safetyState.emergencyDrillActive) "Drill Running" else "Emergency Drill",
                            fontSize = 13.sp
                        )
                    },
                    secondaryLabel = { Text("No SMS or real call", fontSize = 10.sp) },
                    colors = ChipDefaults.secondaryChipColors(),
                    modifier = Modifier.fillMaxWidth(0.9f)
                )
            }

            item {
                Chip(
                    onClick = {
                        if (state.safetyState.trustedJourneyActive) {
                            viewModel.completeTrustedJourney()
                        } else {
                            viewModel.startTrustedJourney()
                        }
                    },
                    label = {
                        Text(
                            text = if (state.safetyState.trustedJourneyActive) "Complete Journey" else "Start Journey",
                            fontSize = 13.sp
                        )
                    },
                    secondaryLabel = {
                        Text(
                            if (state.safetyState.trustedJourneyActive) "Check-in active" else "15 min check-in",
                            fontSize = 10.sp
                        )
                    },
                    colors = if (state.safetyState.trustedJourneyActive) {
                        ChipDefaults.chipColors(backgroundColor = SafeGreen.copy(alpha = 0.3f))
                    } else {
                        ChipDefaults.secondaryChipColors()
                    },
                    modifier = Modifier.fillMaxWidth(0.9f)
                )
            }

            item {
                CompactChip(
                    onClick = onBack,
                    label = { Text("Back", fontSize = 11.sp) },
                    colors = ChipDefaults.secondaryChipColors()
                )
            }
        }
    }
}

@Composable
private fun ActionStatusChip(
    message: String,
    success: Boolean,
    inProgress: Boolean
) {
    Box(
        modifier = Modifier
            .fillMaxWidth(0.9f)
            .clip(MaterialTheme.shapes.small)
            .background(if (success) SafeGreen.copy(alpha = 0.25f) else Color.Red.copy(alpha = 0.25f))
            .padding(horizontal = 10.dp, vertical = 7.dp)
    ) {
            Row(
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                if (inProgress) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(14.dp),
                        strokeWidth = 2.dp
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                }
                Text(
                    text = message,
                    fontSize = 10.sp,
                    textAlign = TextAlign.Center
                )
            }
    }
}

private enum class VoicePermissionAction {
    ListenNow,
    Background
}
