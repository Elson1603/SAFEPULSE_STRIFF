package com.safepulse.wear.ui.screens

import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.wear.compose.material.*
import com.safepulse.wear.presentation.WearHomeViewModel
import com.safepulse.wear.ui.theme.DangerRed
import com.safepulse.wear.ui.theme.SafeGreen

/**
 * Full-screen SOS activation screen.
 * Shows a large pulsing SOS button with countdown and cancel option.
 * Designed for panic situations — easy to hit, hard to miss.
 */
@Composable
fun WearSOSScreen(
    viewModel: WearHomeViewModel,
    onBack: () -> Unit
) {
    val state by viewModel.state.collectAsState()

    // Pulsing animation for the SOS button
    val infiniteTransition = rememberInfiniteTransition(label = "sos_pulse")
    val scale by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = 1.1f,
        animationSpec = infiniteRepeatable(
            animation = tween(800, easing = EaseInOutCubic),
            repeatMode = RepeatMode.Reverse
        ),
        label = "scale"
    )

    val bgAlpha by infiniteTransition.animateFloat(
        initialValue = 0.0f,
        targetValue = 0.15f,
        animationSpec = infiniteRepeatable(
            animation = tween(800, easing = EaseInOutCubic),
            repeatMode = RepeatMode.Reverse
        ),
        label = "bg_alpha"
    )

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 1f - bgAlpha).compositeOver(DangerRed.copy(alpha = bgAlpha))),
        contentAlignment = Alignment.Center
    ) {
        if (state.isSosActive && state.sosCountdown > 0) {
            // ─── Countdown Mode ─────────────────
            SOSCountdownView(
                countdown = state.sosCountdown,
                onCancel = {
                    viewModel.cancelSOS()
                }
            )
        } else if (state.isSosActive && state.sosCountdown == 0) {
            // ─── SOS Sent ───────────────────────
            SOSSentView(
                success = state.lastActionSuccessful,
                message = state.lastActionMessage,
                onBack = onBack
            )
        } else {
            // ─── Ready to Trigger ───────────────
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Text(
                    text = "Tap to Send",
                    fontSize = 11.sp,
                    color = Color.White.copy(alpha = 0.6f)
                )

                Spacer(modifier = Modifier.height(8.dp))

                Button(
                    onClick = { viewModel.triggerSOS() },
                    modifier = Modifier
                        .size(120.dp)
                        .scale(scale),
                    colors = ButtonDefaults.buttonColors(
                        backgroundColor = DangerRed
                    ),
                    shape = CircleShape
                ) {
                    Text(
                        text = "SOS",
                        fontSize = 32.sp,
                        fontWeight = FontWeight.Black,
                        color = Color.White,
                        textAlign = TextAlign.Center
                    )
                }

                Spacer(modifier = Modifier.height(12.dp))

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
private fun SOSCountdownView(
    countdown: Int,
    onCancel: () -> Unit
) {
    // Urgent pulsing
    val infiniteTransition = rememberInfiniteTransition(label = "countdown_pulse")
    val pulse by infiniteTransition.animateFloat(
        initialValue = 0.8f,
        targetValue = 1.0f,
        animationSpec = infiniteRepeatable(
            animation = tween(500),
            repeatMode = RepeatMode.Reverse
        ),
        label = "countdown_scale"
    )

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
        modifier = Modifier.fillMaxSize()
    ) {
        Text(
            text = "SOS in",
            fontSize = 14.sp,
            color = Color.White.copy(alpha = 0.7f)
        )

        Spacer(modifier = Modifier.height(4.dp))

        Text(
            text = "$countdown",
            fontSize = 56.sp,
            fontWeight = FontWeight.Black,
            color = DangerRed,
            modifier = Modifier.scale(pulse)
        )

        Spacer(modifier = Modifier.height(12.dp))

        Button(
            onClick = onCancel,
            modifier = Modifier
                .fillMaxWidth(0.7f)
                .height(40.dp),
            colors = ButtonDefaults.buttonColors(
                backgroundColor = Color.DarkGray
            )
        ) {
            Text(
                text = "CANCEL",
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
                color = Color.White
            )
        }
    }
}

@Composable
private fun SOSSentView(
    success: Boolean,
    message: String,
    onBack: () -> Unit
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
        modifier = Modifier.fillMaxSize()
    ) {
        Text(
            text = if (success) "✓" else "!",
            fontSize = 40.sp,
            color = if (success) SafeGreen else DangerRed
        )

        Spacer(modifier = Modifier.height(6.dp))

        Text(
            text = if (success) "SOS Sent" else "SOS Failed",
            fontSize = 18.sp,
            fontWeight = FontWeight.Bold,
            color = if (success) SafeGreen else DangerRed
        )

        Text(
            text = message.ifBlank { if (success) "Alert sent to phone" else "Phone not connected" },
            fontSize = 11.sp,
            color = Color.White.copy(alpha = 0.6f)
        )

        Spacer(modifier = Modifier.height(12.dp))

        CompactChip(
            onClick = onBack,
            label = { Text("Done", fontSize = 12.sp) },
            colors = ChipDefaults.secondaryChipColors()
        )
    }
}
