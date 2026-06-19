package com.safepulse.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.NearMe
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch

/**
 * Dialog for searching destination - supports custom input and preset cities
 */
@Composable
fun DestinationSearchDialogNew(
    currentLocation: com.google.android.gms.maps.model.LatLng?,
    onDismiss: () -> Unit,
    onSearch: (com.google.android.gms.maps.model.LatLng) -> Unit
) {
    var customInput by remember { mutableStateOf("") }
    var isGeocoding by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val presetDestinations = remember {
        listOf(
            "Delhi - Connaught Place" to com.google.android.gms.maps.model.LatLng(28.6315, 77.2167),
            "Mumbai - CST" to com.google.android.gms.maps.model.LatLng(19.0760, 72.8777),
            "Bangalore - MG Road" to com.google.android.gms.maps.model.LatLng(12.9758, 77.6045),
            "Chennai - T. Nagar" to com.google.android.gms.maps.model.LatLng(13.0418, 80.2341),
            "Hyderabad - Charminar" to com.google.android.gms.maps.model.LatLng(17.3616, 78.4747),
            "Kolkata - Park Street" to com.google.android.gms.maps.model.LatLng(22.5520, 88.3520),
            "Pune - Deccan" to com.google.android.gms.maps.model.LatLng(18.5167, 73.8415),
            "Jaipur - Pink City" to com.google.android.gms.maps.model.LatLng(26.9239, 75.8267),
        )
    }
    
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Search Destination", fontWeight = FontWeight.Bold) },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState())
            ) {
                // Custom text input
                OutlinedTextField(
                    value = customInput,
                    onValueChange = { 
                        customInput = it
                        errorMessage = null
                    },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Address or place name") },
                    placeholder = { Text("e.g., Mumbai Airport, Bandra") },
                    singleLine = true,
                    isError = errorMessage != null,
                    supportingText = errorMessage?.let { { Text(it, color = MaterialTheme.colorScheme.error) } }
                )
                
                Spacer(modifier = Modifier.height(12.dp))
                
                // Quick distance presets
                Text("Quick distances:", style = MaterialTheme.typography.bodySmall)
                Spacer(modifier = Modifier.height(6.dp))
                
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    val loc = currentLocation ?: com.google.android.gms.maps.model.LatLng(28.6139, 77.2090)
                    OutlinedButton(
                        onClick = {
                            onSearch(com.google.android.gms.maps.model.LatLng(
                                loc.latitude + 0.01, loc.longitude + 0.01
                            ))
                        },
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("1km", style = MaterialTheme.typography.labelSmall)
                    }
                    OutlinedButton(
                        onClick = {
                            onSearch(com.google.android.gms.maps.model.LatLng(
                                loc.latitude + 0.02, loc.longitude + 0.02
                            ))
                        },
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("2km", style = MaterialTheme.typography.labelSmall)
                    }
                    OutlinedButton(
                        onClick = {
                            onSearch(com.google.android.gms.maps.model.LatLng(
                                loc.latitude + 0.05, loc.longitude + 0.05
                            ))
                        },
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("5km", style = MaterialTheme.typography.labelSmall)
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                // Preset city destinations
                Text("Popular destinations:", style = MaterialTheme.typography.bodySmall)
                Spacer(modifier = Modifier.height(6.dp))
                
                Column(
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.heightIn(max = 250.dp)
                ) {
                    presetDestinations.forEach { (name, latLng) ->
                        OutlinedCard(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onSearch(latLng) },
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Row(
                                modifier = Modifier.padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    Icons.Default.Place,
                                    null,
                                    modifier = Modifier.size(20.dp),
                                    tint = MaterialTheme.colorScheme.primary
                                )
                                Spacer(Modifier.width(8.dp))
                                Text(name, fontSize = 13.sp)
                            }
                        }
                    }

                    // Nearby option
                    currentLocation?.let { loc ->
                        OutlinedCard(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    onSearch(com.google.android.gms.maps.model.LatLng(
                                        loc.latitude + 0.02, loc.longitude + 0.02
                                    ))
                                },
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Row(
                                modifier = Modifier.padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    Icons.Default.NearMe,
                                    null,
                                    modifier = Modifier.size(20.dp),
                                    tint = MaterialTheme.colorScheme.secondary
                                )
                                Spacer(Modifier.width(8.dp))
                                Text("Nearby (~2km away)", fontSize = 13.sp)
                            }
                        }
                    }
                }
                
                if (isGeocoding) {
                    Spacer(modifier = Modifier.height(12.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.Center
                    ) {
                        CircularProgressIndicator(modifier = Modifier.size(24.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Searching...", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    if (customInput.isBlank()) {
                        errorMessage = "Please enter a destination"
                        return@Button
                    }
                    
                    scope.launch {
                        isGeocoding = true
                        errorMessage = null
                        try {
                            val geocoder = android.location.Geocoder(context)
                            val addresses = geocoder.getFromLocationName(customInput, 1)
                            
                            if (!addresses.isNullOrEmpty()) {
                                val address = addresses[0]
                                android.util.Log.d("DestinationSearch", "Found: ${address.getAddressLine(0)}")
                                onSearch(com.google.android.gms.maps.model.LatLng(
                                    address.latitude,
                                    address.longitude
                                ))
                            } else {
                                errorMessage = "Location not found. Try another address."
                                android.util.Log.w("DestinationSearch", "No results for: $customInput")
                            }
                        } catch (e: Exception) {
                            errorMessage = "Search failed: ${e.message}"
                            android.util.Log.e("DestinationSearch", "Geocoding error", e)
                        } finally {
                            isGeocoding = false
                        }
                    }
                },
                enabled = !isGeocoding && customInput.isNotBlank()
            ) {
                Text("Search")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}
