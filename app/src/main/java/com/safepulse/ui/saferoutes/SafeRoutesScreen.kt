package com.safepulse.ui.saferoutes

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.google.android.gms.location.LocationServices
import com.google.android.gms.maps.model.LatLng
import com.google.maps.android.PolyUtil
import com.safepulse.domain.riskmap.SafetyPlace
import com.safepulse.domain.riskmap.SafetyPlaceType
import com.safepulse.domain.saferoutes.*
import com.safepulse.ui.components.DestinationSearchDialogNew
import com.safepulse.ui.map.*
import kotlinx.coroutines.launch

/**
 * Safe Routes screen with Leaflet map integration
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SafeRoutesScreenWithMap(
    viewModel: SafeRoutesViewModel,
    onNavigateBack: () -> Unit,
    safetyPlaces: List<SafetyPlace> = emptyList(),
    onStartCommuteNavigation: (LatLng, SafeRoute?) -> Unit = { _, _ -> }
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val uiState by viewModel.uiState.collectAsState()
    val currentLocation by viewModel.currentLocation.collectAsState()
    val selectedRoute by viewModel.selectedRoute.collectAsState()
    val policeStations by viewModel.policeStations.collectAsState()
    val hospitals by viewModel.hospitals.collectAsState()
    val safeZones by viewModel.safeZones.collectAsState()
    val crimeZonesForMap by viewModel.crimeZonesForMap.collectAsState()
    val destination by viewModel.destination.collectAsState()
    
    var mapController by remember { mutableStateOf<LeafletMapController?>(null) }
    var showSearchDialog by remember { mutableStateOf(false) }
    var hasLocationPermission by remember { 
        mutableStateOf(
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.ACCESS_FINE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED
        )
    }
    
    val locationPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        hasLocationPermission = isGranted
        if (isGranted) {
            getCurrentLocation(context) { location ->
                viewModel.updateCurrentLocation(location)
                mapController?.animateTo(location, 15f)
            }
        }
    }
    
    // Request location on launch
    LaunchedEffect(Unit) {
        if (!hasLocationPermission) {
            locationPermissionLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION)
        } else {
            getCurrentLocation(context) { location ->
                viewModel.updateCurrentLocation(location)
            }
        }
    }
    
    // Update map when routes change — show only safest route via OSRM with crime zone analysis
    LaunchedEffect(uiState, safetyPlaces, mapController, destination) {
        val ctrl = mapController ?: return@LaunchedEffect
        val markers = mutableListOf<MarkerData>()
        val boundsPoints = mutableListOf<LatLng>()

        // Find the safest route (recommended or lowest risk)
        var safestRoute: SafeRoute? = null
        if (uiState is SafeRoutesUiState.Success) {
            val routes = (uiState as SafeRoutesUiState.Success).routes
            safestRoute = routes.firstOrNull { it.isRecommended }
                ?: routes.minByOrNull { it.riskScore }
            safestRoute?.let { route ->
                val points = PolyUtil.decode(route.polyline)
                boundsPoints.addAll(points)
            }
        }

        // Safety place markers
        val center = currentLocation ?: LatLng(28.6139, 77.2090)
        safetyPlaces.filter { place ->
            val dist = floatArrayOf(0f)
            android.location.Location.distanceBetween(
                center.latitude, center.longitude,
                place.location.latitude, place.location.longitude, dist
            )
            dist[0] <= 30_000f
        }.forEach { place ->
            val (emoji, bgColor) = when (place.type) {
                SafetyPlaceType.POLICE -> "\uD83D\uDE94" to "#1565C0"
                SafetyPlaceType.HOSPITAL -> "\uD83C\uDFE5" to "#D32F2F"
            }
            markers.add(MarkerData(
                place.location.latitude, place.location.longitude,
                "$emoji ${place.name}", "", bgColor, emoji, bgColor
            ))
        }

        ctrl.batchUpdate(MapUpdateData(
            clear = true,
            currentLocation = currentLocation?.let { it.latitude to it.longitude },
            markers = markers,
            fitBoundsPoints = if (boundsPoints.size >= 2) boundsPoints else null
        ))

        // Draw safest route using OSRM with crime zone risk analysis
        safestRoute?.let { route ->
            val points = PolyUtil.decode(route.polyline)
            if (points.size >= 2) {
                val start = points.first()
                val end = points.last()
                ctrl.drawSafeRoute(start, end, crimeZonesForMap)
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
                title = { Text("Safe Routes") },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.Default.ArrowBack, "Back")
                    }
                }
            )
        },
        floatingActionButton = {
            FloatingActionButton(
                onClick = { showSearchDialog = true }
            ) {
                Icon(Icons.Default.Search, "Search destination")
            }
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            // Leaflet Map
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
            ) {
                if (hasLocationPermission) {
                    LeafletMapView(
                        modifier = Modifier.fillMaxSize(),
                        onMapReady = { ctrl ->
                            mapController = ctrl
                            currentLocation?.let {
                                ctrl.setCenter(it.latitude, it.longitude, 15f)
                                ctrl.setCurrentLocation(it)
                            }
                        }
                    )
                } else {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(Color.LightGray),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(
                                Icons.Default.LocationOff,
                                contentDescription = null,
                                modifier = Modifier.size(48.dp)
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            Text("Location permission required")
                            Spacer(modifier = Modifier.height(8.dp))
                            Button(
                                onClick = {
                                    locationPermissionLauncher.launch(
                                        Manifest.permission.ACCESS_FINE_LOCATION
                                    )
                                }
                            ) {
                                Text("Grant Permission")
                            }
                        }
                    }
                }
            }
            
            // Routes list/info
            when (uiState) {
                is SafeRoutesUiState.Loading -> {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        CircularProgressIndicator()
                    }
                }
                
                is SafeRoutesUiState.Success -> {
                    val state = uiState as SafeRoutesUiState.Success
                    val safest = state.routes.firstOrNull { it.isRecommended }
                        ?: state.routes.minByOrNull { it.riskScore }
                    SafestRoutePanel(
                        route = safest,
                        destination = destination,
                        vehicleRecommendation = state.vehicleRecommendation,
                        onStartCommuteNavigation = { navDestination, route ->
                            onStartCommuteNavigation(navDestination, route)
                            openNavigation(context, navDestination)
                        }
                    )
                }
                
                is SafeRoutesUiState.Error -> {
                    ErrorMessage((uiState as SafeRoutesUiState.Error).message)
                }
                
                SafeRoutesUiState.Idle -> {
                    SearchPrompt()
                }
            }
        }
    }

    // Destination search dialog
    if (showSearchDialog) {
        DestinationSearchDialogNew(
            currentLocation = currentLocation,
            onDismiss = { showSearchDialog = false },
            onSearch = { destination ->
                showSearchDialog = false
                viewModel.searchSafeRoutes(destination)
            }
        )
    }
    
    DisposableEffect(Unit) {
        onDispose { /* Leaflet WebView cleaned up by Compose */ }
    }
}

@SuppressLint("MissingPermission")
private fun getCurrentLocation(
    context: android.content.Context,
    onLocation: (LatLng) -> Unit
) {
    val fusedLocationClient = LocationServices.getFusedLocationProviderClient(context)
    fusedLocationClient.lastLocation.addOnSuccessListener { location ->
        location?.let {
            onLocation(LatLng(it.latitude, it.longitude))
        }
    }
}

@Composable
fun SafestRoutePanel(
    route: SafeRoute?,
    destination: LatLng?,
    vehicleRecommendation: VehicleRecommendation,
    onStartCommuteNavigation: (LatLng, SafeRoute?) -> Unit
) {
    val routeDestination = remember(route, destination) {
        destination ?: route?.polyline
            ?.takeIf { it.isNotBlank() }
            ?.let { PolyUtil.decode(it).lastOrNull() }
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        VehicleRecommendationCard(vehicleRecommendation)

        Text(
            text = "Safest Route",
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold
        )

        route?.let {
            RouteCard(route = it, isSelected = true)
        }

        Button(
            onClick = {
                routeDestination?.let { onStartCommuteNavigation(it, route) }
            },
            enabled = routeDestination != null,
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp)
        ) {
            Icon(Icons.Default.Navigation, contentDescription = null)
            Spacer(modifier = Modifier.width(8.dp))
            Text("Start Safe Journey & Navigate")
        }
    }
}

@Composable
fun RouteCard(
    route: SafeRoute,
    isSelected: Boolean,
    onClick: (() -> Unit)? = null
) {
    val colors = CardDefaults.cardColors(
        containerColor = if (isSelected) MaterialTheme.colorScheme.primaryContainer
        else MaterialTheme.colorScheme.surface
    )

    val content: @Composable ColumnScope.() -> Unit = {
        Column(
            modifier = Modifier.padding(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = if (route.isRecommended) "✅ ${route.summary}" else route.summary,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = if (route.isRecommended) FontWeight.Bold else FontWeight.Normal
                )
                RiskBadge(route.riskLevel)
            }
            
            Spacer(modifier = Modifier.height(4.dp))
            
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    text = "${String.format("%.1f", route.distance / 1000f)} km",
                    style = MaterialTheme.typography.bodySmall
                )
                Text(
                    text = "${route.duration / 60} min",
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }
    }

    if (onClick != null) {
        Card(
            modifier = Modifier.fillMaxWidth(),
            onClick = onClick,
            colors = colors,
            content = content
        )
    } else {
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = colors,
            content = content
        )
    }
}

@Composable
fun RiskBadge(riskLevel: RiskLevel) {
    val color = when (riskLevel) {
        RiskLevel.LOW -> Color(0xFF4CAF50)
        RiskLevel.MEDIUM -> Color(0xFFFF9800)
        RiskLevel.HIGH -> Color(0xFFF44336)
    }
    
    val icon = when (riskLevel) {
        RiskLevel.LOW -> "🟢"
        RiskLevel.MEDIUM -> "🟠"
        RiskLevel.HIGH -> "🔴"
    }
    
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = color.copy(alpha = 0.2f)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(icon, style = MaterialTheme.typography.labelSmall)
            Spacer(modifier = Modifier.width(2.dp))
            Text(
                text = riskLevel.name,
                style = MaterialTheme.typography.labelSmall,
                color = color
            )
        }
    }
}

@Composable
fun VehicleRecommendationCard(recommendation: VehicleRecommendation) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer
        )
    ) {
        Column(
            modifier = Modifier.padding(12.dp)
        ) {
            Text(
                text = "🚗 ${recommendation.vehicle.displayName}",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = recommendation.reason,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
fun SearchPrompt() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(24.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Icon(
                Icons.Default.Search,
                contentDescription = null,
                modifier = Modifier.size(32.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = "Tap 🔍 to search destination",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
fun ErrorMessage(message: String) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(24.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Icon(
                Icons.Default.Error,
                contentDescription = null,
                modifier = Modifier.size(32.dp),
                tint = MaterialTheme.colorScheme.error
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = message,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error
            )
        }
    }
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
