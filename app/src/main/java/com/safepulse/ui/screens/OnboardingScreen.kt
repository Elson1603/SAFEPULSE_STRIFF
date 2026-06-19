package com.safepulse.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.safepulse.domain.model.Gender
import com.safepulse.ui.theme.*
import com.safepulse.ui.viewmodel.OnboardingViewModel

private const val REQUIRED_EMERGENCY_CONTACTS = 2

private data class SetupStepInfo(
    val label: String
)

private val setupSteps = listOf(
    SetupStepInfo("Safety profile"),
    SetupStepInfo("Emergency contacts"),
    SetupStepInfo("Voice SOS")
)

@Composable
fun OnboardingScreen(
    onComplete: () -> Unit,
    viewModel: OnboardingViewModel = viewModel()
) {
    val state by viewModel.state.collectAsState()
    val isLastStep = state.currentStep >= setupSteps.lastIndex
    
    LaunchedEffect(state.isComplete) {
        if (state.isComplete) {
            onComplete()
        }
    }
    
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    colors = listOf(
                        MaterialTheme.colorScheme.background,
                        MaterialTheme.colorScheme.background,
                        PrimaryRedDark.copy(alpha = 0.1f)
                    )
                )
            )
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            OnboardingHeader()

            Spacer(modifier = Modifier.height(20.dp))

            StepIndicator(
                currentStep = state.currentStep,
                steps = setupSteps
            )
            
            Spacer(modifier = Modifier.height(28.dp))
            
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                contentAlignment = Alignment.TopCenter
            ) {
                when (state.currentStep) {
                    0 -> GenderSelectionStep(
                        selectedGender = state.gender,
                        onGenderSelected = { viewModel.setGender(it) }
                    )
                    1 -> EmergencyContactsStep(
                        contacts = state.contacts,
                        onAddContact = { name, phone -> viewModel.addContact(name, phone) },
                        onRemoveContact = { viewModel.removeContact(it) }
                    )
                    2 -> VoiceTriggerStep(
                        enabled = state.voiceTriggerEnabled,
                        onToggle = { viewModel.setVoiceTriggerEnabled(it) }
                    )
                }
            }

            state.error?.let { error ->
                Text(
                    text = error,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(bottom = 16.dp)
                )
            }
            
            // Navigation buttons
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                if (state.currentStep > 0) {
                    OutlinedButton(
                        onClick = { viewModel.previousStep() },
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(Icons.Default.ArrowBack, contentDescription = null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Back")
                    }
                    Spacer(modifier = Modifier.width(16.dp))
                }
                
                Button(
                    onClick = { viewModel.nextStep() },
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = PrimaryRed
                    )
                ) {
                    Text(if (isLastStep) "Finish setup" else "Next")
                    Spacer(modifier = Modifier.width(8.dp))
                    Icon(
                        if (isLastStep) Icons.Default.Check else Icons.Default.ArrowForward,
                        contentDescription = null
                    )
                }
            }
        }
    }
}

@Composable
private fun OnboardingHeader() {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(44.dp)
                .clip(CircleShape)
                .background(PrimaryRed.copy(alpha = 0.12f)),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Default.Shield,
                contentDescription = null,
                tint = PrimaryRed,
                modifier = Modifier.size(24.dp)
            )
        }

        Spacer(modifier = Modifier.width(12.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = "SafePulse setup",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = "Configure the essentials before monitoring starts.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.7f),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun StepIndicator(currentStep: Int, steps: List<SetupStepInfo>) {
    val safeStep = currentStep.coerceIn(0, steps.lastIndex)

    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "Step ${safeStep + 1} of ${steps.size}",
                style = MaterialTheme.typography.labelLarge,
                color = PrimaryRed,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = steps[safeStep].label,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.65f)
            )
        }

        Spacer(modifier = Modifier.height(10.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            repeat(steps.size) { step ->
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .height(4.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .background(
                            if (step <= safeStep) PrimaryRed
                            else MaterialTheme.colorScheme.onBackground.copy(alpha = 0.14f)
                        )
                )
            }
        }
    }
}

@Composable
private fun GenderSelectionStep(
    selectedGender: Gender,
    onGenderSelected: (Gender) -> Unit
) {
    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(
            Icons.Default.Person,
            contentDescription = null,
            modifier = Modifier.size(80.dp),
            tint = PrimaryRed
        )
        
        Spacer(modifier = Modifier.height(16.dp))
        
        Text(
            text = "Personalize Safety Profile",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center
        )
        
        Text(
            text = "SafePulse uses this setting to tune risk checks and safety recommendations.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.7f),
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 8.dp)
        )
        
        Spacer(modifier = Modifier.height(32.dp))
        
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            GenderCard(
                gender = Gender.MALE,
                label = "Male",
                icon = Icons.Default.Male,
                isSelected = selectedGender == Gender.MALE,
                onClick = { onGenderSelected(Gender.MALE) },
                modifier = Modifier.weight(1f)
            )
            
            GenderCard(
                gender = Gender.FEMALE,
                label = "Female",
                icon = Icons.Default.Female,
                isSelected = selectedGender == Gender.FEMALE,
                onClick = { onGenderSelected(Gender.FEMALE) },
                modifier = Modifier.weight(1f)
            )
        }
    }
}

@Composable
private fun GenderCard(
    gender: Gender,
    label: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    isSelected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier
            .aspectRatio(1f)
            .clickable(onClick = onClick)
            .then(
                if (isSelected) Modifier.border(2.dp, PrimaryRed, RoundedCornerShape(16.dp))
                else Modifier
            ),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (isSelected) 
                PrimaryRed.copy(alpha = 0.1f) 
            else 
                MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Icon(
                icon,
                contentDescription = null,
                modifier = Modifier.size(48.dp),
                tint = if (isSelected) PrimaryRed else MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = label,
                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                color = if (isSelected) PrimaryRed else MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun EmergencyContactsStep(
    contacts: List<com.safepulse.data.db.entity.EmergencyContactEntity>,
    onAddContact: (String, String) -> Unit,
    onRemoveContact: (com.safepulse.data.db.entity.EmergencyContactEntity) -> Unit
) {
    var showAddDialog by remember { mutableStateOf(false) }
    
    Column(
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(
            Icons.Default.Contacts,
            contentDescription = null,
            modifier = Modifier.size(80.dp),
            tint = PrimaryRed
        )
        
        Spacer(modifier = Modifier.height(16.dp))
        
        Text(
            text = "Add Emergency Contacts",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center
        )
        
        Text(
            text = "Choose trusted people who should receive SOS messages, calls, and live location updates.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.7f),
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 8.dp)
        )
        
        Spacer(modifier = Modifier.height(24.dp))
        
        // Contact list
        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(contacts) { contact ->
                ContactCard(
                    contact = contact,
                    onRemove = { onRemoveContact(contact) }
                )
            }
            
            item {
                OutlinedButton(
                    onClick = { showAddDialog = true },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Default.Add, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Add emergency contact")
                }
            }
        }
        
        val remainingContacts = (REQUIRED_EMERGENCY_CONTACTS - contacts.size).coerceAtLeast(0)
        Text(
            text = when {
                remainingContacts == 0 -> "Ready: ${contacts.size} emergency contacts added"
                remainingContacts == 1 -> "1 more emergency contact required"
                else -> "$remainingContacts more emergency contacts required"
            },
            style = MaterialTheme.typography.bodySmall,
            color = if (contacts.size >= REQUIRED_EMERGENCY_CONTACTS) SafeGreen else WarningYellow,
            modifier = Modifier.padding(top = 16.dp)
        )
    }
    
    if (showAddDialog) {
        AddContactDialog(
            onDismiss = { showAddDialog = false },
            onAdd = { name, phone ->
                onAddContact(name, phone)
                showAddDialog = false
            }
        )
    }
}

@Composable
private fun ContactCard(
    contact: com.safepulse.data.db.entity.EmergencyContactEntity,
    onRemove: () -> Unit
) {
    val initial = contact.name.trim().firstOrNull()?.uppercase() ?: "?"

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(PrimaryRed.copy(alpha = 0.1f)),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = initial,
                    color = PrimaryRed,
                    fontWeight = FontWeight.Bold
                )
            }
            
            Spacer(modifier = Modifier.width(12.dp))
            
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = contact.name,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false)
                    )
                    if (contact.isPrimary) {
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "PRIMARY",
                            fontSize = 10.sp,
                            color = PrimaryRed,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
                Text(
                    text = contact.phone,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            
            IconButton(onClick = onRemove) {
                Icon(
                    Icons.Default.Close,
                    contentDescription = "Remove",
                    tint = MaterialTheme.colorScheme.error
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AddContactDialog(
    onDismiss: () -> Unit,
    onAdd: (String, String) -> Unit
) {
    var name by remember { mutableStateOf("") }
    var phone by remember { mutableStateOf("") }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add Emergency Contact") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    text = "This contact will be used for emergency alerts and live location updates.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                )

                OutlinedTextField(
                    value = name,
                    onValueChange = { 
                        name = it
                        errorMessage = null
                    },
                    label = { Text("Full name") },
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
                    label = { Text("Mobile number") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    isError = phone.isNotEmpty() && phone.length != 10,
                    supportingText = {
                        val color = if (phone.length == 10) SafeGreen 
                                   else if (phone.isEmpty()) MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f) 
                                   else MaterialTheme.colorScheme.error
                        Text(
                            "${phone.length}/10 digits",
                            color = color,
                            fontWeight = if (phone.length == 10) FontWeight.Bold else FontWeight.Normal
                        )
                    }
                )
                
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
            
            Button(
                onClick = {
                    when {
                        name.isBlank() -> errorMessage = "Please enter a name"
                        phone.isBlank() -> errorMessage = "Please enter a phone number"
                        phone.length != 10 -> errorMessage = "Phone number must be exactly 10 digits"
                        !phone.all { it.isDigit() } -> errorMessage = "Phone number must contain only digits"
                        else -> onAdd(name.trim(), phone.trim())
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

@Composable
private fun VoiceTriggerStep(
    enabled: Boolean,
    onToggle: (Boolean) -> Unit
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(
            Icons.Default.Mic,
            contentDescription = null,
            modifier = Modifier.size(80.dp),
            tint = PrimaryRed
        )
        
        Spacer(modifier = Modifier.height(16.dp))
        
        Text(
            text = "Enable Voice SOS",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center
        )
        
        Text(
            text = "Allow emergency keywords to start SOS while monitoring is active.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.7f),
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 8.dp)
        )
        
        Spacer(modifier = Modifier.height(32.dp))
        
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(20.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Voice SOS",
                        fontWeight = FontWeight.Medium
                    )
                    Text(
                        text = "Recognizes \"Help\", \"Save me\", or \"Emergency\" during safety monitoring.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                    )
                }
                Switch(
                    checked = enabled,
                    onCheckedChange = onToggle,
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = Color.White,
                        checkedTrackColor = SafeGreen
                    )
                )
            }
        }
        
        Spacer(modifier = Modifier.height(16.dp))
        
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(
                containerColor = WarningYellow.copy(alpha = 0.1f)
            )
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalAlignment = Alignment.Top
            ) {
                Icon(
                    Icons.Default.Info,
                    contentDescription = null,
                    tint = WarningYellow,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(modifier = Modifier.width(12.dp))
                Text(
                    text = "Microphone permission is required. Voice detection stays active only while monitoring is enabled.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f)
                )
            }
        }
    }
}
