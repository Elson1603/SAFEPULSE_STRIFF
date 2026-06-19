package com.safepulse.ui.screens

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.safepulse.domain.journey.JourneyPhase
import com.safepulse.domain.journey.JourneySessionState
import com.safepulse.domain.model.RiskLevel
import com.safepulse.domain.model.SafetyMode
import com.safepulse.service.SafetyFeatureState
import com.safepulse.service.WatchSyncUiState
import com.safepulse.ui.components.LiveMapCard
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import com.safepulse.ui.onboarding.TutorialTargetRegistry
import com.safepulse.ui.onboarding.tutorialTarget
import com.safepulse.ui.theme.*
import com.safepulse.ui.viewmodel.HomeViewModel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class, androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun HomeScreen(
    onNavigateToLogs: () -> Unit,
    onNavigateToSettings: () -> Unit,
    onNavigateToSafeRoutes: () -> Unit = {},
    onNavigateToRiskMap: () -> Unit = {},
    onNavigateToEventLogs: () -> Unit = {},
    onNavigateToFullMap: () -> Unit = {},
    onNavigateToNearbySafety: () -> Unit = {},
    onNavigateToAdvancedSafety: () -> Unit = {},
    onNavigateToJourneyTimeline: () -> Unit = {},
    onNavigateToCompanionJourney: () -> Unit = {},
    onOpenMenu: () -> Unit = {},
    viewModel: HomeViewModel = viewModel()
) {
    val state by viewModel.state.collectAsState()
    
    // Auto-scroll for tutorial
    val requesters = remember {
        mapOf(
            "main_sos_button" to BringIntoViewRequester(),
            "voice_trigger_card" to BringIntoViewRequester(),
            "risk_map_card" to BringIntoViewRequester(),
            "safe_routes_card" to BringIntoViewRequester()
        )
    }
    
    val activeTargetId = TutorialTargetRegistry.activeTargetId.value
    LaunchedEffect(activeTargetId) {
        activeTargetId?.let { id ->
            requesters[id]?.bringIntoView()
        }
    }
    
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Default.Shield,
                            contentDescription = null,
                            tint = PrimaryRed,
                            modifier = Modifier.size(28.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            "SafePulse",
                            fontWeight = FontWeight.Bold
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onOpenMenu) {
                        Icon(Icons.Default.Menu, contentDescription = "Open navigation menu")
                    }
                },
                actions = {
                    IconButton(onClick = onNavigateToLogs) {
                        Icon(Icons.Default.History, contentDescription = "Event Logs")
                    }
                    IconButton(onClick = onNavigateToSettings) {
                        Icon(Icons.Default.Settings, contentDescription = "Settings")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background
                )
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(modifier = Modifier.height(16.dp))
            
            // Status badges row
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                RiskBadge(
                    riskLevel = state.riskLevel,
                    modifier = Modifier.weight(1f)
                )
                ModeBadge(
                    mode = state.safetyMode,
                    modifier = Modifier.weight(1f)
                )
            }
            
            Spacer(modifier = Modifier.height(16.dp))
            
            // Live Map
            LiveMapCard(
                currentLocation = state.currentLocation,
                crimeHotspots = state.crimeHotspots,
                disasters = state.disasters,
                onLocationUpdate = { viewModel.updateLocation(it) },
                modifier = Modifier.fillMaxWidth()
            )
            
            Spacer(modifier = Modifier.height(20.dp))
            
            // Main SOS button
            SOSButton(
                isActive = state.isServiceRunning,
                onClick = { viewModel.toggleService() },
                modifier = Modifier
                    .tutorialTarget("main_sos_button")
                    .bringIntoViewRequester(requesters["main_sos_button"]!!)
            )
            
            Spacer(modifier = Modifier.height(12.dp))
            
            Text(
                text = if (state.isServiceRunning) 
                    "Protection Active" 
                else 
                    "Tap to Start Protection",
                style = MaterialTheme.typography.titleMedium,
                color = if (state.isServiceRunning) SafeGreen else MaterialTheme.colorScheme.onBackground.copy(alpha = 0.7f)
            )
            
            Spacer(modifier = Modifier.height(24.dp))
            
            // Voice Trigger section
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .tutorialTarget("voice_trigger_card")
                    .bringIntoViewRequester(requesters["voice_trigger_card"]!!),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                )
            ) {
                Column(modifier = Modifier.padding(20.dp)) {
                    Text(
                        "Voice Trigger",
                        fontWeight = FontWeight.SemiBold,
                        style = MaterialTheme.typography.titleMedium,
                        color = PrimaryRed
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            Icons.Default.Mic,
                            contentDescription = null,
                            tint = if (state.voiceTriggerEnabled) SafeGreen else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f),
                            modifier = Modifier.size(28.dp)
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Voice Emergency", fontWeight = FontWeight.Medium)
                            Text(
                                "Say \"Help\" or \"Emergency\" to trigger SOS",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                            )
                        }
                        Switch(
                            checked = state.voiceTriggerEnabled,
                            onCheckedChange = { viewModel.setVoiceTriggerEnabled(it) },
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = Color.White,
                                checkedTrackColor = SafeGreen
                            )
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            WatchSyncCard(
                syncState = state.watchSync,
                onCheckWatch = { viewModel.refreshWatchConnection() },
                onSyncNow = { viewModel.syncWatchNow() }
            )

            Spacer(modifier = Modifier.height(16.dp))

            UnifiedJourneyCard(
                journeyState = state.journeyState,
                onStart = { viewModel.startSafeJourney(it) },
                onShareJourney = { viewModel.shareActiveSafeJourney() },
                onComplete = { viewModel.completeSafeJourney() },
                onCancel = { viewModel.cancelSafeJourney() },
                onOpenTimeline = onNavigateToJourneyTimeline,
                onOpenCompanionJourney = onNavigateToCompanionJourney,
                onOpenSafeRoutes = onNavigateToSafeRoutes
            )

            Spacer(modifier = Modifier.height(16.dp))

            AdvancedSafetyEntryCard(
                features = state.safetyFeatures,
                onOpen = onNavigateToAdvancedSafety
            )

            Spacer(modifier = Modifier.height(16.dp))

            // Navigation cards: Risk Map & Safe Routes
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Card(
                    modifier = Modifier
                        .weight(1f)
                        .clickable { onNavigateToRiskMap() }
                        .tutorialTarget("risk_map_card")
                        .bringIntoViewRequester(requesters["risk_map_card"]!!),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = Color(0xFFFFF3E0)
                    )
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Icon(
                            Icons.Default.Map,
                            contentDescription = "Risk Map",
                            tint = Color(0xFFE65100),
                            modifier = Modifier.size(32.dp)
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            "Risk Map",
                            fontWeight = FontWeight.Bold,
                            fontSize = 14.sp,
                            color = Color(0xFFE65100)
                        )
                        Text(
                            "Crime & Disaster Zones",
                            fontSize = 10.sp,
                            color = Color(0xFFE65100).copy(alpha = 0.7f),
                            textAlign = TextAlign.Center
                        )
                    }
                }
                Card(
                    modifier = Modifier
                        .weight(1f)
                        .clickable { onNavigateToSafeRoutes() }
                        .tutorialTarget("safe_routes_card")
                        .bringIntoViewRequester(requesters["safe_routes_card"]!!),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = Color(0xFFE8F5E9)
                    )
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Icon(
                            Icons.Default.Directions,
                            contentDescription = "Safe Routes",
                            tint = Color(0xFF2E7D32),
                            modifier = Modifier.size(32.dp)
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            "Safe Routes",
                            fontWeight = FontWeight.Bold,
                            fontSize = 14.sp,
                            color = Color(0xFF2E7D32)
                        )
                        Text(
                            "Find Safest Path",
                            fontSize = 10.sp,
                            color = Color(0xFF2E7D32).copy(alpha = 0.7f),
                            textAlign = TextAlign.Center
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Interactive Map card (full-screen Leaflet map)
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onNavigateToFullMap() },
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(
                    containerColor = Color(0xFFE3F2FD)
                )
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        Icons.Default.Explore,
                        contentDescription = "Interactive Map",
                        tint = Color(0xFF1565C0),
                        modifier = Modifier.size(32.dp)
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            "Interactive Map",
                            fontWeight = FontWeight.Bold,
                            fontSize = 14.sp,
                            color = Color(0xFF1565C0)
                        )
                        Text(
                            "Add markers, draw routes, explore",
                            fontSize = 11.sp,
                            color = Color(0xFF1565C0).copy(alpha = 0.7f)
                        )
                    }
                    Icon(
                        Icons.Default.ChevronRight,
                        contentDescription = null,
                        tint = Color(0xFF1565C0).copy(alpha = 0.5f)
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Find Nearby Safety card
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onNavigateToNearbySafety() },
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(
                    containerColor = Color(0xFFE8EAF6)
                )
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        Icons.Default.LocalPolice,
                        contentDescription = "Find Nearby Safety",
                        tint = Color(0xFF283593),
                        modifier = Modifier.size(32.dp)
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            "Find Nearby Safety",
                            fontWeight = FontWeight.Bold,
                            fontSize = 14.sp,
                            color = Color(0xFF283593)
                        )
                        Text(
                            "Police, Hospitals, Safe Zones & Routes",
                            fontSize = 11.sp,
                            color = Color(0xFF283593).copy(alpha = 0.7f)
                        )
                    }
                    Icon(
                        Icons.Default.ChevronRight,
                        contentDescription = null,
                        tint = Color(0xFF283593).copy(alpha = 0.5f)
                    )
                }
            }

            // Footer info
            if (state.isServiceRunning) {
                Spacer(modifier = Modifier.height(16.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center
                ) {
                    val pulseAnimation = rememberInfiniteTransition(label = "pulse")
                    val alpha by pulseAnimation.animateFloat(
                        initialValue = 0.3f,
                        targetValue = 1f,
                        animationSpec = infiniteRepeatable(
                            animation = tween(1000),
                            repeatMode = RepeatMode.Reverse
                        ),
                        label = "pulseAlpha"
                    )
                    
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .clip(CircleShape)
                            .background(SafeGreen.copy(alpha = alpha))
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Monitoring in background",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f)
                    )
                }
            }
            
            // Bottom padding for scroll
            Spacer(modifier = Modifier.height(24.dp))
        }
    }
}

@Composable
private fun RiskBadge(riskLevel: RiskLevel, modifier: Modifier = Modifier) {
    val color by animateColorAsState(
        targetValue = when (riskLevel) {
            RiskLevel.LOW -> RiskLow
            RiskLevel.MEDIUM -> RiskMedium
            RiskLevel.HIGH -> RiskHigh
        },
        label = "riskColor"
    )
    
    Card(
        modifier = modifier,
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = color.copy(alpha = 0.15f)
        )
    ) {
        Row(
            modifier = Modifier
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(color)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Column {
                Text(
                    text = "Risk Level",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                )
                Text(
                    text = riskLevel.name,
                    fontWeight = FontWeight.Bold,
                    color = color
                )
            }
        }
    }
}

@Composable
private fun ModeBadge(mode: SafetyMode, modifier: Modifier = Modifier) {
    val isHeightened = mode == SafetyMode.HEIGHTENED
    val color = if (isHeightened) WarningYellow else SafeGreen
    
    Card(
        modifier = modifier,
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = color.copy(alpha = 0.15f)
        )
    ) {
        Row(
            modifier = Modifier
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                if (isHeightened) Icons.Default.ShieldMoon else Icons.Default.Shield,
                contentDescription = null,
                tint = color,
                modifier = Modifier.size(16.dp)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Column {
                Text(
                    text = "Mode",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                )
                Text(
                    text = mode.name,
                    fontWeight = FontWeight.Bold,
                    color = color
                )
            }
        }
    }
}

@Composable
private fun SOSButton(
    isActive: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val infiniteTransition = rememberInfiniteTransition(label = "sos")
    val scale by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = if (isActive) 1.05f else 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(1000),
            repeatMode = RepeatMode.Reverse
        ),
        label = "sosScale"
    )
    
    val glowAlpha by infiniteTransition.animateFloat(
        initialValue = 0.2f,
        targetValue = if (isActive) 0.5f else 0.2f,
        animationSpec = infiniteRepeatable(
            animation = tween(1000),
            repeatMode = RepeatMode.Reverse
        ),
        label = "glowAlpha"
    )
    
    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
    ) {
        // Outer glow
        if (isActive) {
            Box(
                modifier = Modifier
                    .size(200.dp)
                    .scale(scale * 1.1f)
                    .clip(CircleShape)
                    .background(
                        Brush.radialGradient(
                            colors = listOf(
                                SafeGreen.copy(alpha = glowAlpha),
                                Color.Transparent
                            )
                        )
                    )
            )
        }
        
        // Main button
        Box(
            modifier = Modifier
                .size(180.dp)
                .scale(scale)
                .clip(CircleShape)
                .background(
                    Brush.radialGradient(
                        colors = if (isActive) listOf(
                            SafeGreen,
                            SafeGreen.copy(alpha = 0.7f)
                        ) else listOf(
                            PrimaryRed,
                            PrimaryRedDark
                        )
                    )
                )
                .clickable(onClick = onClick)
                .border(
                    width = 4.dp,
                    color = if (isActive) SafeGreen.copy(alpha = 0.5f) else PrimaryRed.copy(alpha = 0.5f),
                    shape = CircleShape
                ),
            contentAlignment = Alignment.Center
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Icon(
                    if (isActive) Icons.Default.Shield else Icons.Default.PowerSettingsNew,
                    contentDescription = null,
                    modifier = Modifier.size(48.dp),
                    tint = Color.White
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = if (isActive) "ACTIVE" else "START",
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                    fontSize = 18.sp
                )
            }
        }
    }
}

@Composable
private fun StatCard(
    title: String,
    value: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier,
        shape = RoundedCornerShape(12.dp)
    ) {
        Column(
            modifier = Modifier
                .padding(16.dp)
                .fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Icon(
                icon,
                contentDescription = null,
                tint = PrimaryRed.copy(alpha = 0.7f),
                modifier = Modifier.size(24.dp)
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = value,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = title,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
            )
        }
    }
}

@Composable
private fun WatchSyncCard(
    syncState: WatchSyncUiState,
    onCheckWatch: () -> Unit,
    onSyncNow: () -> Unit
) {
    val statusColor = if (syncState.isConnected) SafeGreen else MaterialTheme.colorScheme.error
    val statusText = if (syncState.watchName.isNotBlank()) {
        "${syncState.message}: ${syncState.watchName}"
    } else {
        syncState.message
    }
    val syncMeta = buildString {
        append("Contacts: ${syncState.contactsSynced}")
        append(" | GPS: ${if (syncState.locationSynced) "Synced" else "Waiting"}")
        append(" | ${formatLastWatchSync(syncState.lastSyncMillis)}")
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)
        )
    ) {
        Column(modifier = Modifier.padding(20.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    Icons.Default.Sync,
                    contentDescription = null,
                    tint = statusColor,
                    modifier = Modifier.size(28.dp)
                )
                Spacer(modifier = Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        "Watch Sync",
                        fontWeight = FontWeight.SemiBold,
                        style = MaterialTheme.typography.titleMedium
                    )
                    Text(
                        statusText,
                        style = MaterialTheme.typography.bodySmall,
                        color = statusColor
                    )
                }
                if (syncState.isSyncing) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        strokeWidth = 2.dp
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            Text(
                syncMeta,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f)
            )

            Spacer(modifier = Modifier.height(12.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedButton(
                    onClick = onCheckWatch,
                    enabled = !syncState.isSyncing,
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Icon(Icons.Default.BluetoothSearching, contentDescription = null)
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Check")
                }
                Button(
                    onClick = onSyncNow,
                    enabled = syncState.isConnected && !syncState.isSyncing,
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = PrimaryRed)
                ) {
                    Icon(Icons.Default.CloudSync, contentDescription = null)
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Sync Now")
                }
            }
        }
    }
}

private fun formatLastWatchSync(timestamp: Long): String {
    if (timestamp <= 0L) return "Not synced"
    val minutes = ((System.currentTimeMillis() - timestamp) / 60_000L).coerceAtLeast(0L)
    return when {
        minutes == 0L -> "Synced just now"
        minutes == 1L -> "Synced 1 min ago"
        else -> "Synced $minutes min ago"
    }
}

@Composable
private fun UnifiedJourneyCard(
    journeyState: JourneySessionState,
    onStart: (String?) -> Unit,
    onShareJourney: () -> Unit,
    onComplete: () -> Unit,
    onCancel: () -> Unit,
    onOpenTimeline: () -> Unit,
    onOpenCompanionJourney: () -> Unit,
    onOpenSafeRoutes: () -> Unit
) {
    var destination by remember { mutableStateOf("") }
    val session = journeyState.activeSession

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)
        )
    ) {
        Column(modifier = Modifier.padding(20.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    Icons.Default.Route,
                    contentDescription = null,
                    tint = if (session != null) SafeGreen else PrimaryRed,
                    modifier = Modifier.size(30.dp)
                )
                Spacer(modifier = Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        "Unified Commute Safety",
                        fontWeight = FontWeight.SemiBold,
                        style = MaterialTheme.typography.titleMedium
                    )
                    Text(
                        if (session != null) {
                            "${formatJourneyPhase(session.currentPhase)} - Risk ${session.riskScore}"
                        } else {
                            "One session across walking, train, auto, cab"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f)
                    )
                }
                IconButton(onClick = onOpenTimeline) {
                    Icon(Icons.Default.Timeline, contentDescription = "Open Journey Timeline")
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            if (session == null) {
                OutlinedTextField(
                    value = destination,
                    onValueChange = { destination = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Destination optional") },
                    singleLine = true
                )
                Spacer(modifier = Modifier.height(10.dp))
                Button(
                    onClick = {
                        onStart(destination.takeIf { it.isNotBlank() })
                        destination = ""
                    },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = PrimaryRed)
                ) {
                    Icon(Icons.Default.PlayArrow, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Start Safe Journey")
                }
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedButton(
                    onClick = onOpenSafeRoutes,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Icon(Icons.Default.Map, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Plan with Safe Routes")
                }
                journeyState.latestSummary?.let { summary ->
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        "Last journey: ${summary.eventCount} events - max risk ${summary.maxRiskScore}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f)
                    )
                }
            } else {
                InfoStatusRow("Started", formatJourneyTimestamp(session.startTime))
                InfoStatusRow("Phase", formatJourneyPhase(session.currentPhase))
                InfoStatusRow("Risk Score", session.riskScore.toString())
                session.destination?.let { InfoStatusRow("Destination", it) }

                Spacer(modifier = Modifier.height(10.dp))

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = onOpenTimeline,
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = PrimaryRed)
                    ) {
                        Icon(Icons.Default.Timeline, contentDescription = null)
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Timeline")
                    }
                    OutlinedButton(
                        onClick = onComplete,
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Icon(Icons.Default.CheckCircle, contentDescription = null)
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Arrived")
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                Button(
                    onClick = onShareJourney,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = SafeGreen)
                ) {
                    Icon(Icons.Default.ShareLocation, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Share Journey")
                }

                Spacer(modifier = Modifier.height(8.dp))

                OutlinedButton(
                    onClick = onOpenCompanionJourney,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Icon(Icons.Default.People, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Open Companion Dashboard")
                }

                Spacer(modifier = Modifier.height(8.dp))

                OutlinedButton(
                    onClick = onOpenSafeRoutes,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Icon(Icons.Default.Map, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Open Safe Routes")
                }

                Spacer(modifier = Modifier.height(8.dp))

                OutlinedButton(
                    onClick = onCancel,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Icon(Icons.Default.Close, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Stop Journey")
                }
            }
        }
    }
}

@Composable
private fun InfoStatusRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f)
        )
        Text(
            value,
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.Medium
        )
    }
}

private fun formatJourneyPhase(phase: JourneyPhase): String {
    return when (phase) {
        JourneyPhase.JOURNEY_STARTED -> "Journey Started"
        JourneyPhase.WALKING -> "Walking"
        JourneyPhase.IN_VEHICLE -> "In Vehicle"
        JourneyPhase.TRAIN_TRANSIT -> "Train Transit"
        JourneyPhase.STATION_EXIT -> "Station Exit"
        JourneyPhase.LAST_MILE_WALK -> "Last Mile Walk"
        JourneyPhase.ARRIVED -> "Arrived"
        JourneyPhase.EMERGENCY -> "Emergency"
    }
}

private fun formatJourneyTimestamp(timestamp: Long): String {
    return SimpleDateFormat("h:mm a", Locale.getDefault()).format(Date(timestamp))
}

@Composable
private fun AdvancedSafetyEntryCard(
    features: SafetyFeatureState,
    onOpen: () -> Unit
) {
    val activeTools = listOf(
        features.liveTrackingActive,
        features.offlineSafetyEnabled,
        features.trustedJourney.active,
        features.safetyCheckIn.active,
        features.duressModeActive,
        features.emergencyDrillActive
    ).count { it }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onOpen),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)
        )
    ) {
        Column(modifier = Modifier.padding(20.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    Icons.Default.HealthAndSafety,
                    contentDescription = null,
                    tint = PrimaryRed,
                    modifier = Modifier.size(30.dp)
                )
                Spacer(modifier = Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        "Advanced Safety",
                        fontWeight = FontWeight.SemiBold,
                        style = MaterialTheme.typography.titleMedium
                    )
                    Text(
                        if (activeTools > 0) {
                            "$activeTools active safety tool${if (activeTools == 1) "" else "s"}"
                        } else {
                            "Live tracking, check-ins, duress PIN, offline mode"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f)
                    )
                }
                Icon(
                    Icons.Default.ChevronRight,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.45f)
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            Button(
                onClick = onOpen,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(containerColor = PrimaryRed)
            ) {
                Icon(Icons.Default.OpenInNew, contentDescription = null)
                Spacer(modifier = Modifier.width(8.dp))
                Text("Open Advanced Safety")
            }
        }
    }
}

@Composable
fun AdvancedSafetyCard(
    features: SafetyFeatureState,
    onOfflineChanged: (Boolean) -> Unit,
    onScheduleFakeCall: (Int) -> Unit,
    onStartCheckIn: (Int) -> Unit,
    onCancelCheckIn: () -> Unit,
    onRunEmergencyDrill: () -> Unit,
    onShareTimeline: () -> Unit,
    onStartJourney: (String, Int) -> Unit,
    onCompleteJourney: () -> Unit,
    onCancelPin: (String) -> Boolean,
    onSimulateHelp: () -> Unit,
    modifier: Modifier = Modifier
) {
    var destination by remember { mutableStateOf("") }
    var journeyMinutes by remember { mutableIntStateOf(15) }
    var pin by remember { mutableStateOf("") }
    var pinResult by remember { mutableStateOf<String?>(null) }
    var showDrillDialog by remember { mutableStateOf(false) }

    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)
        )
    ) {
        Column(modifier = Modifier.padding(20.dp)) {
            Text(
                "Advanced Safety",
                fontWeight = FontWeight.SemiBold,
                style = MaterialTheme.typography.titleMedium
            )

            Spacer(modifier = Modifier.height(12.dp))

            FeatureStatusRow(
                icon = Icons.Default.ShareLocation,
                title = if (features.liveTrackingActive) "Live SOS Tracking" else "Live Tracking Ready",
                subtitle = if (features.liveTrackingActive) {
                    "Session ${features.liveTrackingSessionId} • ${features.pendingAckCount} pending • ${features.helpedAckCount} helping"
                } else {
                    "Starts automatically when SOS is triggered"
                },
                color = if (features.liveTrackingActive) DangerRed else MaterialTheme.colorScheme.primary
            )

            FeatureStatusRow(
                icon = Icons.Default.Timeline,
                title = "Emergency Timeline",
                subtitle = features.timeline.lastOrNull()?.let { "${it.title}: ${it.detail}" }
                    ?: "Location, battery, risk, actions will be recorded",
                color = Color(0xFF607D8B)
            )
            OutlinedButton(
                onClick = onShareTimeline,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp)
            ) {
                Icon(Icons.Default.IosShare, contentDescription = null)
                Spacer(modifier = Modifier.width(8.dp))
                Text("Share emergency timeline")
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    Icons.Default.OfflineBolt,
                    contentDescription = null,
                    tint = if (features.offlineSafetyEnabled) SafeGreen else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f)
                )
                Spacer(modifier = Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text("Offline Safety Mode", fontWeight = FontWeight.Medium)
                    Text(
                        features.offlineCacheSummary,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                    )
                }
                Switch(
                    checked = features.offlineSafetyEnabled,
                    onCheckedChange = onOfflineChanged
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            Text("Fake Call Scheduler", fontWeight = FontWeight.Medium)
            Spacer(modifier = Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(2, 5, 10).forEach { minutes ->
                    AssistChip(
                        onClick = { onScheduleFakeCall(minutes) },
                        label = { Text("${minutes}m") },
                        leadingIcon = {
                            Icon(Icons.Default.PhoneInTalk, contentDescription = null, modifier = Modifier.size(16.dp))
                        }
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            Text("Safety Check-in Timer", fontWeight = FontWeight.Medium)
            Spacer(modifier = Modifier.height(8.dp))
            if (features.safetyCheckIn.active) {
                val minutesLeft = ((features.safetyCheckIn.dueAtMillis - System.currentTimeMillis()) / 60_000L)
                    .coerceAtLeast(0L)
                FeatureStatusRow(
                    icon = Icons.Default.Timer,
                    title = "Check-in Active",
                    subtitle = "${features.safetyCheckIn.label} • about $minutesLeft min left",
                    color = WarningYellow
                )
                OutlinedButton(
                    onClick = onCancelCheckIn,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Icon(Icons.Default.CheckCircle, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("I am safe")
                }
            } else {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(15, 30, 60).forEach { minutes ->
                        AssistChip(
                            onClick = { onStartCheckIn(minutes) },
                            label = { Text("${minutes}m") },
                            leadingIcon = {
                                Icon(Icons.Default.Timer, contentDescription = null, modifier = Modifier.size(16.dp))
                            }
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            OutlinedButton(
                onClick = { showDrillDialog = true },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp)
            ) {
                Icon(Icons.Default.HealthAndSafety, contentDescription = null)
                Spacer(modifier = Modifier.width(8.dp))
                Text(if (features.emergencyDrillActive) "Emergency drill running" else "Run emergency drill")
            }

            Spacer(modifier = Modifier.height(12.dp))

            Text("Trusted Journey", fontWeight = FontWeight.Medium)
            Spacer(modifier = Modifier.height(8.dp))
            if (features.trustedJourney.active) {
                FeatureStatusRow(
                    icon = Icons.Default.Route,
                    title = "Journey Active",
                    subtitle = features.trustedJourney.destination,
                    color = SafeGreen
                )
                Button(
                    onClick = onCompleteJourney,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text("Complete Journey")
                }
            } else {
                OutlinedTextField(
                    value = destination,
                    onValueChange = { destination = it },
                    label = { Text("Destination") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )
                Spacer(modifier = Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    listOf(15, 30, 60).forEach { minutes ->
                        FilterChip(
                            selected = journeyMinutes == minutes,
                            onClick = { journeyMinutes = minutes },
                            label = { Text("${minutes}m") }
                        )
                    }
                    Button(
                        onClick = { onStartJourney(destination, journeyMinutes) },
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Text("Start")
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            Text("Duress Cancel PIN", fontWeight = FontWeight.Medium)
            Text(
                "Normal PIN: 1234. Duress PIN: 0000 keeps SOS active silently.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
            )
            Spacer(modifier = Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedTextField(
                    value = pin,
                    onValueChange = { pin = it.take(6) },
                    label = { Text("PIN") },
                    modifier = Modifier.weight(1f),
                    singleLine = true
                )
                Button(
                    onClick = {
                        val cancelled = onCancelPin(pin)
                        pinResult = if (cancelled) "SOS cancelled" else "Duress/invalid PIN recorded"
                        pin = ""
                    },
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text("Apply")
                }
            }
            pinResult?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (it.contains("cancelled")) SafeGreen else WarningYellow
                )
            }

            if (features.contactAcknowledgements.isNotEmpty()) {
                Spacer(modifier = Modifier.height(12.dp))
                OutlinedButton(
                    onClick = onSimulateHelp,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Icon(Icons.Default.DoneAll, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Simulate Contact: Help Is Coming")
                }
            }

            Spacer(modifier = Modifier.height(12.dp))
            FeatureStatusRow(
                icon = Icons.Default.Groups,
                title = "Nearby Helper Network",
                subtitle = if (features.nearbyHelperNetworkEnabled) "Broadcast enabled" else "Ready for backend/volunteer sync",
                color = Color(0xFF3F51B5)
            )
        }
    }

    if (showDrillDialog) {
        AlertDialog(
            onDismissRequest = { showDrillDialog = false },
            title = { Text("Run emergency drill?") },
            text = {
                Text("This checks the local emergency flow and timeline without sending SMS or starting a real call.")
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        onRunEmergencyDrill()
                        showDrillDialog = false
                    }
                ) {
                    Text("Run drill")
                }
            },
            dismissButton = {
                TextButton(onClick = { showDrillDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }
}

@Composable
private fun FeatureStatusRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    subtitle: String,
    color: Color
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, contentDescription = null, tint = color)
        Spacer(modifier = Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(title, fontWeight = FontWeight.Medium)
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
            )
        }
    }
}
