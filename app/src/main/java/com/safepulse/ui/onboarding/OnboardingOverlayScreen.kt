package com.safepulse.ui.onboarding

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.safepulse.ui.theme.PrimaryRed
import com.safepulse.ui.theme.SafeGreen

/**
 * Voice-guided tutorial overlay that appears on top of the main navigation
 * after the initial onboarding (permissions/contacts) is completed.
 * Highlights key UI features one at a time.
 */
@Composable
fun OnboardingOverlayScreen(onComplete: () -> Unit) {
    var currentStep by remember { mutableIntStateOf(0) }

    val steps = remember {
        listOf(
            TutorialStep(
                title = "Welcome to SafePulse",
                description = "A quick guide to the controls that matter during an emergency.",
                icon = Icons.Default.Shield
            ),
            TutorialStep(
                title = "SOS Alert",
                description = "Tap SOS to notify emergency contacts with your current location and start the emergency workflow.",
                icon = Icons.Default.Warning
            ),
            TutorialStep(
                title = "Voice SOS",
                description = "Say \"Help\" or \"Emergency\" while monitoring is active to start SOS hands-free.",
                icon = Icons.Default.Mic
            ),
            TutorialStep(
                title = "Shake to SOS",
                description = "Shake your phone three times quickly to trigger alerts when you cannot use the screen.",
                icon = Icons.Default.Vibration
            ),
            TutorialStep(
                title = "Silent Alert",
                description = "Send your location by SMS discreetly without a phone call or alert sound.",
                icon = Icons.Default.VolumeOff
            ),
            TutorialStep(
                title = "Ready to Start",
                description = "SafePulse is set up. Keep monitoring enabled for background protection.",
                icon = Icons.Default.CheckCircle
            )
        )
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.85f))
            .clickable { /* consume taps */ },
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier
                .fillMaxWidth(0.88f)
                .clip(RoundedCornerShape(20.dp))
                .background(MaterialTheme.colorScheme.surface)
                .padding(28.dp)
        ) {
            val step = steps[currentStep]

            Text(
                text = "Quick guide",
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                color = PrimaryRed,
                textAlign = TextAlign.Center
            )

            Spacer(modifier = Modifier.height(16.dp))

            // Icon
            Icon(
                imageVector = step.icon,
                contentDescription = null,
                tint = if (currentStep == steps.lastIndex) SafeGreen else PrimaryRed,
                modifier = Modifier.size(56.dp)
            )

            Spacer(modifier = Modifier.height(16.dp))

            // Title
            Text(
                text = step.title,
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
                textAlign = TextAlign.Center
            )

            Spacer(modifier = Modifier.height(12.dp))

            // Description
            Text(
                text = step.description,
                fontSize = 14.sp,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                textAlign = TextAlign.Center,
                lineHeight = 20.sp
            )

            Spacer(modifier = Modifier.height(24.dp))

            Text(
                text = "Step ${currentStep + 1} of ${steps.size}",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.62f),
                textAlign = TextAlign.Center
            )

            Spacer(modifier = Modifier.height(8.dp))

            // Step dots
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                steps.forEachIndexed { index, _ ->
                    Box(
                        modifier = Modifier
                            .size(if (index == currentStep) 10.dp else 7.dp)
                            .clip(CircleShape)
                            .background(
                                if (index == currentStep) PrimaryRed
                                else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.2f)
                            )
                    )
                }
            }

            Spacer(modifier = Modifier.height(20.dp))

            // Button
            if (currentStep < steps.lastIndex) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    TextButton(onClick = onComplete) {
                        Text("Skip")
                    }
                    Button(
                        onClick = { currentStep++ },
                        colors = ButtonDefaults.buttonColors(containerColor = PrimaryRed)
                    ) {
                        Text("Next")
                        Spacer(modifier = Modifier.width(8.dp))
                        Icon(
                            imageVector = Icons.Default.ArrowForward,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
            } else {
                Button(
                    onClick = onComplete,
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(containerColor = SafeGreen)
                ) {
                    Icon(
                        imageVector = Icons.Default.Check,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Start using SafePulse", fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

private data class TutorialStep(
    val title: String,
    val description: String,
    val icon: ImageVector
)
