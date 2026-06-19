package com.safepulse.ui.screens

import android.app.Application
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.safepulse.SafePulseApplication
import com.safepulse.data.db.entity.EmergencyContactEntity
import com.safepulse.data.db.entity.HotspotEntity
import com.safepulse.data.db.entity.UnsafeZoneEntity
import com.safepulse.data.prefs.UserPreferences
import com.safepulse.data.repository.EmergencyContactRepository
import com.safepulse.data.repository.HotspotRepository
import com.safepulse.data.repository.UnsafeZoneRepository
import com.safepulse.domain.model.Gender
import com.safepulse.ml.VoiceKeywordMatcher
import com.safepulse.service.SafetyFeatureManager
import com.safepulse.service.SafetyFeatureState
import com.safepulse.ui.theme.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

// Settings ViewModel
data class SettingsState(
    val gender: Gender = Gender.UNSPECIFIED,
    val voiceTriggerEnabled: Boolean = false,
    val darkModeEnabled: Boolean = false,
    val autoStartOnBoot: Boolean = true,
    val contacts: List<EmergencyContactEntity> = emptyList(),
    val hotspots: List<HotspotEntity> = emptyList(),
    val unsafeZones: List<UnsafeZoneEntity> = emptyList(),
    val safetyFeatures: SafetyFeatureState = SafetyFeatureState()
)

class SettingsViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application as SafePulseApplication
    private val userPreferences = UserPreferences(application)
    private val contactRepository = EmergencyContactRepository(app.database.emergencyContactDao())
    private val hotspotRepository = HotspotRepository(app.database.hotspotDao())
    private val unsafeZoneRepository = UnsafeZoneRepository(app.database.unsafeZoneDao())
    private val safetyFeatureManager = SafetyFeatureManager.getInstance(application)
    
    private val _state = MutableStateFlow(SettingsState())
    val state: StateFlow<SettingsState> = _state.asStateFlow()
    
    init {
        loadData()
    }
    
    private fun loadData() {
        viewModelScope.launch {
            val settings = userPreferences.userSettingsFlow.first()
            _state.value = _state.value.copy(
                gender = settings.gender,
                voiceTriggerEnabled = settings.voiceTriggerEnabled,
                darkModeEnabled = settings.darkModeEnabled,
                autoStartOnBoot = settings.autoStartOnBoot
            )
        }
        
        viewModelScope.launch {
            contactRepository.getAllContacts().collect { contacts ->
                _state.value = _state.value.copy(contacts = contacts)
            }
        }
        
        viewModelScope.launch {
            hotspotRepository.getAllHotspots().collect { hotspots ->
                _state.value = _state.value.copy(hotspots = hotspots)
            }
        }
        
        viewModelScope.launch {
            unsafeZoneRepository.getAllUnsafeZones().collect { zones ->
                _state.value = _state.value.copy(unsafeZones = zones)
            }
        }

        viewModelScope.launch {
            safetyFeatureManager.state.collect { features ->
                _state.value = _state.value.copy(safetyFeatures = features)
            }
        }
    }
    
    fun setVoiceTriggerEnabled(enabled: Boolean) {
        viewModelScope.launch {
            userPreferences.setVoiceTriggerEnabled(enabled)
            _state.value = _state.value.copy(voiceTriggerEnabled = enabled)
        }
    }
    
    fun setDarkModeEnabled(enabled: Boolean) {
        viewModelScope.launch {
            userPreferences.setDarkModeEnabled(enabled)
            _state.value = _state.value.copy(darkModeEnabled = enabled)
        }
    }

    fun setAutoStartOnBoot(enabled: Boolean) {
        viewModelScope.launch {
            userPreferences.setAutoStartOnBoot(enabled)
            _state.value = _state.value.copy(autoStartOnBoot = enabled)
        }
    }

    fun setSosMessageTemplate(message: String) {
        safetyFeatureManager.setSosMessageTemplate(message)
    }

    fun setFakeCallerName(name: String) {
        safetyFeatureManager.setFakeCallerName(name)
    }

    fun setSafetyPins(normalPin: String, duressPin: String): Boolean {
        return safetyFeatureManager.setSafetyPins(normalPin, duressPin)
    }
    
    fun addContact(name: String, phone: String, isPrimary: Boolean) {
        viewModelScope.launch {
            val contact = EmergencyContactEntity(
                name = name,
                phone = phone,
                isPrimary = isPrimary
            )
            contactRepository.insert(contact)
        }
    }
    
    fun deleteContact(contact: EmergencyContactEntity) {
        viewModelScope.launch {
            contactRepository.delete(contact)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    onNavigateToRiskMap: () -> Unit = {},
    onNavigateToUserManual: () -> Unit = {},
    onNavigateToPermissionHealth: () -> Unit = {},
    viewModel: SettingsViewModel = viewModel()
) {
    val state by viewModel.state.collectAsState()
    var showZonesDialog by remember { mutableStateOf(false) }
    var showSosTemplateDialog by remember { mutableStateOf(false) }
    var showFakeCallerDialog by remember { mutableStateOf(false) }
    var showPinDialog by remember { mutableStateOf(false) }
    
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            contentPadding = PaddingValues(vertical = 16.dp)
        ) {
            // Profile section
            item {
                SectionHeader("Profile")
            }
            
            item {
                SettingsCard {
                    SettingsRow(
                        icon = Icons.Default.Person,
                        title = "Gender",
                        subtitle = state.gender.name,
                        iconColor = PrimaryRed
                    )
                }
            }
            
            // Appearance section
            item {
                SectionHeader("Appearance")
            }
            
            item {
                SettingsCard {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            Icons.Default.DarkMode,
                            contentDescription = null,
                            tint = if (state.darkModeEnabled) WarningYellow else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f)
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Dark Mode", fontWeight = FontWeight.Medium)
                            Text(
                                "Enable dark theme for better night viewing",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                            )
                        }
                        Switch(
                            checked = state.darkModeEnabled,
                            onCheckedChange = { viewModel.setDarkModeEnabled(it) },
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = Color.White,
                                checkedTrackColor = MaterialTheme.colorScheme.primary
                            )
                        )
                    }
                }
            }

            // Safety controls section
            item {
                SectionHeader("Safety Controls")
            }

            item {
                SettingsCard {
                    Column {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                Icons.Default.RestartAlt,
                                contentDescription = null,
                                tint = if (state.autoStartOnBoot) SafeGreen else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f)
                            )
                            Spacer(modifier = Modifier.width(12.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text("Auto-start after reboot", fontWeight = FontWeight.Medium)
                                Text(
                                    "Restore monitoring if protection was active before restart",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                                )
                            }
                            Switch(
                                checked = state.autoStartOnBoot,
                                onCheckedChange = { viewModel.setAutoStartOnBoot(it) },
                                colors = SwitchDefaults.colors(
                                    checkedThumbColor = Color.White,
                                    checkedTrackColor = SafeGreen
                                )
                            )
                        }

                        HorizontalDivider(modifier = Modifier.padding(horizontal = 12.dp))

                        SafetyActionRow(
                            icon = Icons.Default.VerifiedUser,
                            title = "Permission health check",
                            subtitle = "Review location, SMS, call, notification, and microphone access",
                            iconColor = MaterialTheme.colorScheme.primary,
                            onClick = onNavigateToPermissionHealth
                        )

                        HorizontalDivider(modifier = Modifier.padding(horizontal = 12.dp))

                        SafetyActionRow(
                            icon = Icons.Default.Message,
                            title = "SOS message template",
                            subtitle = state.safetyFeatures.sosMessageTemplate,
                            iconColor = PrimaryRed,
                            onClick = { showSosTemplateDialog = true }
                        )

                        HorizontalDivider(modifier = Modifier.padding(horizontal = 12.dp))

                        SafetyActionRow(
                            icon = Icons.Default.PhoneInTalk,
                            title = "Fake caller name",
                            subtitle = state.safetyFeatures.fakeCallerName,
                            iconColor = Color(0xFF1565C0),
                            onClick = { showFakeCallerDialog = true }
                        )

                        HorizontalDivider(modifier = Modifier.padding(horizontal = 12.dp))

                        SafetyActionRow(
                            icon = Icons.Default.Pin,
                            title = "Cancel and duress PINs",
                            subtitle = "Update the normal cancel PIN and silent duress PIN",
                            iconColor = WarningYellow,
                            onClick = { showPinDialog = true }
                        )
                    }
                }
            }
            
            // Emergency Contacts section
            item {
                SectionHeader("Emergency Contacts")
            }
            
            item {
                SettingsCard {
                    Column {
                        if (state.contacts.isEmpty()) {
                            // Empty state
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(24.dp),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                Icon(
                                    Icons.Default.PersonOff,
                                    contentDescription = null,
                                    modifier = Modifier.size(48.dp),
                                    tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f)
                                )
                                Spacer(modifier = Modifier.height(12.dp))
                                Text(
                                    "No emergency contacts added",
                                    style = MaterialTheme.typography.bodyLarge,
                                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                                )
                                Spacer(modifier = Modifier.height(8.dp))
                                Text(
                                    "Add at least 2 contacts for emergency SOS",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f)
                                )
                            }
                        } else {
                            state.contacts.forEachIndexed { index, contact ->
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(12.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(
                                        Icons.Default.Person,
                                        contentDescription = null,
                                        tint = if (contact.isPrimary) PrimaryRed else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                                    )
                                    Spacer(modifier = Modifier.width(12.dp))
                                    Column(modifier = Modifier.weight(1f)) {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Text(contact.name, fontWeight = FontWeight.Medium)
                                            if (contact.isPrimary) {
                                                Spacer(modifier = Modifier.width(8.dp))
                                                Surface(
                                                    shape = RoundedCornerShape(4.dp),
                                                    color = PrimaryRed.copy(alpha = 0.15f)
                                                ) {
                                                    Text(
                                                        "PRIMARY",
                                                        style = MaterialTheme.typography.labelSmall,
                                                        color = PrimaryRed,
                                                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                                    )
                                                }
                                            }
                                        }
                                        Text(
                                            contact.phone,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                                        )
                                    }
                                    IconButton(onClick = { viewModel.deleteContact(contact) }) {
                                        Icon(
                                            Icons.Default.Delete,
                                            contentDescription = "Delete",
                                            tint = MaterialTheme.colorScheme.error
                                        )
                                    }
                                }
                                if (index < state.contacts.size - 1) {
                                    HorizontalDivider(modifier = Modifier.padding(horizontal = 12.dp))
                                }
                            }
                        }
                        
                        HorizontalDivider(modifier = Modifier.padding(horizontal = 12.dp))
                        
                        // Add Contact Button
                        var showAddContactDialog by remember { mutableStateOf(false) }
                        
                        TextButton(
                            onClick = { showAddContactDialog = true },
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp)
                        ) {
                            Icon(Icons.Default.Add, contentDescription = null)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Add Emergency Contact")
                        }
                        
                        if (showAddContactDialog) {
                            AddContactDialog(
                                onDismiss = { showAddContactDialog = false },
                                onAdd = { name, phone, isPrimary ->
                                    viewModel.addContact(name, phone, isPrimary)
                                    showAddContactDialog = false
                                }
                            )
                        }
                    }
                }
            }
            
            // Statistics section
            item {
                SectionHeader("Statistics")
            }
            
            item {
                SettingsCard {
                    Column {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(16.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                Icons.Default.Warning,
                                contentDescription = null,
                                tint = PrimaryRed.copy(alpha = 0.7f)
                            )
                            Spacer(modifier = Modifier.width(16.dp))
                            Column {
                                Text("Events", fontWeight = FontWeight.Medium)
                                Text(
                                    "Detected safety events",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                                )
                            }
                            Spacer(modifier = Modifier.weight(1f))
                            Text(
                                "6",
                                style = MaterialTheme.typography.headlineSmall,
                                fontWeight = FontWeight.Bold,
                                color = PrimaryRed
                            )
                        }
                        
                        HorizontalDivider(modifier = Modifier.padding(horizontal = 12.dp))
                        
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(16.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                Icons.Default.Send,
                                contentDescription = null,
                                tint = PrimaryRed.copy(alpha = 0.7f)
                            )
                            Spacer(modifier = Modifier.width(16.dp))
                            Column {
                                Text("SOS Sent", fontWeight = FontWeight.Medium)
                                Text(
                                    "Emergency messages sent",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                                )
                            }
                            Spacer(modifier = Modifier.weight(1f))
                            Text(
                                "6",
                                style = MaterialTheme.typography.headlineSmall,
                                fontWeight = FontWeight.Bold,
                                color = PrimaryRed
                            )
                        }
                        
                        HorizontalDivider(modifier = Modifier.padding(horizontal = 12.dp))
                        
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(16.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                Icons.Default.People,
                                contentDescription = null,
                                tint = PrimaryRed.copy(alpha = 0.7f)
                            )
                            Spacer(modifier = Modifier.width(16.dp))
                            Column {
                                Text("Emergency Contacts", fontWeight = FontWeight.Medium)
                                Text(
                                    "${state.contacts.size} contacts configured",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                                )
                            }
                            Spacer(modifier = Modifier.weight(1f))
                            Text(
                                state.contacts.size.toString(),
                                style = MaterialTheme.typography.headlineSmall,
                                fontWeight = FontWeight.Bold,
                                color = PrimaryRed
                            )
                        }
                    }
                }
            }
            
            // Zones section
            item {
                SectionHeader("Safety Zones")
            }
            
            item {
                SettingsCard {
                    Column {
                        SettingsRow(
                            icon = Icons.Default.Warning,
                            title = "Accident Hotspots",
                            subtitle = "${state.hotspots.size} zones loaded",
                            iconColor = RiskHigh
                        )
                        HorizontalDivider(modifier = Modifier.padding(horizontal = 12.dp))
                        SettingsRow(
                            icon = Icons.Default.LocationOff,
                            title = "Unsafe Zones",
                            subtitle = "${state.unsafeZones.size} zones loaded",
                            iconColor = WarningYellow
                        )
                        HorizontalDivider(modifier = Modifier.padding(horizontal = 12.dp))
                        TextButton(
                            onClick = { onNavigateToRiskMap() },
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp)
                        ) {
                            Icon(Icons.Default.Map, contentDescription = null)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("View on Risk Map")
                        }
                    }
                }
            }
            
            // About section
            item {
                SectionHeader("About")
            }
            
            item {
                SettingsCard {
                    Column {
                        SettingsRow(
                            icon = Icons.Default.Info,
                            title = "Version",
                            subtitle = "1.0.0 (Prototype)",
                            iconColor = MaterialTheme.colorScheme.primary
                        )
                        HorizontalDivider(modifier = Modifier.padding(horizontal = 12.dp))
                        SettingsRow(
                            icon = Icons.Default.Shield,
                            title = "SafePulse",
                            subtitle = "Offline-first Safety Application",
                            iconColor = PrimaryRed
                        )
                        HorizontalDivider(modifier = Modifier.padding(horizontal = 12.dp))
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(16.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                Icons.Default.MenuBook,
                                contentDescription = null,
                                tint = SafeGreen
                            )
                            Spacer(modifier = Modifier.width(16.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text("User Manual", fontWeight = FontWeight.Medium)
                                Text(
                                    "Learn about all features",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                                )
                            }
                            IconButton(onClick = onNavigateToUserManual) {
                                Icon(
                                    Icons.Default.ChevronRight,
                                    contentDescription = "Open User Manual",
                                    tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f)
                                )
                            }
                        }
                    }
                }
            }
        }
    }
    
    // Zone Details Dialog
    if (showZonesDialog) {
        ZoneDetailsDialog(
            hotspots = state.hotspots,
            unsafeZones = state.unsafeZones,
            onDismiss = { showZonesDialog = false }
        )
    }

    if (showSosTemplateDialog) {
        EditableSafetyTextDialog(
            title = "SOS message template",
            initialValue = state.safetyFeatures.sosMessageTemplate,
            label = "Emergency instruction",
            maxLength = 180,
            onDismiss = { showSosTemplateDialog = false },
            onSave = {
                viewModel.setSosMessageTemplate(it)
                showSosTemplateDialog = false
            }
        )
    }

    if (showFakeCallerDialog) {
        EditableSafetyTextDialog(
            title = "Fake caller name",
            initialValue = state.safetyFeatures.fakeCallerName,
            label = "Caller name",
            maxLength = 32,
            onDismiss = { showFakeCallerDialog = false },
            onSave = {
                viewModel.setFakeCallerName(it)
                showFakeCallerDialog = false
            }
        )
    }

    if (showPinDialog) {
        PinSettingsDialog(
            onDismiss = { showPinDialog = false },
            onSave = { normalPin, duressPin ->
                viewModel.setSafetyPins(normalPin, duressPin)
            }
        )
    }
}

@Composable
private fun SectionHeader(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(vertical = 4.dp)
    )
}

@Composable
private fun SettingsCard(content: @Composable () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp)
    ) {
        content()
    }
}

@Composable
private fun SettingsRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    subtitle: String,
    iconColor: Color
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, contentDescription = null, tint = iconColor)
        Spacer(modifier = Modifier.width(16.dp))
        Column {
            Text(title, fontWeight = FontWeight.Medium)
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
            )
        }
    }
}

@Composable
private fun SafetyActionRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    subtitle: String,
    iconColor: Color,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, contentDescription = null, tint = iconColor)
        Spacer(modifier = Modifier.width(16.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(title, fontWeight = FontWeight.Medium)
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                maxLines = 2
            )
        }
        Icon(
            Icons.Default.ChevronRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f)
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun EditableSafetyTextDialog(
    title: String,
    initialValue: String,
    label: String,
    maxLength: Int,
    onDismiss: () -> Unit,
    onSave: (String) -> Unit
) {
    var value by remember(initialValue) { mutableStateOf(initialValue) }
    val cleanValue = value.trim()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = value,
                    onValueChange = { value = it.take(maxLength) },
                    label = { Text(label) },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = if (maxLength > 80) 3 else 1,
                    supportingText = {
                        Text("${value.length}/$maxLength")
                    }
                )
                Text(
                    "This is used by automatic safety actions.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onSave(cleanValue) },
                enabled = cleanValue.isNotBlank()
            ) {
                Text("Save")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PinSettingsDialog(
    onDismiss: () -> Unit,
    onSave: (normalPin: String, duressPin: String) -> Boolean
) {
    var normalPin by remember { mutableStateOf("") }
    var duressPin by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Update safety PINs") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    "Use different PINs. The duress PIN looks like cancel, but keeps SOS active silently.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                )
                OutlinedTextField(
                    value = normalPin,
                    onValueChange = {
                        normalPin = it.filter(Char::isDigit).take(8)
                        error = null
                    },
                    label = { Text("Normal cancel PIN") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword)
                )
                OutlinedTextField(
                    value = duressPin,
                    onValueChange = {
                        duressPin = it.filter(Char::isDigit).take(8)
                        error = null
                    },
                    label = { Text("Duress PIN") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword)
                )
                error?.let {
                    Text(
                        it,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val saved = onSave(normalPin, duressPin)
                    if (saved) {
                        onDismiss()
                    } else {
                        error = "Enter two different PINs with at least 4 digits each"
                    }
                },
                enabled = normalPin.length >= 4 && duressPin.length >= 4
            ) {
                Text("Save")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}

@Composable
private fun ZoneDetailsDialog(
    hotspots: List<HotspotEntity>,
    unsafeZones: List<UnsafeZoneEntity>,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Safety Zones") },
        text = {
            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.heightIn(max = 400.dp)
            ) {
                item {
                    Text(
                        "Accident Hotspots",
                        fontWeight = FontWeight.Bold,
                        color = RiskHigh
                    )
                }
                items(hotspots) { hotspot ->
                    Text(
                        "• ${String.format("%.4f", hotspot.lat)}, ${String.format("%.4f", hotspot.lng)} - Risk: ${(hotspot.baseRisk * 100).toInt()}% (${hotspot.roadType})",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
                
                item {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        "Unsafe Zones",
                        fontWeight = FontWeight.Bold,
                        color = WarningYellow
                    )
                }
                items(unsafeZones) { zone ->
                    Text(
                        "• ${String.format("%.4f", zone.lat)}, ${String.format("%.4f", zone.lng)} - Crime: ${(zone.crimeScore * 100).toInt()}%",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("Close")
            }
        }
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun VoiceDemoDialog(onDismiss: () -> Unit) {
    var testInput by remember { mutableStateOf("") }
    var result by remember { mutableStateOf<String?>(null) }
    
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Voice Trigger Demo") },
        text = {
            Column {
                Text(
                    "This simulates voice keyword detection. Type a phrase containing trigger words:",
                    style = MaterialTheme.typography.bodySmall
                )
                Spacer(modifier = Modifier.height(8.dp))
                
                Text(
                    "Keywords: ${VoiceKeywordMatcher.supportedKeywords.joinToString()}",
                    style = MaterialTheme.typography.labelSmall,
                    color = PrimaryRed
                )
                
                Spacer(modifier = Modifier.height(16.dp))
                
                OutlinedTextField(
                    value = testInput,
                    onValueChange = { testInput = it },
                    label = { Text("Enter phrase") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )
                
                Spacer(modifier = Modifier.height(8.dp))
                
                Button(
                    onClick = {
                        val match = VoiceKeywordMatcher.findBestMatch(listOf(testInput))
                        result = if (match.matched) {
                            "KEYWORD DETECTED: ${match.keyword} (${(match.confidence * 100).toInt()}%)"
                        } else {
                            "No clear SOS keyword detected"
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Test")
                }
                
                result?.let { r ->
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = r,
                        fontWeight = FontWeight.Bold,
                        color = if (r.contains("DETECTED")) DangerRed else SafeGreen
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("Close")
            }
        }
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AddContactDialog(
    onDismiss: () -> Unit,
    onAdd: (name: String, phone: String, isPrimary: Boolean) -> Unit
) {
    var name by remember { mutableStateOf("") }
    var phone by remember { mutableStateOf("") }
    var isPrimary by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add Emergency Contact") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { 
                        name = it
                        errorMessage = null
                    },
                    label = { Text("Name") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    isError = errorMessage != null && name.isBlank()
                )
                
                OutlinedTextField(
                    value = phone,
                    onValueChange = { newValue ->
                        // Only allow digits, max 10 characters
                        if (newValue.all { it.isDigit() } && newValue.length <= 10) {
                            phone = newValue
                            errorMessage = null
                        }
                    },
                    label = { Text("Phone Number (10 digits)") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    isError = phone.isNotEmpty() && phone.length != 10,
                    supportingText = {
                        val color = if (phone.length == 10) SafeGreen else if (phone.isEmpty()) MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f) else MaterialTheme.colorScheme.error
                        Text(
                            "${phone.length}/10 digits",
                            color = color,
                            fontWeight = if (phone.length == 10) FontWeight.Bold else FontWeight.Normal
                        )
                    }
                )
                
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Checkbox(
                        checked = isPrimary,
                        onCheckedChange = { isPrimary = it }
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Set as primary contact")
                }
                
                errorMessage?.let { error ->
                    Text(
                        text = error,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        },
        confirmButton = {
            val isValid = name.isNotBlank() && phone.length == 10 && phone.all { it.isDigit() }
            
            TextButton(
                onClick = {
                    when {
                        name.isBlank() -> errorMessage = "Please enter a name"
                        phone.isBlank() -> errorMessage = "Please enter a phone number"
                        phone.length != 10 -> errorMessage = "Phone number must be exactly 10 digits"
                        !phone.all { it.isDigit() } -> errorMessage = "Phone number must contain only digits"
                        else -> onAdd(name.trim(), phone.trim(), isPrimary)
                    }
                },
                enabled = isValid
            ) {
                Text("Add")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}
