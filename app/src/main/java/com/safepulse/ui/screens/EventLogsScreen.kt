package com.safepulse.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.safepulse.data.db.entity.EventLogEntity
import com.safepulse.domain.model.EventType
import com.safepulse.ui.theme.*
import com.safepulse.ui.viewmodel.EventLogsViewModel
import java.text.SimpleDateFormat
import java.util.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EventLogsScreen(
    onBack: () -> Unit,
    viewModel: EventLogsViewModel = viewModel()
) {
    val state by viewModel.state.collectAsState()
    
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Event History") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    if (state.events.isNotEmpty()) {
                        IconButton(onClick = { viewModel.clearAllEvents() }) {
                            Icon(Icons.Default.DeleteSweep, contentDescription = "Clear All")
                        }
                    }
                }
            )
        }
    ) { padding ->
        if (state.isLoading) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator(color = PrimaryRed)
            }
        } else if (state.events.isEmpty()) {
            EmptyState(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
            )
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                contentPadding = PaddingValues(vertical = 16.dp)
            ) {
                items(state.events, key = { it.id }) { event ->
                    EventCard(
                        event = event,
                        onDelete = { viewModel.deleteEvent(event) }
                    )
                }
            }
        }
    }
}

@Composable
private fun EmptyState(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            Icons.Default.EventNote,
            contentDescription = null,
            modifier = Modifier.size(80.dp),
            tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f)
        )
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = "No Events Yet",
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = "Safety events will appear here",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f)
        )
    }
}

@Composable
private fun EventCard(
    event: EventLogEntity,
    onDelete: () -> Unit
) {
    val dateFormat = remember { SimpleDateFormat("MMM dd, HH:mm", Locale.getDefault()) }
    
    val eventType = try {
        EventType.valueOf(event.type)
    } catch (e: Exception) {
        EventType.HIGH_RISK_ZONE
    }
    
    val (icon, color, label) = when (eventType) {
        EventType.ROAD_ACCIDENT -> Triple(Icons.Default.CarCrash, DangerRed, "Road Accident")
        EventType.FALL -> Triple(Icons.Default.PersonOff, RiskMedium, "Fall Detected")
        EventType.POSSIBLE_ASSAULT -> Triple(Icons.Default.Warning, DangerRed, "Possible Assault")
        EventType.INACTIVITY_ALERT -> Triple(Icons.Default.AccessTime, WarningYellow, "Inactivity Alert")
        EventType.VOICE_TRIGGER -> Triple(Icons.Default.Mic, PrimaryRed, "Voice Trigger")
        EventType.MANUAL_SOS -> Triple(Icons.Default.Sos, DangerRed, "Manual SOS")
        EventType.HIGH_RISK_ZONE -> Triple(Icons.Default.LocationOn, RiskMedium, "High Risk Zone")
    }
    
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.Top
        ) {
            // Icon
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clip(CircleShape)
                    .background(color.copy(alpha = 0.15f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    icon,
                    contentDescription = null,
                    tint = color,
                    modifier = Modifier.size(24.dp)
                )
            }
            
            Spacer(modifier = Modifier.width(16.dp))
            
            // Content
            Column(modifier = Modifier.weight(1f)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.Top
                ) {
                    Text(
                        text = label,
                        fontWeight = FontWeight.SemiBold,
                        color = color
                    )
                    IconButton(
                        onClick = onDelete,
                        modifier = Modifier.size(24.dp)
                    ) {
                        Icon(
                            Icons.Default.Close,
                            contentDescription = "Delete",
                            modifier = Modifier.size(16.dp),
                            tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f)
                        )
                    }
                }
                
                Spacer(modifier = Modifier.height(4.dp))
                
                // Details row
                Row(
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        Icons.Default.Schedule,
                        contentDescription = null,
                        modifier = Modifier.size(14.dp),
                        tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = dateFormat.format(Date(event.timestamp)),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                    )
                }
                
                Spacer(modifier = Modifier.height(4.dp))
                
                // Location
                Row(
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        Icons.Default.LocationOn,
                        contentDescription = null,
                        modifier = Modifier.size(14.dp),
                        tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = "${String.format("%.4f", event.lat)}, ${String.format("%.4f", event.lng)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                    )
                }
                
                Spacer(modifier = Modifier.height(8.dp))
                
                // Footer badges with proper wrapping
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.Start),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Confidence badge
                    Surface(
                        shape = RoundedCornerShape(6.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant
                    ) {
                        Text(
                            text = "Confidence: ${(event.confidence * 100).toInt()}%",
                            style = MaterialTheme.typography.labelSmall,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                        )
                    }
                    
                    // SOS sent indicator
                    if (event.wasSOSSent) {
                        Surface(
                            shape = RoundedCornerShape(6.dp),
                            color = DangerRed.copy(alpha = 0.15f)
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                Icon(
                                    Icons.Default.Send,
                                    contentDescription = null,
                                    modifier = Modifier.size(12.dp),
                                    tint = DangerRed
                                )
                                Text(
                                    text = "SOS Sent",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = DangerRed
                                )
                            }
                        }
                    }
                    
                    // Mode badge
                    Surface(
                        shape = RoundedCornerShape(6.dp),
                        color = if (event.mode == "HEIGHTENED") 
                            WarningYellow.copy(alpha = 0.15f) 
                        else 
                            SafeGreen.copy(alpha = 0.15f)
                    ) {
                        Text(
                            text = event.mode.uppercase(),
                            style = MaterialTheme.typography.labelSmall,
                            color = if (event.mode == "HEIGHTENED") WarningYellow else SafeGreen,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                            maxLines = 1
                        )
                    }
                }
            }
        }
    }
}
