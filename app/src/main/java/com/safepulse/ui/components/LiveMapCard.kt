package com.safepulse.ui.components

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import com.google.android.gms.location.LocationServices
import com.google.android.gms.maps.model.LatLng
import com.google.maps.android.PolyUtil
import com.safepulse.ui.map.CircleData
import com.safepulse.ui.map.LeafletMapController
import com.safepulse.ui.map.LeafletMapView
import com.safepulse.ui.map.MapUpdateData
import com.safepulse.ui.map.MarkerData
import com.safepulse.ui.map.PolylineData
import com.safepulse.data.db.entity.HotspotEntity
import com.safepulse.data.repository.DisasterRepository
import com.safepulse.data.repository.HotspotRepository
import com.safepulse.data.repository.RiskZoneRepository
import com.safepulse.data.repository.SafeRoutesRepository
import com.safepulse.domain.saferoutes.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Expandable live map component with integrated route planning
 */
@Composable
fun LiveMapCard(
    currentLocation: com.google.android.gms.maps.model.LatLng?,
    crimeHotspots: List<HotspotEntity>,
    disasters: List<DisasterAlert>,
    onLocationUpdate: (com.google.android.gms.maps.model.LatLng) -> Unit,
    modifier: Modifier = Modifier
) {
    var isExpanded by remember { mutableStateOf(false) }
    var mapController: LeafletMapController? by remember { mutableStateOf(null) }
    val context = LocalContext.current

    // Route planning state
    var showSearchDialog by remember { mutableStateOf(false) }
    var isLoadingRoutes by remember { mutableStateOf(false) }
    var routes by remember { mutableStateOf<List<SafeRoute>>(emptyList()) }
    var selectedRoute by remember { mutableStateOf<SafeRoute?>(null) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    val hasLocationPermission = ContextCompat.checkSelfPermission(
        context,
        Manifest.permission.ACCESS_FINE_LOCATION
    ) == PackageManager.PERMISSION_GRANTED

    val scope = rememberCoroutineScope()

    // Update location
    LaunchedEffect(hasLocationPermission) {
        if (hasLocationPermission) {
            getCurrentLocation(context, onLocationUpdate)
        }
    }

    // Update map markers when data changes
    LaunchedEffect(crimeHotspots, disasters, currentLocation, routes, selectedRoute) {
        mapController?.let { controller ->
            updateLeafletContent(controller, crimeHotspots, disasters, currentLocation, routes, selectedRoute)
        }
    }

    // Load bounded nearby map layers. Full-India hospital/police layers are too heavy for WebView.
    LaunchedEffect(mapController, currentLocation) {
        val ctrl = mapController ?: return@LaunchedEffect
        val center = currentLocation ?: LatLng(28.6139, 77.2090)
        val repo = RiskZoneRepository(context)
        val stations = withContext(Dispatchers.IO) {
            repo.getPoliceStationsNear(center, 30.0).take(MAX_LAYER_MARKERS)
        }
        val hospitals = withContext(Dispatchers.IO) {
            repo.getHospitalsNear(center, 20.0).take(MAX_LAYER_MARKERS)
        }
        val safeZones = withContext(Dispatchers.IO) {
            repo.getSafeZonesNear(center, 50.0).take(MAX_SAFE_ZONE_MARKERS)
        }
        if (stations.isNotEmpty()) {
            ctrl.addPoliceStations(stations)
        }
        if (hospitals.isNotEmpty()) {
            ctrl.addHospitals(hospitals)
        }
        if (safeZones.isNotEmpty()) {
            ctrl.addSafeZones(safeZones)
        }
    }

    if (isExpanded) {
        // Full screen map with route planning
        Dialog(
            onDismissRequest = {
                isExpanded = false
                routes = emptyList()
                selectedRoute = null
            },
            properties = DialogProperties(usePlatformDefaultWidth = false)
        ) {
            Box(modifier = Modifier.fillMaxSize()) {
                LeafletMapView(
                    onMapReady = { controller ->
                        mapController = controller
                        currentLocation?.let { loc ->
                            controller.setCenter(loc.latitude, loc.longitude, 13f)
                            controller.setCurrentLocation(loc.latitude, loc.longitude)
                        }
                        updateLeafletContent(controller, crimeHotspots, disasters, currentLocation, routes, selectedRoute)
                    },
                    modifier = Modifier.fillMaxSize()
                )

                // Top bar with close button
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp)
                        .align(Alignment.TopCenter),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = MaterialTheme.colorScheme.surface
                    ) {
                        Text(
                            text = "Safe Routes",
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                    }

                    IconButton(
                        onClick = {
                            isExpanded = false
                            routes = emptyList()
                            selectedRoute = null
                        },
                        modifier = Modifier.background(Color.White, CircleShape)
                    ) {
                        Icon(Icons.Default.Close, "Close", tint = Color.Black)
                    }
                }

                // Search FAB
                if (routes.isEmpty()) {
                    FloatingActionButton(
                        onClick = { showSearchDialog = true },
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .padding(16.dp)
                    ) {
                        Icon(Icons.Default.Search, "Search destination")
                    }
                }

                // Route cards
                if (routes.isNotEmpty()) {
                    Column(
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .fillMaxWidth()
                            .padding(16.dp)
                    ) {
                        LazyColumn(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(max = 300.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            items(routes) { route ->
                                RouteCard(
                                    route = route,
                                    isSelected = route == selectedRoute,
                                    onClick = {
                                        selectedRoute = route
                                        mapController?.let { controller ->
                                            updateLeafletContent(controller, crimeHotspots, disasters, currentLocation, routes, route)
                                            // Zoom to route
                                            val points = PolyUtil.decode(route.polyline)
                                            if (points.isNotEmpty()) {
                                                controller.fitBounds(points)
                                            }
                                        }
                                    }
                                )
                            }
                        }
                    }
                }

                // Loading indicator
                if (isLoadingRoutes) {
                    CircularProgressIndicator(
                        modifier = Modifier.align(Alignment.Center)
                    )
                }

                // Error message
                errorMessage?.let { error ->
                    Snackbar(
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .padding(16.dp),
                        action = {
                            TextButton(onClick = { errorMessage = null }) {
                                Text("Dismiss")
                            }
                        }
                    ) {
                        Text(error)
                    }
                }

                // Map legend
                if (routes.isEmpty()) {
                    MapLegend(
                        modifier = Modifier
                            .align(Alignment.BottomStart)
                            .padding(16.dp)
                    )
                }
            }
        }

        // Search dialog
        if (showSearchDialog) {
            DestinationSearchDialog(
                currentLocation = currentLocation,
                onDismiss = { showSearchDialog = false },
                onSearch = { destination ->
                    showSearchDialog = false
                    if (currentLocation != null) {
                        scope.launch {
                            isLoadingRoutes = true
                            errorMessage = null
                            try {
                                android.util.Log.d("LiveMapCard", "Searching from $currentLocation to $destination")

                                // Initialize repositories
                                val hotspotRepository = HotspotRepository(
                                    (context.applicationContext as com.safepulse.SafePulseApplication)
                                        .database.hotspotDao()
                                )
                                val riskAnalyzer = RouteRiskAnalyzer(hotspotRepository)
                                val safeRoutesRepository = SafeRoutesRepository(
                                    context = context,
                                    riskAnalyzer = riskAnalyzer
                                )

                                android.util.Log.d("LiveMapCard", "Fetching routes...")
                                // Fetch routes
                                val fetchedRoutes = safeRoutesRepository.getSafeRoutes(currentLocation, destination)
                                android.util.Log.d("LiveMapCard", "Found ${fetchedRoutes.size} routes")

                                routes = fetchedRoutes
                                selectedRoute = routes.firstOrNull()

                                if (routes.isEmpty()) {
                                    errorMessage = "No routes found. Check logs or try TEST MODE."
                                    android.util.Log.w("LiveMapCard", "No routes returned")
                                } else {
                                    android.util.Log.d("LiveMapCard", "Routes: ${routes.joinToString { "${it.distance} - ${it.riskLevel}" }}")
                                    // Zoom to show routes
                                    mapController?.let { controller ->
                                        val allPoints = routes.flatMap { route -> PolyUtil.decode(route.polyline) }
                                        if (allPoints.isNotEmpty()) {
                                            controller.fitBounds(allPoints)
                                        }
                                    }
                                }
                            } catch (e: Exception) {
                                errorMessage = "API Error: ${e.message}. Try TEST MODE instead."
                                android.util.Log.e("LiveMapCard", "Route search error", e)
                                e.printStackTrace()
                            } finally {
                                isLoadingRoutes = false
                            }
                        }
                    } else {
                        errorMessage = "Current location not available"
                    }
                },
                onTestMode = { destination ->
                    showSearchDialog = false
                    // Create mock routes without API
                    android.util.Log.d("LiveMapCard", "TEST MODE: Creating mock routes")
                    val mockRoutes = createMockRoutesForTest(currentLocation ?: destination, destination)
                    routes = mockRoutes
                    selectedRoute = routes.firstOrNull()
                    android.util.Log.d("LiveMapCard", "TEST MODE: Created ${mockRoutes.size} mock routes")

                    // Zoom to destination
                    mapController?.animateTo(destination.latitude, destination.longitude,  -13f)
                }
            )
        }
    }

    // Compact map card
    Card(
        modifier = modifier
            .fillMaxWidth()
            .height(200.dp)
            .clickable { isExpanded = true },
        shape = RoundedCornerShape(16.dp)
    ) {
        Box {
            if (hasLocationPermission) {
                LeafletMapView(
                    onMapReady = { controller ->
                        mapController = controller
                        currentLocation?.let { loc ->
                            controller.setCenter(loc.latitude, loc.longitude, 13f)
                            controller.setCurrentLocation(loc.latitude, loc.longitude)
                        }
                        updateLeafletContent(controller, crimeHotspots, disasters, currentLocation, emptyList(), null)
                    },
                    modifier = Modifier.fillMaxSize()
                )
            } else {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.LightGray),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(Icons.Default.LocationOff, null, modifier = Modifier.size(32.dp))
                        Spacer(modifier = Modifier.height(8.dp))
                        Text("Location permission needed", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }

            // Tap to expand hint
            Surface(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(8.dp),
                shape = RoundedCornerShape(8.dp),
                color = MaterialTheme.colorScheme.surface.copy(alpha = 0.9f)
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        Icons.Default.Fullscreen,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Tap for routes", style = MaterialTheme.typography.labelSmall)
                }
            }

            // Stats overlay
            Surface(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(8.dp),
                shape = RoundedCornerShape(8.dp),
                color = MaterialTheme.colorScheme.surface.copy(alpha = 0.9f)
            ) {
                Row(
                    modifier = Modifier.padding(8.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    MapStat("🔴", crimeHotspots.size.toString(), "Crimes")
                    MapStat("⚠️", disasters.size.toString(), "Alerts")
                }
            }
        }
    }

    DisposableEffect(Unit) {
        onDispose { }
    }
}

@Composable
fun DestinationSearchDialog(
    currentLocation: com.google.android.gms.maps.model.LatLng?,
    onDismiss: () -> Unit,
    onSearch: (com.google.android.gms.maps.model.LatLng) -> Unit,
    onTestMode: (com.google.android.gms.maps.model.LatLng) -> Unit
) {
    var customInput by remember { mutableStateOf("") }
    var isGeocoding by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Search Destination") },
        text = {
            Column {
                Text("Enter any location:", style = MaterialTheme.typography.bodyMedium)
                Spacer(modifier = Modifier.height(12.dp))

                // Custom text input
                OutlinedTextField(
                    value = customInput,
                    onValueChange = {
                        customInput = it
                        errorMessage = null
                    },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Place or address") },
                    placeholder = { Text("e.g., Mumbai Airport") },
                    singleLine = true,
                    isError = errorMessage != null,
                    supportingText = errorMessage?.let { { Text(it, color = MaterialTheme.colorScheme.error) } }
                )

                Spacer(modifier = Modifier.height(16.dp))

                // Quick presets
                Text("Quick options:", style = MaterialTheme.typography.bodySmall)
                Spacer(modifier = Modifier.height(8.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedButton(
                        onClick = {
                            currentLocation?.let {
                                onSearch(com.google.android.gms.maps.model.LatLng(
                                    it.latitude + 0.01,
                                    it.longitude + 0.01
                                ))
                            }
                        },
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("1km", style = MaterialTheme.typography.labelSmall)
                    }
                    OutlinedButton(
                        onClick = {
                            currentLocation?.let {
                                onSearch(com.google.android.gms.maps.model.LatLng(
                                    it.latitude + 0.02,
                                    it.longitude + 0.02
                                ))
                            }
                        },
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("2km", style = MaterialTheme.typography.labelSmall)
                    }
                    OutlinedButton(
                        onClick = {
                            currentLocation?.let {
                                onSearch(com.google.android.gms.maps.model.LatLng(
                                    it.latitude + 0.05,
                                    it.longitude + 0.05
                                ))
                            }
                        },
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("5km", style = MaterialTheme.typography.labelSmall)
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
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                // TEST MODE button
                OutlinedButton(
                    onClick = {
                        val testDest = currentLocation?.let {
                            com.google.android.gms.maps.model.LatLng(it.latitude + 0.02, it.longitude + 0.02)
                        } ?: com.google.android.gms.maps.model.LatLng(19.0760, 72.8777)
                        onTestMode(testDest)
                    }
                ) {
                    Text("TEST MODE")
                }

                // Regular search button
                Button(
                    onClick = {
                        if (customInput.isBlank()) {
                            errorMessage = "Please enter a location"
                            return@Button
                        }

                        scope.launch {
                            isGeocoding = true
                            errorMessage = null
                            try {
                                android.util.Log.d("DestinationSearch", "Searching for: $customInput")
                                val geocoder = android.location.Geocoder(context)
                                val addresses = geocoder.getFromLocationName(customInput, 1)

                                if (!addresses.isNullOrEmpty()) {
                                    val address = addresses[0]
                                    android.util.Log.d("DestinationSearch", "Found: ${address.getAddressLine(0)} at ${address.latitude}, ${address.longitude}")
                                    onSearch(com.google.android.gms.maps.model.LatLng(
                                        address.latitude,
                                        address.longitude
                                    ))
                                } else {
                                    errorMessage = "Location not found. Click TEST MODE to see demo routes."
                                    android.util.Log.w("DestinationSearch", "No results for: $customInput")
                                }
                            } catch (e: Exception) {
                                errorMessage = "Search failed. Click TEST MODE to see demo routes."
                                android.util.Log.e("DestinationSearch", "Geocoding error for: $customInput", e)
                            } finally {
                                isGeocoding = false
                            }
                        }
                    },
                    enabled = !isGeocoding && customInput.isNotBlank()
                ) {
                    Text("Search")
                }
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
fun RouteCard(
    route: SafeRoute,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        colors = CardDefaults.cardColors(
            containerColor = if (isSelected)
                MaterialTheme.colorScheme.primaryContainer
            else
                MaterialTheme.colorScheme.surface
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    RiskBadge(route.riskLevel)
                    if (route.isRecommended) {
                        Spacer(modifier = Modifier.width(8.dp))
                        Surface(
                            shape = RoundedCornerShape(4.dp),
                            color = Color(0xFF4CAF50)
                        ) {
                            Text(
                                "Safest",
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                style = MaterialTheme.typography.labelSmall,
                                color = Color.White
                            )
                        }
                    }
                }
                Spacer(modifier = Modifier.height(4.dp))

                // Convert Long values to readable strings if needed
                val distanceText = if (route.distance >= 1000) "%.1f km".format(route.distance / 1000f) else "${route.distance} m"
                val durationText = "${route.duration / 60} mins"

                Text(
                    "$distanceText • $durationText",
                    style = MaterialTheme.typography.bodyMedium
                )
            }

            Icon(
                if (isSelected) Icons.Default.CheckCircle else Icons.Default.ChevronRight,
                contentDescription = null,
                tint = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
            )
        }
    }
}

@Composable
fun RiskBadge(riskLevel: RiskLevel) {
    val (color, text) = when (riskLevel) {
        RiskLevel.LOW -> Color(0xFF4CAF50) to "Low Risk"
        RiskLevel.MEDIUM -> Color(0xFFFF9800) to "Medium"
        RiskLevel.HIGH -> Color(0xFFF44336) to "High Risk"
    }

    Surface(
        shape = RoundedCornerShape(4.dp),
        color = color
    ) {
        Text(
            text,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            style = MaterialTheme.typography.labelSmall,
            color = Color.White,
            fontWeight = FontWeight.Bold
        )
    }
}

fun updateLeafletContent(
    controller: LeafletMapController,
    crimeHotspots: List<HotspotEntity>,
    disasters: List<DisasterAlert>,
    currentLocation: com.google.android.gms.maps.model.LatLng?,
    routes: List<SafeRoute>,
    selectedRoute: SafeRoute?
) {
    val markers = mutableListOf<MarkerData>()
    val circles = mutableListOf<CircleData>()
    val polylines = mutableListOf<PolylineData>()

    // Routes (drawn first so they appear under markers)
    routes.forEach { route ->
        val points = PolyUtil.decode(route.polyline)
        val isSelected = route == selectedRoute
        val color = when {
            isSelected -> "#2196F3"
            route.riskLevel == RiskLevel.LOW -> "#4CAF50"
            route.riskLevel == RiskLevel.MEDIUM -> "#FF9800"
            else -> "#F44336"
        }
        polylines.add(PolylineData(
            points = points,
            color = color,
            weight = if (isSelected) 6 else 4
        ))
    }

    // Crime hotspot markers
    crimeHotspots.forEach { hotspot ->
        markers.add(MarkerData(hotspot.lat, hotspot.lng, "Crime Hotspot", color = "#F44336"))
    }

    // Disaster zones
    disasters.forEach { disaster ->
        val color = when (disaster.severity) {
            Severity.LOW -> "#FFEB3B"
            Severity.MODERATE -> "#FF9800"
            Severity.HIGH, Severity.CRITICAL -> "#F44336"
        }
        circles.add(CircleData(
            lat = disaster.location.latitude,
            lng = disaster.location.longitude,
            radius = disaster.radius * 1000.0,
            fillColor = color,
            strokeColor = color
        ))
        markers.add(MarkerData(
            disaster.location.latitude,
            disaster.location.longitude,
            "${disaster.type.name} Alert",
            snippet = disaster.description,
            color = "#FF9800"
        ))
    }

    controller.batchUpdate(MapUpdateData(
        clear = true,
        currentLocation = currentLocation?.let { it.latitude to it.longitude },
        markers = markers,
        circles = circles,
        polylines = polylines,
        fitToLayers = routes.isEmpty() && markers.isNotEmpty()
    ))
}

@SuppressLint("MissingPermission")
fun getCurrentLocation(
    context: Context,
    onLocation: (com.google.android.gms.maps.model.LatLng) -> Unit
) {
    val fusedLocationClient = LocationServices.getFusedLocationProviderClient(context)
    fusedLocationClient.lastLocation.addOnSuccessListener { location ->
        location?.let {
            onLocation(com.google.android.gms.maps.model.LatLng(it.latitude, it.longitude))
        }
    }
}

@Composable
fun MapStat(icon: String, value: String, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(icon)
        Spacer(modifier = Modifier.width(4.dp))
        Column {
            Text(
                text = value,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
            )
        }
    }
}

@Composable
fun MapLegend(modifier: Modifier = Modifier) {
    Card(
        modifier = modifier,
        shape = RoundedCornerShape(8.dp)
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Text(
                "Legend",
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold
            )
            LegendItem("🔴", "Crime Hotspot")
            LegendItem("⚠️", "Disaster Alert")
            LegendItem("📍", "Your Location")
        }
    }
}

@Composable
fun LegendItem(icon: String, label: String) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Text(icon, style = MaterialTheme.typography.bodySmall)
        Text(label, style = MaterialTheme.typography.bodySmall)
    }
}

/**
 * Create mock routes for testing without API calls
 */
fun createMockRoutesForTest(
    origin: com.google.android.gms.maps.model.LatLng,
    destination: com.google.android.gms.maps.model.LatLng
): List<SafeRoute> {
    // Create 3 different paths
    val route1Points = listOf(origin, destination)
    val route2Points = listOf(
        origin,
        com.google.android.gms.maps.model.LatLng(
            (origin.latitude + destination.latitude) / 2,
            (origin.longitude + destination.longitude) / 2 + 0.005
        ),
        destination
    )
    val route3Points = listOf(
        origin,
        com.google.android.gms.maps.model.LatLng(
            (origin.latitude + destination.latitude) / 2,
            (origin.longitude + destination.longitude) / 2 - 0.005
        ),
        destination
    )

    // Encode polylines
    val route1Polyline = PolyUtil.encode(route1Points)
    val route2Polyline = PolyUtil.encode(route2Points)
    val route3Polyline = PolyUtil.encode(route3Points)

    return listOf(
        SafeRoute(
            id = "test1",
            polyline = route1Polyline,
            distance = 3200L, // Changed from "3.2 km" to Long
            duration = 480L,  // Changed from "8 mins" to Long
            riskScore = 0.2f, // Changed from 0.2 to Float
            riskLevel = RiskLevel.LOW,
            summary = "Direct Route (Safest)",
            isRecommended = true
        ),
        SafeRoute(
            id = "test2",
            polyline = route2Polyline,
            distance = 3800L, // Changed from "3.8 km" to Long
            duration = 600L,  // Changed from "10 mins" to Long
            riskScore = 0.5f, // Changed from 0.5 to Float
            riskLevel = RiskLevel.MEDIUM,
            summary = "Via North Side",
            isRecommended = false
        ),
        SafeRoute(
            id = "test3",
            polyline = route3Polyline,
            distance = 4100L, // Changed from "4.1 km" to Long
            duration = 720L,  // Changed from "12 mins" to Long
            riskScore = 0.8f, // Changed from 0.8 to Float
            riskLevel = RiskLevel.HIGH,
            summary = "Via South Side",
            isRecommended = false
        )
    )
}

private const val MAX_LAYER_MARKERS = 80
private const val MAX_SAFE_ZONE_MARKERS = 24
