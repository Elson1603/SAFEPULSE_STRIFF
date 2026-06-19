package com.safepulse.ui.journey

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Timeline
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.safepulse.domain.journey.JourneyLiveState
import com.safepulse.domain.journey.JourneyMonitoringAlert
import com.safepulse.ui.theme.PrimaryRed
import com.safepulse.ui.viewmodel.HomeViewModel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CompanionJourneyScreen(
    onBack: () -> Unit,
    viewModel: HomeViewModel = viewModel()
) {
    val state by viewModel.state.collectAsState()
    val companionState = state.companionJourneyState

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Shield, contentDescription = null, tint = PrimaryRed)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Companion Journey", fontWeight = FontWeight.Bold)
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background
                )
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item { Spacer(modifier = Modifier.height(4.dp)) }
            item { CompanionHeaderCard(companionState) }
            item { LiveStateCard(companionState, onCheckIn = { viewModel.requestJourneyCheckIn("Are you safe?") }) }
            item { JourneyAlertsCard(companionState) }
            item { TimelineCard(companionState) }
            item { ShareCard(companionState, onShare = { viewModel.shareCompanionJourney() }) }
            item { Spacer(modifier = Modifier.height(12.dp)) }
        }
    }
}

@Composable
private fun CompanionHeaderCard(state: JourneyLiveState) {
    JourneyCardSurface {
        Text("Live Journey Monitor", fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.titleMedium)
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            state.liveSession?.destination ?: "No live journey shared yet",
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
        )
        Spacer(modifier = Modifier.height(12.dp))
        MetricBlock("Journey Phase", formatPhase(state.currentPhase))
        Spacer(modifier = Modifier.height(8.dp))
        MetricBlock("Current Risk Score", state.currentRiskScore.toString())
        Spacer(modifier = Modifier.height(8.dp))
        MetricBlock("Current ETA", formatTime(state.etaMillis))
    }
}

@Composable
private fun LiveStateCard(state: JourneyLiveState, onCheckIn: () -> Unit) {
    JourneyCardSurface {
        Text("Current Status", fontWeight = FontWeight.SemiBold)
        Spacer(modifier = Modifier.height(10.dp))
        InfoRow("Current Journey Phase", formatPhase(state.currentPhase))
        InfoRow("Current Risk Score", state.currentRiskScore.toString())
        InfoRow("Current ETA", formatTime(state.etaMillis))
        InfoRow(
            "Last Known Location",
            state.lastKnownLocation?.let { "${it.latitude}, ${it.longitude}" } ?: "Waiting for location"
        )
        state.currentCheckIn?.let {
            Spacer(modifier = Modifier.height(10.dp))
            Text("Check-in active: ${it.label}", color = Color(0xFFFF9800), fontWeight = FontWeight.Medium)
        }
        Spacer(modifier = Modifier.height(12.dp))
        Button(onClick = onCheckIn, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp)) {
            Icon(Icons.Default.CheckCircle, contentDescription = null)
            Spacer(modifier = Modifier.width(8.dp))
            Text("Ask Check-In")
        }
    }
}

@Composable
private fun JourneyAlertsCard(state: JourneyLiveState) {
    JourneyCardSurface {
        Text("Proactive Alerts", fontWeight = FontWeight.SemiBold)
        Spacer(modifier = Modifier.height(12.dp))
        if (state.alerts.isEmpty()) {
            Text("No proactive alerts yet.", color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f))
        } else {
            state.alerts.takeLast(4).asReversed().forEach { alert ->
                AlertRow(alert)
                Spacer(modifier = Modifier.height(8.dp))
            }
        }
    }
}

@Composable
private fun TimelineCard(state: JourneyLiveState) {
    JourneyCardSurface {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.Timeline, contentDescription = null, tint = PrimaryRed)
            Spacer(modifier = Modifier.width(8.dp))
            Text("Journey Timeline", fontWeight = FontWeight.SemiBold)
        }
        Spacer(modifier = Modifier.height(12.dp))
        if (state.timeline.isEmpty()) {
            Text("Timeline will appear here as the journey evolves.", color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f))
        } else {
            state.timeline.takeLast(6).asReversed().forEach { event ->
                Text(
                    "${formatClock(event.timestamp)}  ${event.eventType} - ${event.description}",
                    style = MaterialTheme.typography.bodySmall
                )
                Spacer(modifier = Modifier.height(6.dp))
            }
        }
    }
}

@Composable
private fun ShareCard(state: JourneyLiveState, onShare: () -> Unit) {
    JourneyCardSurface {
        Text("Journey Sharing", fontWeight = FontWeight.SemiBold)
        Spacer(modifier = Modifier.height(10.dp))
        Text(
            state.shareToken?.token ?: "No active share token",
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
        )
        Text(
            state.shareToken?.link ?: "Share link will appear here",
            style = MaterialTheme.typography.bodySmall
        )
        Spacer(modifier = Modifier.height(12.dp))
        OutlinedButton(onClick = onShare, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp)) {
            Icon(Icons.Default.Link, contentDescription = null)
            Spacer(modifier = Modifier.width(8.dp))
            Text("Share Journey Link")
        }
    }
}

@Composable
private fun JourneyCardSurface(content: @Composable () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)
        )
    ) {
        Column(modifier = Modifier.padding(20.dp)) {
            content()
        }
    }
}

@Composable
private fun MetricBlock(label: String, value: String) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.75f)
        )
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f))
            Text(value, fontWeight = FontWeight.SemiBold)
        }
    }
}

@Composable
private fun AlertRow(alert: JourneyMonitoringAlert) {
    Row(verticalAlignment = Alignment.Top, modifier = Modifier.fillMaxWidth()) {
        Icon(Icons.Default.Warning, contentDescription = null, tint = PrimaryRed)
        Spacer(modifier = Modifier.width(8.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(alert.type, fontWeight = FontWeight.Medium)
            Text(
                alert.reason,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
            )
        }
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f))
        Text(value, fontWeight = FontWeight.Medium)
    }
}

private fun formatPhase(phase: com.safepulse.domain.journey.JourneyPhase): String {
    return when (phase) {
        com.safepulse.domain.journey.JourneyPhase.JOURNEY_STARTED -> "Started"
        com.safepulse.domain.journey.JourneyPhase.WALKING -> "Walking"
        com.safepulse.domain.journey.JourneyPhase.IN_VEHICLE -> "In Vehicle"
        com.safepulse.domain.journey.JourneyPhase.TRAIN_TRANSIT -> "Train"
        com.safepulse.domain.journey.JourneyPhase.STATION_EXIT -> "Station Exit"
        com.safepulse.domain.journey.JourneyPhase.LAST_MILE_WALK -> "Last Mile"
        com.safepulse.domain.journey.JourneyPhase.ARRIVED -> "Arrived"
        com.safepulse.domain.journey.JourneyPhase.EMERGENCY -> "Emergency"
    }
}

private fun formatTime(timestamp: Long): String {
    if (timestamp <= 0L) return "-"
    return SimpleDateFormat("h:mm a", Locale.getDefault()).format(Date(timestamp))
}

private fun formatClock(timestamp: Long): String {
    return SimpleDateFormat("h:mm a", Locale.getDefault()).format(Date(timestamp))
}
