package com.safepulse.wear.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.items
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import androidx.wear.compose.material.*
import com.safepulse.wear.data.WearEmergencyContact
import com.safepulse.wear.presentation.WearHomeViewModel

/**
 * Emergency contacts screen.
 * Shows contacts synced from the phone app.
 * Contacts can be tapped to trigger phone-based contact actions.
 */
@Composable
fun WearContactsScreen(
    viewModel: WearHomeViewModel,
    onBack: () -> Unit
) {
    val state by viewModel.state.collectAsState()
    val listState = rememberScalingLazyListState()

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
                        text = "Emergency Contacts",
                        fontSize = 14.sp,
                        color = MaterialTheme.colors.primary
                    )
                }
            }

            if (state.emergencyContacts.isEmpty()) {
                item {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.padding(16.dp)
                    ) {
                        Text(
                            text = "No contacts synced",
                            fontSize = 13.sp,
                            color = Color.White.copy(alpha = 0.6f)
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "Add contacts in\nphone app",
                            fontSize = 11.sp,
                            color = Color.White.copy(alpha = 0.4f)
                        )
                    }
                }
            } else {
                items(state.emergencyContacts) { contact ->
                    ContactChip(
                        contact = contact,
                        onClick = { viewModel.requestContactCall(contact) }
                    )
                }
            }

            if (state.lastActionMessage.isNotBlank()) {
                item {
                    Text(
                        text = state.lastActionMessage,
                        fontSize = 10.sp,
                        color = if (state.lastActionSuccessful) Color(0xFF4CAF50) else Color(0xFFFF5252),
                        modifier = Modifier.padding(horizontal = 12.dp)
                    )
                }
            }

            // Refresh button
            item {
                CompactChip(
                    onClick = { viewModel.refresh() },
                    label = { Text("Refresh", fontSize = 11.sp) },
                    colors = ChipDefaults.secondaryChipColors()
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
private fun ContactChip(
    contact: WearEmergencyContact,
    onClick: () -> Unit
) {
    Chip(
        onClick = onClick,
        label = {
            Text(
                text = contact.name,
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium
            )
        },
        secondaryLabel = {
            val subtitle = buildString {
                append(contact.phone)
                if (contact.relationship.isNotBlank()) {
                    append(" • ${contact.relationship}")
                }
            }
            Text(text = subtitle, fontSize = 10.sp)
        },
        colors = ChipDefaults.secondaryChipColors(),
        modifier = Modifier.fillMaxWidth(0.9f)
    )
}
