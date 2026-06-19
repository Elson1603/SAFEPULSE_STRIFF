package com.safepulse.ui.riskmap

import android.Manifest
import android.annotation.SuppressLint
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.google.android.gms.location.LocationServices
import com.google.android.gms.maps.model.LatLng
import com.safepulse.domain.riskmap.*
import com.safepulse.ui.map.*

// Risk level colors
private val HighRiskColor = Color(0xFFF44336)
private val MediumRiskColor = Color(0xFFFF9800)
private val LowRiskColor = Color(0xFF4CAF50)
private val FloodColor = Color(0xFF2196F3)
private val LandslideColor = Color(0xFF795548)
private val SafeRouteColor = Color(0xFF4CAF50)
private val UnsafeRouteColor = Color(0xFFF44336)
private val AltRouteColor = Color(0xFFFF9800)
private val PoliceColor = Color(0xFF1565C0)
private val HospitalColor = Color(0xFFD32F2F)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RiskMapScreen(
    viewModel: RiskMapViewModel,
    onNavigateBack: () -> Unit
) {
    val context = LocalContext.current
    val uiState by viewModel.uiState.collectAsState()
    val currentLocation by viewModel.currentLocation.collectAsState()
    val locationRisk by viewModel.locationRisk.collectAsState()
    val selectedFilter by viewModel.selectedFilter.collectAsState()
    val safeRoutes by viewModel.safeRoutes.collectAsState()
    val destination by viewModel.destination.collectAsState()
    val safetyPlaces by viewModel.safetyPlaces.collectAsState()
    val showSafetyPlaces by viewModel.showSafetyPlaces.collectAsState()
    val policeStations by viewModel.policeStations.collectAsState()
    val hospitals by viewModel.hospitals.collectAsState()
    val safeZones by viewModel.safeZones.collectAsState()
    val crimeZonesForMap by viewModel.crimeZonesForMap.collectAsState()

    var mapController by remember { mutableStateOf<LeafletMapController?>(null) }
    var showDestinationDialog by remember { mutableStateOf(false) }
    var showBottomSheet by remember { mutableStateOf(true) }
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
            fetchCurrentLocation(context) { loc ->
                viewModel.updateCurrentLocation(loc)
                mapController?.animateTo(loc, 12f)
            }
        }
    }

    LaunchedEffect(Unit) {
        viewModel.loadRiskData()
        if (!hasLocationPermission) {
            locationPermissionLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION)
        } else {
            fetchCurrentLocation(context) { loc ->
                viewModel.updateCurrentLocation(loc)
            }
        }
    }

    // Update map overlays when data or filter changes
    LaunchedEffect(uiState, selectedFilter, safeRoutes, destination, showSafetyPlaces, safetyPlaces, mapController) {
        val ctrl = mapController ?: return@LaunchedEffect
        val state = uiState as? RiskMapUiState.Success ?: return@LaunchedEffect
        drawRiskOverlays(
            ctrl, state.riskData, selectedFilter, safeRoutes,
            currentLocation, destination,
            if (showSafetyPlaces) safetyPlaces else emptyList(),
            crimeZonesForMap
        )
    }

    // Load police stations as a persistent layer
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
                        Text("Risk Map", fontWeight = FontWeight.Bold, fontSize = 18.sp)
                        locationRisk?.let { risk ->
                            Text(
                                text = "Risk Level: ${risk.riskLevel}",
                                fontSize = 12.sp,
                                color = when (risk.riskLevel) {
                                    "HIGH" -> HighRiskColor
                                    "MEDIUM" -> MediumRiskColor
                                    else -> LowRiskColor
                                }
                            )
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.Default.ArrowBack, "Back")
                    }
                },
                actions = {
                    IconButton(onClick = { viewModel.toggleSafetyPlaces() }) {
                        Icon(
                            Icons.Default.LocalHospital,
                            "Toggle Safety Places",
                            tint = if (showSafetyPlaces) MaterialTheme.colorScheme.primary
                                   else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f)
                        )
                    }
                    IconButton(onClick = { showDestinationDialog = true }) {
                        Icon(Icons.Default.Directions, "Safe Route")
                    }
                    IconButton(onClick = { showBottomSheet = !showBottomSheet }) {
                        Icon(Icons.Default.Info, "Risk Info")
                    }
                }
            )
        },
        floatingActionButton = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (safeRoutes.isNotEmpty()) {
                    FloatingActionButton(
                        onClick = { viewModel.clearRoutes() },
                        containerColor = MaterialTheme.colorScheme.errorContainer,
                        modifier = Modifier.size(48.dp)
                    ) {
                        Icon(Icons.Default.Close, "Clear Routes", modifier = Modifier.size(20.dp))
                    }
                }
                FloatingActionButton(
                    onClick = {
                        currentLocation?.let { loc ->
                            mapController?.animateTo(loc, 14f)
                        }
                    }
                ) {
                    Icon(Icons.Default.MyLocation, "My Location")
                }
            }
        }
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            // Map
            if (hasLocationPermission) {
                LeafletMapView(
                    modifier = Modifier.fillMaxSize(),
                    onMapReady = { ctrl ->
                        mapController = ctrl
                        currentLocation?.let {
                            ctrl.setCenter(it.latitude, it.longitude, 12f)
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

            // Filter chips at top
            FilterChipsRow(
                selectedFilter = selectedFilter,
                onFilterChanged = { viewModel.setFilter(it) },
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 8.dp)
            )

            // Loading
            if (uiState is RiskMapUiState.Loading) {
                CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
            }

            // Bottom risk info panel
            AnimatedVisibility(
                visible = showBottomSheet && (locationRisk != null || safeRoutes.isNotEmpty()),
                modifier = Modifier.align(Alignment.BottomCenter)
            ) {
                RiskInfoPanel(
                    locationRisk = locationRisk,
                    safeRoutes = safeRoutes,
                    onRouteSelect = { route ->
                        if (route.waypoints.isNotEmpty()) {
                            mapController?.fitBounds(route.waypoints)
                        }
                    }
                )
            }

            // Legend
            MapLegend(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(top = 56.dp, end = 8.dp)
            )
        }
    }

    // Destination dialog
    if (showDestinationDialog) {
        DestinationInputDialog(
            onDismiss = { showDestinationDialog = false },
            onSearch = { destLat, destLng ->
                showDestinationDialog = false
                viewModel.searchSafeRoute(LatLng(destLat, destLng))
            },
            currentLocation = currentLocation
        )
    }

    DisposableEffect(Unit) {
        onDispose { /* Leaflet WebView cleaned up by Compose */ }
    }
}

@Composable
private fun FilterChipsRow(
    selectedFilter: RiskFilter,
    onFilterChanged: (RiskFilter) -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        RiskFilter.values().forEach { filter ->
            FilterChip(
                selected = filter == selectedFilter,
                onClick = { onFilterChanged(filter) },
                label = {
                    Text(
                        when (filter) {
                            RiskFilter.ALL -> "All Risks"
                            RiskFilter.CRIME_ONLY -> "🔴 Crime"
                            RiskFilter.DISASTER_ONLY -> "🌊 Disaster"
                        },
                        fontSize = 12.sp
                    )
                }
            )
        }
    }
}

@Composable
private fun MapLegend(modifier: Modifier = Modifier) {
    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.92f)
        ),
        shape = RoundedCornerShape(8.dp)
    ) {
        Column(modifier = Modifier.padding(8.dp)) {
            Text("Legend", fontWeight = FontWeight.Bold, fontSize = 10.sp)
            LegendItem(HighRiskColor, "High Crime Risk")
            LegendItem(MediumRiskColor, "Medium Crime Risk")
            LegendItem(LowRiskColor, "Low Crime Risk")
            LegendItem(FloodColor, "Flood Risk")
            LegendItem(LandslideColor, "Landslide Risk")
            LegendItem(PoliceColor, "🚔 Police Station")
            LegendItem(HospitalColor, "🏥 Hospital")
        }
    }
}

@Composable
private fun LegendItem(color: Color, label: String) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.padding(vertical = 1.dp)
    ) {
        Box(
            modifier = Modifier
                .size(10.dp)
                .clip(CircleShape)
                .background(color.copy(alpha = 0.6f))
        )
        Spacer(Modifier.width(4.dp))
        Text(label, fontSize = 9.sp, maxLines = 1)
    }
}

@Composable
private fun RiskInfoPanel(
    locationRisk: LocationRiskInfo?,
    safeRoutes: List<SafeRouteOption>,
    onRouteSelect: (SafeRouteOption) -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(8.dp),
        shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp, bottomStart = 8.dp, bottomEnd = 8.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.95f)
        )
    ) {
        Column(
            modifier = Modifier
                .padding(16.dp)
                .verticalScroll(rememberScrollState())
                .heightIn(max = 280.dp)
        ) {
            // Location risk summary
            locationRisk?.let { risk ->
                Text("📍 Your Area Risk Analysis", fontWeight = FontWeight.Bold, fontSize = 14.sp)
                Spacer(Modifier.height(8.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly
                ) {
                    RiskScoreChip("Crime", risk.crimeRisk, HighRiskColor)
                    RiskScoreChip("Disaster", risk.disasterRisk, FloodColor)
                    RiskScoreChip("Overall", risk.overallRisk,
                        when {
                            risk.overallRisk >= 0.7f -> HighRiskColor
                            risk.overallRisk >= 0.4f -> MediumRiskColor
                            else -> LowRiskColor
                        }
                    )
                }

                // Nearby zones
                if (risk.nearbyCrimeZones.isNotEmpty()) {
                    Spacer(Modifier.height(8.dp))
                    Text("Nearby Crime Zones:", fontWeight = FontWeight.SemiBold, fontSize = 12.sp)
                    risk.nearbyCrimeZones.take(3).forEach { zone ->
                        Text(
                            "• ${zone.city}: ${zone.totalCrimes} crimes (${(zone.crimeRiskScore * 100).toInt()}% risk)",
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                if (risk.nearbyDisasterZones.isNotEmpty()) {
                    Spacer(Modifier.height(4.dp))
                    Text("Nearby Disaster Zones:", fontWeight = FontWeight.SemiBold, fontSize = 12.sp)
                    risk.nearbyDisasterZones.take(3).forEach { zone ->
                        val factors = zone.riskFactors.take(2).joinToString(", ")
                        Text(
                            "• ${zone.city}: $factors",
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            // Safe route section — show only the safest
            if (safeRoutes.isNotEmpty()) {
                val safest = safeRoutes.firstOrNull { it.isSafest }
                    ?: safeRoutes.minByOrNull { it.totalRiskScore }
                Spacer(Modifier.height(12.dp))
                Divider()
                Spacer(Modifier.height(8.dp))
                Text("🛣️ Safest Route", fontWeight = FontWeight.Bold, fontSize = 14.sp)
                Spacer(Modifier.height(8.dp))

                safest?.let { route ->
                    SafeRouteCard(route = route, onClick = { onRouteSelect(route) })
                }
            }
        }
    }
}

@Composable
private fun RiskScoreChip(label: String, score: Float, color: Color) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(label, fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(12.dp))
                .background(color.copy(alpha = 0.15f))
                .padding(horizontal = 12.dp, vertical = 4.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                "${(score * 100).toInt()}%",
                fontWeight = FontWeight.Bold,
                color = color,
                fontSize = 14.sp
            )
        }
    }
}

@Composable
private fun SafeRouteCard(route: SafeRouteOption, onClick: () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        colors = CardDefaults.cardColors(
            containerColor = if (route.isSafest)
                LowRiskColor.copy(alpha = 0.1f)
            else
                MaterialTheme.colorScheme.surfaceVariant
        ),
        shape = RoundedCornerShape(8.dp)
    ) {
        Column(modifier = Modifier.padding(10.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (route.isSafest) {
                        Text("✅ ", fontSize = 14.sp)
                    }
                    Text(
                        route.name,
                        fontWeight = if (route.isSafest) FontWeight.Bold else FontWeight.Normal,
                        fontSize = 13.sp
                    )
                }
                val routeColor = when {
                    route.totalRiskScore < 0.3f -> LowRiskColor
                    route.totalRiskScore < 0.6f -> MediumRiskColor
                    else -> HighRiskColor
                }
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(4.dp))
                        .background(routeColor.copy(alpha = 0.2f))
                        .padding(horizontal = 6.dp, vertical = 2.dp)
                ) {
                    Text(
                        "Risk: ${(route.totalRiskScore * 100).toInt()}%",
                        fontSize = 11.sp,
                        color = routeColor,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("📏 ${String.format("%.1f", route.distanceKm)} km", fontSize = 11.sp)
                Text("🔴 Crime: ${(route.crimeRisk * 100).toInt()}%", fontSize = 11.sp)
                Text("🌊 Disaster: ${(route.disasterRisk * 100).toInt()}%", fontSize = 11.sp)
            }
            if (route.warnings.isNotEmpty()) {
                Spacer(Modifier.height(4.dp))
                route.warnings.take(2).forEach { warning ->
                    Text(
                        warning,
                        fontSize = 10.sp,
                        color = MediumRiskColor,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DestinationInputDialog(
    onDismiss: () -> Unit,
    onSearch: (Double, Double) -> Unit,
    currentLocation: LatLng?
) {
    var destinationText by remember { mutableStateOf("") }
    val presetDestinations = remember {
        listOf(
            "Delhi - Connaught Place" to LatLng(28.6315, 77.2167),
            "Mumbai - CST" to LatLng(19.0760, 72.8777),
            "Bangalore - MG Road" to LatLng(12.9758, 77.6045),
            "Chennai - T. Nagar" to LatLng(13.0418, 80.2341),
            "Hyderabad - Charminar" to LatLng(17.3616, 78.4747),
            "Kolkata - Park Street" to LatLng(22.5520, 88.3520),
            "Pune - Deccan" to LatLng(18.5167, 73.8415),
            "Jaipur - Pink City" to LatLng(26.9239, 75.8267),
        )
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("🛣️ Find Safe Route", fontWeight = FontWeight.Bold) },
        text = {
            Column {
                Text(
                    "Select a destination to get the safest route based on crime and disaster data:",
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(12.dp))

                // Preset city destinations
                Column(
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.heightIn(max = 300.dp).verticalScroll(rememberScrollState())
                ) {
                    presetDestinations.forEach { (name, latLng) ->
                        OutlinedCard(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onSearch(latLng.latitude, latLng.longitude) },
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
                }

                // Also allow nearby offset destination
                currentLocation?.let {
                    Spacer(Modifier.height(8.dp))
                    OutlinedCard(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                onSearch(it.latitude + 0.02, it.longitude + 0.02)
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
                            Text("📍 Nearby (~2km away)", fontSize = 13.sp)
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}

// --- Leaflet map update helpers ---

private fun drawRiskOverlays(
    ctrl: LeafletMapController,
    riskData: CombinedRiskData,
    filter: RiskFilter,
    safeRoutes: List<SafeRouteOption>,
    currentLocation: LatLng?,
    destination: LatLng?,
    safetyPlaces: List<SafetyPlace> = emptyList(),
    crimeZonesForMap: List<CrimeZoneData> = emptyList()
) {
    val circles = mutableListOf<CircleData>()
    val markers = mutableListOf<MarkerData>()
    val polylines = mutableListOf<PolylineData>()
    val boundsPoints = mutableListOf<LatLng>()

    // Crime risk circles & hotspot markers
    if (filter == RiskFilter.ALL || filter == RiskFilter.CRIME_ONLY) {
        for (zone in riskData.crimeZones) {
            val (fill, stroke) = when {
                zone.crimeRiskScore >= 0.5f -> "#F44336" to "#F44336"
                zone.crimeRiskScore >= 0.2f -> "#FF9800" to "#FF9800"
                else -> "#4CAF50" to "#4CAF50"
            }
            circles.add(CircleData(
                zone.location.latitude, zone.location.longitude,
                zone.radiusMeters.toDouble(), fill, stroke, 0.25, 0.5
            ))
            for (hotspot in zone.hotspots) {
                val color = when {
                    hotspot.risk >= 0.7f -> "#F44336"
                    hotspot.risk >= 0.4f -> "#FF9800"
                    else -> "#4CAF50"
                }
                markers.add(MarkerData(
                    hotspot.location.latitude, hotspot.location.longitude,
                    "\uD83D\uDD34 ${hotspot.label}",
                    "Crime Risk: ${(hotspot.risk * 100).toInt()}% | ${zone.city}",
                    color
                ))
            }
        }
    }

    // Disaster risk circles & markers
    if (filter == RiskFilter.ALL || filter == RiskFilter.DISASTER_ONLY) {
        for (zone in riskData.disasterZones) {
            if (zone.floodRisk >= 0.4f) {
                circles.add(CircleData(
                    zone.location.latitude, zone.location.longitude,
                    zone.radiusMeters.toDouble() * 0.8, "#2196F3", "#2196F3", 0.2, 0.4
                ))
            }
            if (zone.landslideRisk >= 0.4f) {
                circles.add(CircleData(
                    zone.location.latitude, zone.location.longitude,
                    zone.radiusMeters.toDouble() * 0.6, "#795548", "#795548", 0.2, 0.4
                ))
            }
            if (zone.combinedDisasterRisk >= 0.4f) {
                val typeLabel = when {
                    zone.floodRisk >= 0.5f && zone.landslideRisk >= 0.5f -> "\uD83C\uDF0A\u26F0\uFE0F Multi-hazard"
                    zone.floodRisk >= 0.5f -> "\uD83C\uDF0A Flood Risk"
                    zone.landslideRisk >= 0.5f -> "\u26F0\uFE0F Landslide Risk"
                    else -> "\u26A0\uFE0F Disaster Risk"
                }
                val bgColor = if (zone.floodRisk > zone.landslideRisk) "#2196F3" else "#795548"
                markers.add(MarkerData(
                    zone.location.latitude, zone.location.longitude,
                    "$typeLabel - ${zone.city}",
                    zone.riskFactors.take(2).joinToString(" \u2022 "),
                    bgColor
                ))
            }
        }
    }

    // Safe route — pick only the safest one, draw via OSRM after batchUpdate
    val safestRoute = if (safeRoutes.isNotEmpty()) {
        val best = safeRoutes.firstOrNull { it.isSafest }
            ?: safeRoutes.minByOrNull { it.totalRiskScore }
        best?.let { route ->
            boundsPoints.addAll(route.waypoints)
        }
        destination?.let {
            markers.add(MarkerData(it.latitude, it.longitude, "\uD83C\uDFAF Destination", "", "#9C27B0"))
        }
        best
    } else null

    // Safety places
    for (place in safetyPlaces) {
        val (emoji, bgColor) = when (place.type) {
            SafetyPlaceType.POLICE -> "\uD83D\uDE94" to "#1565C0"
            SafetyPlaceType.HOSPITAL -> "\uD83C\uDFE5" to "#D32F2F"
        }
        val snippet = if (place.address.isNotEmpty() && place.phoneNumber.isNotEmpty())
            "${place.address} \u2022 ${place.phoneNumber}"
        else if (place.address.isNotEmpty()) place.address
        else ""
        markers.add(MarkerData(
            place.location.latitude, place.location.longitude,
            "$emoji ${place.name}", snippet, bgColor, emoji, bgColor
        ))
    }

    currentLocation?.let { boundsPoints.add(it) }

    ctrl.batchUpdate(MapUpdateData(
        clear = true,
        currentLocation = currentLocation?.let { it.latitude to it.longitude },
        markers = markers,
        circles = circles,
        polylines = polylines,
        fitBoundsPoints = if (boundsPoints.size >= 2) boundsPoints else null
    ))

    // Draw only the safest route using OSRM with crime zone analysis
    safestRoute?.let { route ->
        if (route.waypoints.size >= 2) {
            val start = route.waypoints.first()
            val end = route.waypoints.last()
            ctrl.drawSafeRoute(start, end, crimeZonesForMap)
        }
    }
}

@SuppressLint("MissingPermission")
private fun fetchCurrentLocation(
    context: android.content.Context,
    onLocation: (LatLng) -> Unit
) {
    val fusedClient = LocationServices.getFusedLocationProviderClient(context)
    fusedClient.lastLocation.addOnSuccessListener { location ->
        location?.let {
            onLocation(LatLng(it.latitude, it.longitude))
        } ?: run {
            onLocation(LatLng(28.6139, 77.2090))
        }
    }.addOnFailureListener {
        onLocation(LatLng(28.6139, 77.2090))
    }
}