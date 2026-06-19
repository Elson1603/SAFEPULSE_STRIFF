package com.safepulse.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.safepulse.ui.theme.*

data class ManualSection(
    val title: String,
    val icon: ImageVector,
    val iconColor: Color,
    val items: List<ManualItem>
)

data class ManualItem(
    val title: String,
    val description: String,
    val steps: List<String> = emptyList()
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UserManualScreen(
    onBack: () -> Unit
) {
    val primaryColor = MaterialTheme.colorScheme.primary
    val sections = remember(primaryColor) {
        listOf(
            ManualSection(
                title = "Emergency SOS Triggers",
                icon = Icons.Default.Sos,
                iconColor = DangerRed,
                items = listOf(
                    ManualItem(
                        title = "Triple Shake Gesture",
                        description = "Shake your phone 3 times within 2 seconds to trigger emergency SOS. The app will ask for voice confirmation to prevent false alarms.",
                        steps = listOf(
                            "Enable the SafePulse service from the home screen",
                            "Shake your phone vigorously 3 times within 2 seconds",
                            "App will speak: 'Do you need help?'",
                            "You have 10 seconds to respond:",
                            "  • Say 'No' to cancel the emergency",
                            "  • Say anything else or remain silent to proceed",
                            "If emergency proceeds, SOS will be sent to your contacts with your location"
                        )
                    ),
                    ManualItem(
                        title = "Manual SOS Button",
                        description = "Tap the Manual SOS button on the home screen to immediately send an emergency alert.",
                        steps = listOf(
                            "Go to the home screen",
                            "Tap the 'Manual SOS' button under Quick Actions",
                            "Your location and emergency alert will be sent to all emergency contacts"
                        )
                    ),
                    ManualItem(
                        title = "Voice-Based Trigger",
                        description = "Say emergency keywords like 'Help', 'Emergency', 'Save me', or 'Bachao' to trigger SOS hands-free.",
                        steps = listOf(
                            "Go to Settings > Voice Trigger",
                            "Enable 'Voice Emergency' toggle",
                            "Say any trigger word: 'Help', 'Emergency', 'Save me', or 'Bachao'",
                            "SOS will be automatically triggered"
                        )
                    ),
                    ManualItem(
                        title = "Triple Volume Button Press",
                        description = "Press the volume down button 3 times rapidly to trigger SOS with a photo capture.",
                        steps = listOf(
                            "Press volume down button 3 times quickly",
                            "App will automatically capture a photo",
                            "SOS with photo and location will be sent to emergency contacts"
                        )
                    )
                )
            ),
            ManualSection(
                title = "Safety Features",
                icon = Icons.Default.Shield,
                iconColor = SafeGreen,
                items = listOf(
                    ManualItem(
                        title = "Protection Service",
                        description = "The main safety service that continuously monitors your location and detects potential dangers.",
                        steps = listOf(
                            "Tap the large button on home screen to start protection",
                            "Service runs in background and monitors your safety",
                            "Automatic alerts when entering high-risk zones"
                        )
                    ),
                    ManualItem(
                        title = "Risk Level Detection",
                        description = "App automatically calculates risk level based on your location, time of day, and known danger zones.",
                        steps = listOf(
                            "Risk level shown on home screen (Low/Medium/High)",
                            "Updates automatically as you move",
                            "Higher risk triggers heightened safety mode"
                        )
                    ),
                    ManualItem(
                        title = "Safety Mode",
                        description = "Two modes: Normal and Heightened. Heightened mode activates in high-risk areas with enhanced monitoring.",
                        steps = listOf(
                            "Automatically switches based on detected risk",
                            "Heightened mode: more frequent location updates",
                            "Enhanced alertness for all emergency triggers"
                        )
                    )
                )
            ),
            ManualSection(
                title = "Emergency Contacts",
                icon = Icons.Default.People,
                iconColor = PrimaryRed,
                items = listOf(
                    ManualItem(
                        title = "Add Emergency Contacts",
                        description = "Add trusted contacts who will receive SOS alerts with your location.",
                        steps = listOf(
                            "Go to Settings > Emergency Contacts",
                            "Tap 'Add Emergency Contact' button",
                            "Enter name and phone number",
                            "Option to set as Primary contact",
                            "Add at least 2 contacts for safety"
                        )
                    ),
                    ManualItem(
                        title = "Primary Contact",
                        description = "Your most important emergency contact gets priority notifications.",
                        steps = listOf(
                            "Check 'Set as primary contact' when adding",
                            "Only one primary contact allowed",
                            "Primary contact receives SMS first in emergencies"
                        )
                    )
                )
            ),
            ManualSection(
                title = "Maps & Navigation",
                icon = Icons.Default.Map,
                iconColor = WarningYellow,
                items = listOf(
                    ManualItem(
                        title = "Risk Map",
                        description = "View crime hotspots and unsafe zones on an interactive map.",
                        steps = listOf(
                            "Tap 'Risk Map' card on home screen",
                            "See crime zones (red) and accident hotspots (orange)",
                            "Zoom and pan to explore different areas",
                            "Your current location shown in real-time"
                        )
                    ),
                    ManualItem(
                        title = "Safe Routes",
                        description = "Calculate the safest route between two locations, avoiding high-risk areas.",
                        steps = listOf(
                            "Tap 'Safe Routes' card on home screen",
                            "Enter your destination address",
                            "App calculates safest route avoiding danger zones",
                            "Follow the suggested route for maximum safety"
                        )
                    ),
                    ManualItem(
                        title = "Live Location Tracking",
                        description = "Your location is continuously monitored when protection is active.",
                        steps = listOf(
                            "Enable location permissions when prompted",
                            "Start protection service",
                            "Live map on home screen shows your position",
                            "Location shared in SOS messages"
                        )
                    )
                )
            ),
            ManualSection(
                title = "App Settings",
                icon = Icons.Default.Settings,
                iconColor = primaryColor,
                items = listOf(
                    ManualItem(
                        title = "Dark Mode",
                        description = "Enable dark theme for comfortable viewing at night.",
                        steps = listOf(
                            "Go to Settings > Appearance",
                            "Toggle 'Dark Mode' switch",
                            "App immediately switches to dark theme"
                        )
                    ),
                    ManualItem(
                        title = "Gender Setting",
                        description = "Set your gender to help app provide personalized safety recommendations.",
                        steps = listOf(
                            "Set during onboarding",
                            "View your setting in Settings > Profile > Gender",
                            "Affects risk calculations and safety suggestions"
                        )
                    ),
                    ManualItem(
                        title = "Event Logs",
                        description = "View history of all safety events and SOS alerts.",
                        steps = listOf(
                            "Tap history icon in top bar of home screen",
                            "See all logged events with timestamps",
                            "Review past emergencies and alerts"
                        )
                    )
                )
            ),
            ManualSection(
                title = "Permissions",
                icon = Icons.Default.Security,
                iconColor = Color(0xFF7B1FA2),
                items = listOf(
                    ManualItem(
                        title = "Required Permissions",
                        description = "SafePulse needs certain permissions to function properly and keep you safe.",
                        steps = listOf(
                            "Location: For real-time position and risk detection",
                            "SMS: To send emergency messages to contacts",
                            "Camera: For photo capture in volume button emergency",
                            "Microphone: For voice trigger feature",
                            "Sensors: For shake gesture detection",
                            "Notifications: For emergency alerts"
                        )
                    )
                )
            ),
            ManualSection(
                title = "Tips for Best Safety",
                icon = Icons.Default.Lightbulb,
                iconColor = Color(0xFFFFA726),
                items = listOf(
                    ManualItem(
                        title = "Best Practices",
                        description = "Follow these tips to get maximum protection from SafePulse.",
                        steps = listOf(
                            "Always keep protection service running when traveling",
                            "Add at least 2 trusted emergency contacts",
                            "Enable voice trigger for hands-free emergency",
                            "Check risk map before traveling to new areas",
                            "Use safe routes feature for unfamiliar destinations",
                            "Test emergency features to familiarize yourself",
                            "Keep phone charged and with you at all times",
                            "Ensure location services are always enabled"
                        )
                    )
                )
            )
        )
    }
    
    var expandedSectionIndex by remember { mutableStateOf<Int?>(null) }
    
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("User Manual") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    titleContentColor = Color.White,
                    navigationIconContentColor = Color.White
                )
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            contentPadding = PaddingValues(vertical = 16.dp)
        ) {
            item {
                // Welcome card
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer
                    )
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            Icons.Default.MenuBook,
                            contentDescription = null,
                            modifier = Modifier.size(48.dp),
                            tint = MaterialTheme.colorScheme.primary
                        )
                        Spacer(modifier = Modifier.width(16.dp))
                        Column {
                            Text(
                                "Welcome to SafePulse",
                                style = MaterialTheme.typography.titleLarge,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                            Text(
                                "Your complete guide to staying safe",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f)
                            )
                        }
                    }
                }
            }
            
            items(sections.size) { index ->
                val section = sections[index]
                ManualSectionCard(
                    section = section,
                    isExpanded = expandedSectionIndex == index,
                    onToggle = {
                        expandedSectionIndex = if (expandedSectionIndex == index) null else index
                    }
                )
            }
            
            item {
                // Footer
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.secondaryContainer
                    )
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Icon(
                            Icons.Default.Shield,
                            contentDescription = null,
                            modifier = Modifier.size(32.dp),
                            tint = PrimaryRed
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            "Stay Safe with SafePulse",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            "Version 1.0.0",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.6f)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ManualSectionCard(
    section: ManualSection,
    isExpanded: Boolean,
    onToggle: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        onClick = onToggle
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            // Section Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .background(
                            color = section.iconColor.copy(alpha = 0.15f),
                            shape = RoundedCornerShape(12.dp)
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        section.icon,
                        contentDescription = null,
                        tint = section.iconColor,
                        modifier = Modifier.size(24.dp)
                    )
                }
                Spacer(modifier = Modifier.width(12.dp))
                Text(
                    text = section.title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f)
                )
                Icon(
                    if (isExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                    contentDescription = if (isExpanded) "Collapse" else "Expand"
                )
            }
            
            // Expanded Content
            if (isExpanded) {
                Spacer(modifier = Modifier.height(16.dp))
                HorizontalDivider()
                Spacer(modifier = Modifier.height(16.dp))
                
                section.items.forEachIndexed { index, item ->
                    ManualItemContent(item = item)
                    if (index < section.items.size - 1) {
                        Spacer(modifier = Modifier.height(16.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun ManualItemContent(item: ManualItem) {
    Column {
        Text(
            text = item.title,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.primary
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = item.description,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f)
        )
        
        if (item.steps.isNotEmpty()) {
            Spacer(modifier = Modifier.height(8.dp))
            item.steps.forEachIndexed { index, step ->
                Row(
                    modifier = Modifier.padding(vertical = 2.dp)
                ) {
                    Text(
                        text = "${index + 1}.",
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.width(24.dp)
                    )
                    Text(
                        text = step,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }
    }
}
