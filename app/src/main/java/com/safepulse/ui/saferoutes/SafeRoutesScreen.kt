package com.safepulse.ui.saferoutes

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
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
import com.safepulse.domain.saferoutes.*
import com.safepulse.ui.components.DestinationSearchDialogNew
import com.safepulse.ui.map.*

/**
 * Safe Routes screen with Leaflet map integration
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SafeRoutesScreenWithMap(
    viewModel: SafeRoutesViewModel,
    onNavigateBack: () -> Unit,
    onStartCommuteNavigation: (LatLng, SafeRoute?) -> Unit = { _, _ -> }
) {
    val context = LocalContext.current
    val uiState by viewModel.uiState.collectAsState()
    val currentLocation by viewModel.currentLocation.collectAsState()
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
    LaunchedEffect(uiState, mapController, destination) {
        val ctrl = mapController ?: return@LaunchedEffect
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

        ctrl.batchUpdate(MapUpdateData(
            clear = true,
            currentLocation = currentLocation?.let { it.latitude to it.longitude },
            markers = emptyList(),
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
fun VehicleRecommendationCard(recommendation: VehicleRecommendation) {
    val accent = when (recommendation.vehicle) {
        RecommendedVehicle.AVOID_TRAVEL -> MaterialTheme.colorScheme.error
        RecommendedVehicle.TRACKED_CAB -> Color(0xFF42A5F5)
        RecommendedVehicle.AUTO_RICKSHAW -> Color(0xFFFFB74D)
        RecommendedVehicle.BIKE_TAXI -> Color(0xFFAB47BC)
        RecommendedVehicle.PUBLIC_BUS -> Color(0xFF2E7D32)
        RecommendedVehicle.WALK -> Color(0xFF43A047)
    }
    val icon = when (recommendation.vehicle) {
        RecommendedVehicle.AVOID_TRAVEL -> Icons.Default.Warning
        RecommendedVehicle.TRACKED_CAB -> Icons.Default.LocalTaxi
        RecommendedVehicle.AUTO_RICKSHAW -> Icons.Default.ElectricRickshaw
        RecommendedVehicle.BIKE_TAXI -> Icons.Default.TwoWheeler
        RecommendedVehicle.PUBLIC_BUS -> Icons.Default.DirectionsBus
        RecommendedVehicle.WALK -> Icons.Default.DirectionsWalk
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        ),
        border = BorderStroke(1.dp, accent.copy(alpha = 0.45f)),
        shape = RoundedCornerShape(12.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Surface(
                modifier = Modifier.size(44.dp),
                shape = RoundedCornerShape(10.dp),
                color = accent.copy(alpha = 0.16f)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        tint = accent,
                        modifier = Modifier.size(24.dp)
                    )
                }
            }

            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(
                    text = "Recommended Transport",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = recommendation.vehicle.displayName,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = recommendation.reason,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
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
