package com.safepulse.wear.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import androidx.wear.compose.material.*
import com.safepulse.wear.data.WearPreferences
import com.safepulse.wear.service.WearSafetyService
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Settings screen for the Wear OS app.
 * Provides toggles for:
 * - Shake-to-SOS
 * - Heart rate monitoring
 * - Haptic feedback
 * - SOS countdown duration
 */
@Composable
fun WearSettingsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val preferences = remember { WearPreferences(context) }
    val scope = rememberCoroutineScope()
    val listState = rememberScalingLazyListState()

    var shakeEnabled by remember { mutableStateOf(true) }
    var backgroundVoiceEnabled by remember { mutableStateOf(false) }
    var heartRateEnabled by remember { mutableStateOf(true) }
    var hapticEnabled by remember { mutableStateOf(true) }
    var countdown by remember { mutableIntStateOf(5) }
    val microphonePermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        backgroundVoiceEnabled = granted
        scope.launch {
            preferences.setBackgroundVoiceSOSEnabled(granted)
            if (granted) {
                WearSafetyService.start(context)
                preferences.setServiceEnabled(true)
            }
        }
    }

    LaunchedEffect(Unit) {
        shakeEnabled = preferences.shakeSOSEnabledFlow.first()
        backgroundVoiceEnabled = preferences.backgroundVoiceSOSEnabledFlow.first()
        heartRateEnabled = preferences.heartRateMonitoringFlow.first()
        hapticEnabled = preferences.hapticFeedbackFlow.first()
        countdown = preferences.sosCountdownSecondsFlow.first()
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
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            item {
                ListHeader {
                    Text(
                        text = "Settings",
                        fontSize = 14.sp,
                        color = MaterialTheme.colors.primary
                    )
                }
            }

            // Shake to SOS
            item {
                ToggleChip(
                    checked = shakeEnabled,
                    onCheckedChange = {
                        shakeEnabled = it
                        scope.launch { preferences.setShakeSOSEnabled(it) }
                    },
                    label = { Text("Shake to SOS", fontSize = 13.sp) },
                    secondaryLabel = { Text("Triple shake triggers SOS", fontSize = 10.sp) },
                    toggleControl = { Switch(checked = shakeEnabled) },
                    modifier = Modifier.fillMaxWidth(0.9f)
                )
            }

            item {
                ToggleChip(
                    checked = backgroundVoiceEnabled,
                    onCheckedChange = { enabled ->
                        if (enabled && ContextCompat.checkSelfPermission(
                                context,
                                Manifest.permission.RECORD_AUDIO
                            ) != PackageManager.PERMISSION_GRANTED
                        ) {
                            microphonePermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                        } else {
                            backgroundVoiceEnabled = enabled
                            scope.launch {
                                preferences.setBackgroundVoiceSOSEnabled(enabled)
                                if (enabled) {
                                    WearSafetyService.start(context)
                                    preferences.setServiceEnabled(true)
                                }
                            }
                        }
                    },
                    label = { Text("Background Voice SOS", fontSize = 13.sp) },
                    secondaryLabel = { Text("Listens while monitoring runs", fontSize = 10.sp) },
                    toggleControl = { Switch(checked = backgroundVoiceEnabled) },
                    modifier = Modifier.fillMaxWidth(0.9f)
                )
            }

            // Heart Rate Monitoring
            item {
                ToggleChip(
                    checked = heartRateEnabled,
                    onCheckedChange = {
                        heartRateEnabled = it
                        scope.launch { preferences.setHeartRateMonitoring(it) }
                    },
                    label = { Text("Heart Rate", fontSize = 13.sp) },
                    secondaryLabel = { Text("Monitor via watch sensor", fontSize = 10.sp) },
                    toggleControl = { Switch(checked = heartRateEnabled) },
                    modifier = Modifier.fillMaxWidth(0.9f)
                )
            }

            // Haptic Feedback
            item {
                ToggleChip(
                    checked = hapticEnabled,
                    onCheckedChange = {
                        hapticEnabled = it
                        scope.launch { preferences.setHapticFeedback(it) }
                    },
                    label = { Text("Haptic Feedback", fontSize = 13.sp) },
                    secondaryLabel = { Text("Vibrate on actions", fontSize = 10.sp) },
                    toggleControl = { Switch(checked = hapticEnabled) },
                    modifier = Modifier.fillMaxWidth(0.9f)
                )
            }

            // SOS Countdown
            item {
                Chip(
                    onClick = {
                        // Cycle through 3, 5, 10 seconds
                        val next = when (countdown) {
                            3 -> 5
                            5 -> 10
                            10 -> 3
                            else -> 5
                        }
                        countdown = next
                        scope.launch { preferences.setSosCountdownSeconds(next) }
                    },
                    label = { Text("SOS Countdown", fontSize = 13.sp) },
                    secondaryLabel = { Text("${countdown} seconds (tap to change)", fontSize = 10.sp) },
                    colors = ChipDefaults.secondaryChipColors(),
                    modifier = Modifier.fillMaxWidth(0.9f)
                )
            }

            // App info
            item {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier
                        .fillMaxWidth(0.9f)
                        .padding(vertical = 8.dp)
                ) {
                    Text("SafePulse Wear", fontSize = 13.sp)
                    Text(
                        "Version 1.0",
                        fontSize = 10.sp,
                        color = MaterialTheme.colors.onBackground.copy(alpha = 0.55f)
                    )
                }
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
