package com.safepulse.ui.journey

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Flag
import androidx.compose.material.icons.filled.Route
import androidx.compose.material.icons.filled.Timeline
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
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
import com.safepulse.domain.journey.JourneyEvent
import com.safepulse.domain.journey.JourneyPhase
import com.safepulse.domain.journey.JourneySession
import com.safepulse.domain.journey.JourneySummary
import com.safepulse.domain.transition.TransitionRiskState
import com.safepulse.ui.theme.PrimaryRed
import com.safepulse.ui.theme.SafeGreen
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun JourneyTimelineScreen(
    onBack: () -> Unit,
    viewModel: JourneyViewModel = viewModel()
) {
    val state by viewModel.state.collectAsState()
    val transitionRiskState by viewModel.transitionRiskState.collectAsState()
    val session = state.activeSession
    val latestSummary = state.latestSummary

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Timeline, contentDescription = null, tint = PrimaryRed)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Journey Timeline", fontWeight = FontWeight.Bold)
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

            item {
                when {
                    session != null -> JourneyHeader(
                        session = session,
                        onComplete = { viewModel.completeJourney() },
                        onCancel = { viewModel.cancelJourney() }
                    )
                    latestSummary != null -> JourneySummaryCard(latestSummary)
                    else -> EmptyJourneyCard()
                }
            }

            if (session != null && transitionRiskState.currentRiskScore > 0) {
                item {
                    TransitionRiskCard(transitionRiskState)
                }
            }

            item {
                Text(
                    "Event Timeline",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
            }

            if (state.events.isEmpty()) {
                item {
                    Text(
                        "No journey events recorded yet.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f)
                    )
                }
            } else {
                items(state.events, key = { it.id }) { event ->
                    JourneyEventRow(event)
                }
            }

            item { Spacer(modifier = Modifier.height(16.dp)) }
        }
    }
}

@Composable
fun TransitionRiskCard(state: TransitionRiskState) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.42f)
        )
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Warning, contentDescription = null, tint = PrimaryRed)
                Spacer(modifier = Modifier.width(10.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text("Transition Risk", fontWeight = FontWeight.SemiBold)
                    Text(
                        "Confidence ${(state.confidence * 100).toInt()}%",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f)
                    )
                }
            }

            HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))

            InfoLine("Current Zone", formatTransitionZone(state))
            InfoLine("Risk Score", state.currentRiskScore.toString())

            Spacer(modifier = Modifier.height(8.dp))
            Text(
                "Contributing Factors",
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(modifier = Modifier.height(4.dp))
            state.reasons.take(5).forEach { reason ->
                Text(
                    reason,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.78f)
                )
            }
        }
    }
}

@Composable
private fun JourneyHeader(
    session: JourneySession,
    onComplete: () -> Unit,
    onCancel: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f)
        )
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Route, contentDescription = null, tint = PrimaryRed)
                Spacer(modifier = Modifier.width(10.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text("Journey Active", fontWeight = FontWeight.SemiBold)
                    Text(
                        "Started ${formatTime(session.startTime)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f)
                    )
                }
            }

            HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))

            InfoLine("Current Phase", formatPhase(session.currentPhase))
            InfoLine("Risk Score", session.riskScore.toString())
            session.destination?.let { InfoLine("Destination", it) }

            Spacer(modifier = Modifier.height(12.dp))

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = onComplete,
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(10.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = SafeGreen)
                ) {
                    Icon(Icons.Default.CheckCircle, contentDescription = null)
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Arrived")
                }
                OutlinedButton(
                    onClick = onCancel,
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Icon(Icons.Default.Close, contentDescription = null)
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Stop")
                }
            }
        }
    }
}

@Composable
private fun JourneySummaryCard(summary: JourneySummary) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Flag, contentDescription = null, tint = SafeGreen)
                Spacer(modifier = Modifier.width(10.dp))
                Text("Journey Summary", fontWeight = FontWeight.SemiBold)
            }
            Spacer(modifier = Modifier.height(12.dp))
            InfoLine("Started", formatTime(summary.startedAt))
            InfoLine("Ended", formatTime(summary.endedAt))
            InfoLine("Duration", formatDuration(summary.durationMillis))
            InfoLine("Max Risk Score", summary.maxRiskScore.toString())
            InfoLine("SOS Triggered", if (summary.sosTriggered) "Yes" else "No")
            InfoLine("Transport Transitions", summary.transportTransitions.size.toString())
            InfoLine("Risk Events", summary.riskEvents.size.toString())
            summary.lastKnownSafeCheckpoint?.let { InfoLine("Last Safe Checkpoint", it) }
            InfoLine("Events Stored", summary.eventCount.toString())
            InfoLine("Status", summary.finalStatus.name)
            summary.destination?.let { InfoLine("Destination", it) }
        }
    }
}

@Composable
private fun EmptyJourneyCard() {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)
    ) {
        Text(
            "Start a Safe Journey from Home to build a commute timeline.",
            modifier = Modifier.padding(16.dp),
            style = MaterialTheme.typography.bodyMedium
        )
    }
}

@Composable
private fun JourneyEventRow(event: JourneyEvent) {
    Row(modifier = Modifier.fillMaxWidth()) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Box(
                modifier = Modifier
                    .size(12.dp)
                    .background(PrimaryRed, CircleShape)
            )
            Box(
                modifier = Modifier
                    .width(1.dp)
                    .height(52.dp)
                    .background(MaterialTheme.colorScheme.outline.copy(alpha = 0.3f))
            )
        }
        Spacer(modifier = Modifier.width(12.dp))
        Card(
            modifier = Modifier.weight(1f),
            shape = RoundedCornerShape(10.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
        ) {
            Column(modifier = Modifier.padding(12.dp)) {
                Text(event.eventType, fontWeight = FontWeight.SemiBold)
                Text(
                    event.description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.68f)
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    formatTime(event.timestamp),
                    style = MaterialTheme.typography.labelSmall,
                    color = Color.Gray
                )
            }
        }
    }
}

@Composable
private fun InfoLine(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(label, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f))
        Text(value, fontWeight = FontWeight.Medium)
    }
}

private fun formatPhase(phase: JourneyPhase): String {
    return phase.name.lowercase()
        .split("_")
        .joinToString(" ") { word -> word.replaceFirstChar { it.titlecase() } }
}

private fun formatTransitionZone(state: TransitionRiskState): String {
    return state.transitionZoneType.name.lowercase()
        .split("_")
        .joinToString(" ") { word -> word.replaceFirstChar { it.titlecase() } }
}

private fun formatTime(timestamp: Long): String {
    return SimpleDateFormat("h:mm a", Locale.getDefault()).format(Date(timestamp))
}

private fun formatDuration(durationMillis: Long): String {
    val totalMinutes = (durationMillis / 60_000L).coerceAtLeast(0L)
    val hours = totalMinutes / 60L
    val minutes = totalMinutes % 60L
    return if (hours > 0) "${hours}h ${minutes}m" else "${minutes}m"
}
