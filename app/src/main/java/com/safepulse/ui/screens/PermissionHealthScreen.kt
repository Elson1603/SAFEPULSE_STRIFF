package com.safepulse.ui.screens

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material.icons.filled.Security
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.safepulse.ui.theme.PrimaryRed
import com.safepulse.ui.theme.SafeGreen
import com.safepulse.ui.theme.WarningYellow
import com.safepulse.utils.PermissionHelper

private data class PermissionHealthItem(
    val title: String,
    val detail: String,
    val granted: Boolean,
    val important: Boolean = true
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PermissionHealthScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    var refreshToken by remember { mutableIntStateOf(0) }
    val items = remember(refreshToken) { buildPermissionHealthItems(context) }
    val missingRuntimePermissions = remember(refreshToken) { getRequestableMissingPermissions(context) }
    val criticalMissing = items.count { it.important && !it.granted }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) {
        refreshToken++
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Permission Health") },
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
            verticalArrangement = Arrangement.spacedBy(12.dp),
            contentPadding = PaddingValues(vertical = 16.dp)
        ) {
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = if (criticalMissing == 0) SafeGreen.copy(alpha = 0.12f)
                        else WarningYellow.copy(alpha = 0.14f)
                    )
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Default.Security,
                            contentDescription = null,
                            tint = if (criticalMissing == 0) SafeGreen else WarningYellow
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = if (criticalMissing == 0) "Safety permissions ready" else "$criticalMissing critical item needs attention",
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = "SafePulse works best when emergency, location, and background permissions are healthy.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                            )
                        }
                    }
                }
            }

            items(items) { item ->
                PermissionHealthRow(item)
            }

            item {
                Button(
                    onClick = {
                        if (missingRuntimePermissions.isNotEmpty()) {
                            permissionLauncher.launch(missingRuntimePermissions.toTypedArray())
                        }
                    },
                    enabled = missingRuntimePermissions.isNotEmpty(),
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = PrimaryRed)
                ) {
                    Text(if (missingRuntimePermissions.isEmpty()) "Runtime permissions granted" else "Request missing permissions")
                }
            }

            item {
                OutlinedButton(
                    onClick = { openAppSettings(context) },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Icon(Icons.Default.OpenInNew, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Open app settings")
                }
            }

            item {
                OutlinedButton(
                    onClick = { PermissionHelper.openBatteryOptimizationSettings(context) },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Icon(Icons.Default.OpenInNew, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Battery optimization settings")
                }
            }
        }
    }
}

@Composable
private fun PermissionHealthRow(item: PermissionHealthItem) {
    val color = when {
        item.granted -> SafeGreen
        item.important -> PrimaryRed
        else -> WarningYellow
    }

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
            Icon(
                imageVector = if (item.granted) Icons.Default.CheckCircle else Icons.Default.Error,
                contentDescription = null,
                tint = color
            )
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(item.title, fontWeight = FontWeight.Medium)
                Text(
                    item.detail,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f)
                )
            }
            Text(
                text = if (item.granted) "Ready" else "Fix",
                style = MaterialTheme.typography.labelMedium,
                color = color,
                fontWeight = FontWeight.Bold
            )
        }
    }
}

private fun buildPermissionHealthItems(context: Context): List<PermissionHealthItem> {
    return listOf(
        PermissionHealthItem(
            title = "Precise location",
            detail = "Needed for SOS location, nearby safety, safe routes, and live tracking.",
            granted = PermissionHelper.hasLocationPermission(context)
        ),
        PermissionHealthItem(
            title = "Background location",
            detail = "Needed for monitoring when the app is closed.",
            granted = PermissionHelper.hasBackgroundLocationPermission(context)
        ),
        PermissionHealthItem(
            title = "SMS alerts",
            detail = "Needed to send SOS messages to emergency contacts.",
            granted = PermissionHelper.hasSMSPermission(context)
        ),
        PermissionHealthItem(
            title = "Phone calls",
            detail = "Needed to start emergency calls from SOS.",
            granted = PermissionHelper.hasCallPermission(context)
        ),
        PermissionHealthItem(
            title = "Notifications",
            detail = "Needed for monitoring, countdown, call fallback, and check-in alerts.",
            granted = PermissionHelper.hasNotificationPermission(context)
        ),
        PermissionHealthItem(
            title = "Microphone",
            detail = "Needed for voice SOS trigger.",
            granted = PermissionHelper.hasAudioPermission(context),
            important = false
        ),
        PermissionHealthItem(
            title = "Activity recognition",
            detail = "Improves fall, accident, and motion detection.",
            granted = isPermissionGranted(context, Manifest.permission.ACTIVITY_RECOGNITION),
            important = false
        ),
        PermissionHealthItem(
            title = "Camera",
            detail = "Optional for emergency evidence/photo capture.",
            granted = isPermissionGranted(context, Manifest.permission.CAMERA),
            important = false
        ),
        PermissionHealthItem(
            title = "Battery optimization",
            detail = "Disable optimization for more reliable background monitoring.",
            granted = isIgnoringBatteryOptimizations(context),
            important = false
        )
    )
}

private fun getRequestableMissingPermissions(context: Context): List<String> {
    val permissions = mutableListOf(
        Manifest.permission.ACCESS_FINE_LOCATION,
        Manifest.permission.ACCESS_COARSE_LOCATION,
        Manifest.permission.SEND_SMS,
        Manifest.permission.CALL_PHONE,
        Manifest.permission.RECORD_AUDIO,
        Manifest.permission.CAMERA
    )

    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        permissions.add(Manifest.permission.ACTIVITY_RECOGNITION)
    }
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        permissions.add(Manifest.permission.POST_NOTIFICATIONS)
    }

    return permissions.filter { !isPermissionGranted(context, it) }
}

private fun isPermissionGranted(context: Context, permission: String): Boolean {
    return ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
}

private fun isIgnoringBatteryOptimizations(context: Context): Boolean {
    val powerManager = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
    return powerManager?.isIgnoringBatteryOptimizations(context.packageName) ?: true
}

private fun openAppSettings(context: Context) {
    val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
        data = Uri.fromParts("package", context.packageName, null)
        flags = Intent.FLAG_ACTIVITY_NEW_TASK
    }
    context.startActivity(intent)
}
