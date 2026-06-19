package com.safepulse.ui.nearbysafety

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.google.android.gms.location.LocationServices
import com.google.android.gms.maps.model.LatLng
import com.safepulse.domain.riskmap.SafeRouteOption
import com.safepulse.domain.saferoutes.VehicleRecommendation
import com.safepulse.ui.map.*

private val PoliceColor = Color(0xFF1565C0)
private val HospitalColor = Color(0xFFD32F2F)
private val SafeZoneColor = Color(0xFF2E7D32)
private val HighRiskColor = Color(0xFFF44336)
private val MediumRiskColor = Color(0xFFFF9800)
private val LowRiskColor = Color(0xFF4CAF50)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NearbySafetyScreen(
    viewModel: NearbySafetyViewModel,
    onNavigateBack: () -> Unit
) {
    val context = LocalContext.current
    val uiState by viewModel.uiState.collectAsState()
    val currentLocation by viewModel.currentLocation.collectAsState()
    val policeStations by viewModel.policeStations.collectAsState()
    val hospitals by viewModel.hospitals.collectAsState()
    val safeZones by viewModel.safeZones.collectAsState()
    val crimeZonesForMap by viewModel.crimeZonesForMap.collectAsState()

    var mapController by remember { mutableStateOf<LeafletMapController?>(null) }
    var hasLocationPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION)
                    == PackageManager.PERMISSION_GRANTED
        )
    }

    val locationPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        hasLocationPermission = granted
        if (granted) {
            fetchLocation(context) { viewModel.updateCurrentLocation(it) }
        }
    }

    LaunchedEffect(Unit) {
        if (!hasLocationPermission) {
            locationPermissionLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION)
        } else {
            fetchLocation(context) { viewModel.updateCurrentLocation(it) }
        }
    }

    // Update map when state changes
    LaunchedEffect(uiState, currentLocation, crimeZonesForMap, mapController) {
        val ctrl = mapController ?: return@LaunchedEffect
        val state = uiState as? NearbySafetyUiState.Success ?: return@LaunchedEffect

        val markers = mutableListOf<MarkerData>()
        val boundsPoints = mutableListOf<LatLng>()

        val filtered = if (state.selectedCategory == SafetyCategory.ALL)
            state.items
        else
            state.items.filter { it.category == state.selectedCategory }

        filtered.forEach { item ->
            val (emoji, bgColor) = when (item.category) {
                SafetyCategory.POLICE -> "\uD83D\uDE94" to "#1565C0"
                SafetyCategory.HOSPITAL -> "\uD83C\uDFE5" to "#D32F2F"
                SafetyCategory.SAFE_ZONE -> "\u2705" to "#4CAF50"
                else -> "\uD83D\uDCCD" to "#9E9E9E"
            }
            markers.add(MarkerData(
                item.location.latitude, item.location.longitude,
                "$emoji ${item.name}",
                "${"%.1f".format(item.distanceKm)} km | Risk: ${item.riskLabel}",
                bgColor, emoji, bgColor
            ))
        }

        // Draw only the safest route via OSRM
        var safestRoute: SafeRouteOption? = null

        state.selectedDetail?.let { detail ->
            safestRoute = detail.routeOptions.firstOrNull { it.isSafest }
                ?: detail.routeOptions.minByOrNull { it.totalRiskScore }
            boundsPoints.add(detail.item.location)
        }

        currentLocation?.let { boundsPoints.add(it) }

        ctrl.batchUpdate(MapUpdateData(
            clear = true,
            currentLocation = currentLocation?.let { it.latitude to it.longitude },
            markers = markers,
            fitBoundsPoints = if (boundsPoints.size >= 2) boundsPoints else null
        ))

        // Draw the selected destination as a real road route. The route options
        // are for risk scoring; their interpolated points must not be used as
        // mandatory navigation waypoints.
        if (safestRoute != null) {
            val origin = currentLocation
            val destination = state.selectedDetail?.item?.location
            if (origin != null && destination != null) {
                ctrl.drawSafeRoute(origin, destination, crimeZonesForMap)
            }
        }
    }

    // Load all-India police stations as persistent layer
    LaunchedEffect(policeStations, mapController) {
        val ctrl = mapController ?: return@LaunchedEffect
        if (policeStations.isNotEmpty()) {
            ctrl.addPoliceStations(policeStations)
        }
    }

    // Load all-India hospitals as persistent layer
    LaunchedEffect(hospitals, mapController) {
        val ctrl = mapController ?: return@LaunchedEffect
        if (hospitals.isNotEmpty()) {
            ctrl.addHospitals(hospitals)
        }
    }

    // Load all-India safe zones as persistent layer
    LaunchedEffect(safeZones, mapController) {
        val ctrl = mapController ?: return@LaunchedEffect
        if (safeZones.isNotEmpty()) {
            ctrl.addSafeZones(safeZones)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Find Nearby Safety", fontWeight = FontWeight.Bold, fontSize = 18.sp)
                        Text("All India - Police, Hospitals & Safe Zones", fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.Default.ArrowBack, "Back")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            // Map
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(0.4f)
            ) {
                if (hasLocationPermission) {
                    LeafletMapView(
                        modifier = Modifier.fillMaxSize(),
                        onMapReady = { ctrl ->
                            mapController = ctrl
                            currentLocation?.let {
                                ctrl.setCenter(it.latitude, it.longitude, 13f)
                                ctrl.setCurrentLocation(it)
                            }
                        }
                    )
                } else {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(MaterialTheme.colorScheme.surfaceVariant),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(Icons.Default.LocationOff, null, modifier = Modifier.size(48.dp))
                            Spacer(Modifier.height(8.dp))
                            Text("Location permission required")
                            Spacer(Modifier.height(8.dp))
                            Button(onClick = {
                                locationPermissionLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION)
                            }) { Text("Grant Permission") }
                        }
                    }
                }
            }

            // Category filter chips
            val successState = uiState as? NearbySafetyUiState.Success
            CategoryFilterRow(
                selected = successState?.selectedCategory ?: SafetyCategory.ALL,
                onSelect = { viewModel.setCategory(it) },
                modifier = Modifier.padding(vertical = 4.dp)
            )

            // Content
            Box(modifier = Modifier.fillMaxWidth().weight(0.6f)) {
                when (uiState) {
                    is NearbySafetyUiState.Loading -> {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                CircularProgressIndicator()
                                Spacer(Modifier.height(8.dp))
                                Text("Finding nearby safe places...", fontSize = 13.sp)
                            }
                        }
                    }
                    is NearbySafetyUiState.Error -> {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Icon(Icons.Default.Error, null, Modifier.size(32.dp),
                                    tint = MaterialTheme.colorScheme.error)
                                Spacer(Modifier.height(8.dp))
                                Text((uiState as NearbySafetyUiState.Error).message,
                                    color = MaterialTheme.colorScheme.error)
                            }
                        }
                    }
                    is NearbySafetyUiState.Success -> {
                        val state = uiState as NearbySafetyUiState.Success
                        if (state.selectedDetail != null) {
                            RouteDetailPanel(
                                detail = state.selectedDetail!!,
                                onBack = { viewModel.clearSelection() }
                            )
                        } else {
                            val filtered = if (state.selectedCategory == SafetyCategory.ALL)
                                state.items
                            else
                                state.items.filter { it.category == state.selectedCategory }

                            if (filtered.isEmpty()) {
                                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                    Text("No places found in this category",
                                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            } else {
                                LazyColumn(
                                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                                    verticalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    items(filtered) { item ->
                                        NearbyItemCard(
                                            item = item,
                                            onClick = { viewModel.selectItem(item) }
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CategoryFilterRow(
    selected: SafetyCategory,
    onSelect: (SafetyCategory) -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        data class ChipInfo(val category: SafetyCategory, val label: String, val icon: ImageVector)
        val chips = listOf(
            ChipInfo(SafetyCategory.ALL, "All", Icons.Default.Shield),
            ChipInfo(SafetyCategory.POLICE, "Police", Icons.Default.LocalPolice),
            ChipInfo(SafetyCategory.HOSPITAL, "Hospital", Icons.Default.LocalHospital),
            ChipInfo(SafetyCategory.SAFE_ZONE, "Safe Zone", Icons.Default.VerifiedUser)
        )
        chips.forEach { chip ->
            FilterChip(
                selected = selected == chip.category,
                onClick = { onSelect(chip.category) },
                label = { Text(chip.label, fontSize = 12.sp) },
                leadingIcon = {
                    Icon(chip.icon, null, modifier = Modifier.size(16.dp))
                }
            )
        }
    }
}

@Composable
private fun NearbyItemCard(item: NearbySafetyItem, onClick: () -> Unit) {
    val (icon, iconColor) = when (item.category) {
        SafetyCategory.POLICE -> Icons.Default.LocalPolice to PoliceColor
        SafetyCategory.HOSPITAL -> Icons.Default.LocalHospital to HospitalColor
        SafetyCategory.SAFE_ZONE -> Icons.Default.VerifiedUser to SafeZoneColor
        else -> Icons.Default.Place to Color.Gray
    }
    val riskColor = when (item.riskLabel) {
        "HIGH" -> HighRiskColor
        "MEDIUM" -> MediumRiskColor
        else -> LowRiskColor
    }

    Card(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        shape = RoundedCornerShape(12.dp)
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(iconColor.copy(alpha = 0.15f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(icon, null, tint = iconColor, modifier = Modifier.size(22.dp))
            }
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(item.name, fontWeight = FontWeight.SemiBold, fontSize = 14.sp,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (item.subtitle.isNotEmpty()) {
                    Text(item.subtitle, fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.padding(top = 2.dp)
                ) {
                    Text("${"%.1f".format(item.distanceKm)} km", fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Spacer(Modifier.width(8.dp))
            // Risk badge
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .background(riskColor.copy(alpha = 0.15f))
                    .padding(horizontal = 8.dp, vertical = 3.dp)
            ) {
                Text(item.riskLabel, fontSize = 10.sp, fontWeight = FontWeight.Bold, color = riskColor)
            }
            Spacer(Modifier.width(4.dp))
            Icon(Icons.Default.ChevronRight, null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                modifier = Modifier.size(20.dp))
        }
    }
}

@Composable
private fun RouteDetailPanel(
    detail: SelectedDestinationDetail,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val item = detail.item
    val (iconColor) = when (item.category) {
        SafetyCategory.POLICE -> PoliceColor
        SafetyCategory.HOSPITAL -> HospitalColor
        SafetyCategory.SAFE_ZONE -> SafeZoneColor
        else -> Color.Gray
    } to Unit

    LazyColumn(
        contentPadding = PaddingValues(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        // Header with back
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack, modifier = Modifier.size(32.dp)) {
                    Icon(Icons.Default.ArrowBack, "Back", modifier = Modifier.size(18.dp))
                }
                Spacer(Modifier.width(4.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(item.name, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                    Text("${"%.1f".format(item.distanceKm)} km away",
                        fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }

        item {
            val phoneNumber = item.phoneNumber.ifBlank {
                when (item.category) {
                    SafetyCategory.POLICE -> "112"
                    SafetyCategory.HOSPITAL -> "108"
                    else -> ""
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedButton(
                    onClick = { openNavigation(context, item.location) },
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Icon(Icons.Default.Navigation, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Navigate")
                }
                Button(
                    onClick = { openDialer(context, phoneNumber) },
                    enabled = phoneNumber.isNotBlank(),
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Icon(Icons.Default.Call, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Call")
                }
            }
        }

        // Vehicle recommendation
        item {
            VehicleCard(detail.vehicleRecommendation)
        }

        // Safest route
        item {
            Text("Safest Route", fontWeight = FontWeight.Bold, fontSize = 14.sp,
                modifier = Modifier.padding(top = 4.dp))
        }
        val safest = detail.routeOptions.firstOrNull { it.isSafest }
            ?: detail.routeOptions.minByOrNull { it.totalRiskScore }
        safest?.let { route ->
            item { RouteOptionCard(route) }
        }

        // Safety tips
        if (detail.vehicleRecommendation.safetyTips.isNotEmpty()) {
            item {
                Text("Safety Tips", fontWeight = FontWeight.Bold, fontSize = 14.sp,
                    modifier = Modifier.padding(top = 4.dp))
            }
            items(detail.vehicleRecommendation.safetyTips) { tip ->
                Row(modifier = Modifier.padding(start = 4.dp, bottom = 2.dp)) {
                    Text("\u2022 ", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(tip, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

@Composable
private fun VehicleCard(recommendation: VehicleRecommendation) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer
        ),
        shape = RoundedCornerShape(12.dp)
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(Icons.Default.DirectionsCar, null,
                tint = MaterialTheme.colorScheme.onPrimaryContainer,
                modifier = Modifier.size(28.dp))
            Spacer(Modifier.width(12.dp))
            Column {
                Text(recommendation.vehicle.displayName,
                    fontWeight = FontWeight.Bold, fontSize = 14.sp)
                Text(recommendation.reason, fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f))
            }
        }
    }
}

@Composable
private fun RouteOptionCard(route: SafeRouteOption) {
    val riskColor = when {
        route.totalRiskScore >= 0.7f -> HighRiskColor
        route.totalRiskScore >= 0.4f -> MediumRiskColor
        else -> LowRiskColor
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (route.isSafest)
                LowRiskColor.copy(alpha = 0.08f)
            else MaterialTheme.colorScheme.surfaceVariant
        ),
        shape = RoundedCornerShape(10.dp)
    ) {
        Column(modifier = Modifier.padding(10.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (route.isSafest) {
                        Text("\u2705 ", fontSize = 13.sp)
                    }
                    Text(route.name,
                        fontWeight = if (route.isSafest) FontWeight.Bold else FontWeight.Normal,
                        fontSize = 13.sp)
                }
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(4.dp))
                        .background(riskColor.copy(alpha = 0.15f))
                        .padding(horizontal = 6.dp, vertical = 2.dp)
                ) {
                    Text("Risk: ${(route.totalRiskScore * 100).toInt()}%",
                        fontSize = 10.sp, color = riskColor, fontWeight = FontWeight.Bold)
                }
            }
            Row(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.padding(top = 4.dp)
            ) {
                Text("${"%.1f".format(route.distanceKm)} km", fontSize = 11.sp)
                Text("Crime: ${(route.crimeRisk * 100).toInt()}%", fontSize = 11.sp)
                Text("Disaster: ${(route.disasterRisk * 100).toInt()}%", fontSize = 11.sp)
            }
            if (route.warnings.isNotEmpty()) {
                Spacer(Modifier.height(4.dp))
                route.warnings.take(2).forEach { warning ->
                    Text(warning, fontSize = 10.sp, color = MediumRiskColor,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

@SuppressLint("MissingPermission")
private fun fetchLocation(
    context: Context,
    onLocation: (LatLng) -> Unit
) {
    val fusedClient = LocationServices.getFusedLocationProviderClient(context)
    fusedClient.lastLocation.addOnSuccessListener { location ->
        location?.let {
            onLocation(LatLng(it.latitude, it.longitude))
        } ?: run {
            onLocation(LatLng(28.6139, 77.2090)) // Default: Delhi
        }
    }.addOnFailureListener {
        onLocation(LatLng(28.6139, 77.2090))
    }
}

private fun openDialer(context: Context, phoneNumber: String) {
    if (phoneNumber.isBlank()) return
    val intent = Intent(Intent.ACTION_DIAL).apply {
        data = Uri.fromParts("tel", phoneNumber, null)
        flags = Intent.FLAG_ACTIVITY_NEW_TASK
    }
    context.startActivity(intent)
}

private fun openNavigation(context: Context, destination: LatLng) {
    val uri = Uri.parse("google.navigation:q=${destination.latitude},${destination.longitude}")
    val intent = Intent(Intent.ACTION_VIEW, uri).apply {
        setPackage("com.google.android.apps.maps")
        flags = Intent.FLAG_ACTIVITY_NEW_TASK
    }

    try {
        context.startActivity(intent)
    } catch (e: Exception) {
        val fallback = Intent(
            Intent.ACTION_VIEW,
            Uri.parse("https://www.google.com/maps/dir/?api=1&destination=${destination.latitude},${destination.longitude}")
        ).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        context.startActivity(fallback)
    }
}
