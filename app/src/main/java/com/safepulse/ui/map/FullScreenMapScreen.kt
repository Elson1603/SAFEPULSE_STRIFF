package com.safepulse.ui.map

import android.Manifest
import android.annotation.SuppressLint
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.maps.model.LatLng
import com.safepulse.data.repository.RiskZoneRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Full-screen interactive map screen with advanced features:
 * - GPS location tracking with dynamic marker updates
 * - Tap-to-add markers with removal via popup
 * - OSRM routing between two points with distance/duration display
 * - Runtime location permission handling
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FullScreenMapScreen(
    onNavigateBack: () -> Unit
) {
    val context = LocalContext.current

    // Map controller
    var mapController by remember { mutableStateOf<LeafletMapController?>(null) }

    // Location state
    var currentLocation by remember { mutableStateOf<LatLng?>(null) }
    var hasLocationPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(
                context, Manifest.permission.ACCESS_FINE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED
        )
    }

    // UI mode state
    var tapToAddEnabled by remember { mutableStateOf(false) }
    var routingMode by remember { mutableStateOf(false) }
    var routeStart by remember { mutableStateOf<LatLng?>(null) }
    var routeEnd by remember { mutableStateOf<LatLng?>(null) }
    var userMarkerCount by remember { mutableIntStateOf(0) }

    // Route result state
    var routeDistance by remember { mutableStateOf<Double?>(null) }
    var routeDuration by remember { mutableStateOf<Int?>(null) }
    var routeError by remember { mutableStateOf<String?>(null) }
    var isLoadingRoute by remember { mutableStateOf(false) }

    // Snackbar
    val snackbarHostState = remember { SnackbarHostState() }

    // Permission launcher
    val locationPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        hasLocationPermission = granted
    }

    // Request permission on launch
    LaunchedEffect(Unit) {
        if (!hasLocationPermission) {
            locationPermissionLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION)
        }
    }

    // Start live location tracking when permission granted
    DisposableEffect(hasLocationPermission) {
        if (!hasLocationPermission) return@DisposableEffect onDispose { }

        val fusedClient = LocationServices.getFusedLocationProviderClient(context)
        val locationRequest = LocationRequest.Builder(
            Priority.PRIORITY_HIGH_ACCURACY, 5000L
        ).setMinUpdateIntervalMillis(2000L).build()

        val callback = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                result.lastLocation?.let { loc ->
                    val latLng = LatLng(loc.latitude, loc.longitude)
                    val isFirst = currentLocation == null
                    currentLocation = latLng
                    mapController?.setCurrentLocation(loc.latitude, loc.longitude)
                    if (isFirst) {
                        mapController?.animateTo(loc.latitude, loc.longitude, 15f)
                    }
                }
            }
        }

        @SuppressLint("MissingPermission")
        fun startUpdates() {
            fusedClient.requestLocationUpdates(
                locationRequest, callback, android.os.Looper.getMainLooper()
            )
        }

        // Also get last known immediately
        @SuppressLint("MissingPermission")
        fun getLastKnown() {
            fusedClient.lastLocation.addOnSuccessListener { loc ->
                loc?.let {
                    val latLng = LatLng(it.latitude, it.longitude)
                    if (currentLocation == null) {
                        currentLocation = latLng
                        mapController?.setCurrentLocation(it.latitude, it.longitude)
                        mapController?.animateTo(it.latitude, it.longitude, 15f)
                    }
                }
            }
        }

        getLastKnown()
        startUpdates()

        onDispose {
            fusedClient.removeLocationUpdates(callback)
        }
    }

    // Load bounded nearby layers; full-India marker layers make the WebView sluggish.
    LaunchedEffect(currentLocation, mapController) {
        val ctrl = mapController ?: return@LaunchedEffect
        val repo = RiskZoneRepository(context)
        val center = currentLocation ?: LatLng(28.6139, 77.2090)
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

    // Map callbacks
    val callbacks = remember {
        LeafletMapCallbacks(
            onMapReady = { ctrl ->
                mapController = ctrl
            },
            onMapTapped = { lat, lng ->
                if (routingMode) {
                    if (routeStart == null) {
                        routeStart = LatLng(lat, lng)
                        mapController?.addMarkerWithIcon(lat, lng, "Route Start", "", "\uD83D\uDFE2", "#4CAF50")
                    } else if (routeEnd == null) {
                        routeEnd = LatLng(lat, lng)
                        mapController?.addMarkerWithIcon(lat, lng, "Destination", "", "\uD83D\uDD34", "#F44336")
                        // Draw route
                        isLoadingRoute = true
                        routeError = null
                        mapController?.drawRoute(routeStart!!, LatLng(lat, lng))
                    }
                }
            },
            onMarkerAdded = { _, _, _ ->
                userMarkerCount++
            },
            onMarkerRemoved = { _ ->
                userMarkerCount = (userMarkerCount - 1).coerceAtLeast(0)
            },
            onRouteReady = { distKm, durMin, _ ->
                routeDistance = distKm
                routeDuration = durMin
                isLoadingRoute = false
            },
            onRouteError = { error ->
                routeError = error
                isLoadingRoute = false
            }
        )
    }

    // Show snackbar messages
    LaunchedEffect(routeError) {
        routeError?.let {
            snackbarHostState.showSnackbar("Route error: $it")
            routeError = null
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Interactive Map", fontWeight = FontWeight.Bold, fontSize = 18.sp)
                        currentLocation?.let {
                            Text(
                                "%.4f, %.4f".format(it.latitude, it.longitude),
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
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
                    // Toggle tap-to-add markers
                    IconButton(onClick = {
                        tapToAddEnabled = !tapToAddEnabled
                        if (tapToAddEnabled) {
                            routingMode = false
                            mapController?.enableTapToAdd()
                        } else {
                            mapController?.disableTapToAdd()
                        }
                    }) {
                        Icon(
                            Icons.Default.AddLocation,
                            "Toggle tap-to-add",
                            tint = if (tapToAddEnabled) Color(0xFF4CAF50)
                            else MaterialTheme.colorScheme.onSurface
                        )
                    }
                    // Toggle routing mode
                    IconButton(onClick = {
                        routingMode = !routingMode
                        if (routingMode) {
                            tapToAddEnabled = false
                            mapController?.disableTapToAdd()
                            routeStart = null
                            routeEnd = null
                            routeDistance = null
                            routeDuration = null
                            mapController?.clearRoutes()
                        }
                    }) {
                        Icon(
                            Icons.Default.Directions,
                            "Toggle routing",
                            tint = if (routingMode) Color(0xFF2196F3)
                            else MaterialTheme.colorScheme.onSurface
                        )
                    }
                }
            )
        },
        floatingActionButton = {
            Column(
                verticalArrangement = Arrangement.spacedBy(8.dp),
                horizontalAlignment = Alignment.End
            ) {
                // Clear all user markers
                if (userMarkerCount > 0) {
                    SmallFloatingActionButton(
                        onClick = {
                            mapController?.clearUserMarkers()
                            userMarkerCount = 0
                        },
                        containerColor = MaterialTheme.colorScheme.errorContainer
                    ) {
                        Icon(Icons.Default.Delete, "Clear markers", modifier = Modifier.size(20.dp))
                    }
                }

                // Clear route
                if (routeDistance != null || routeStart != null) {
                    SmallFloatingActionButton(
                        onClick = {
                            mapController?.clearRoutes()
                            routeStart = null
                            routeEnd = null
                            routeDistance = null
                            routeDuration = null
                        },
                        containerColor = MaterialTheme.colorScheme.errorContainer
                    ) {
                        Icon(Icons.Default.Close, "Clear route", modifier = Modifier.size(20.dp))
                    }
                }

                // My location FAB
                FloatingActionButton(
                    onClick = {
                        currentLocation?.let {
                            mapController?.animateTo(it.latitude, it.longitude, 16f)
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
                    callbacks = callbacks
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
                        Spacer(Modifier.height(12.dp))
                        Button(onClick = {
                            locationPermissionLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION)
                        }) {
                            Text("Grant Permission")
                        }
                    }
                }
            }

            // Mode indicator chips at top
            Row(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                AnimatedVisibility(visible = tapToAddEnabled, enter = fadeIn(), exit = fadeOut()) {
                    Surface(
                        shape = RoundedCornerShape(20.dp),
                        color = Color(0xFF4CAF50),
                        shadowElevation = 4.dp
                    ) {
                        Text(
                            "Tap map to add marker",
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                            color = Color.White,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Medium
                        )
                    }
                }
                AnimatedVisibility(visible = routingMode, enter = fadeIn(), exit = fadeOut()) {
                    val text = when {
                        routeStart == null -> "Tap START location"
                        routeEnd == null -> "Tap DESTINATION"
                        else -> "Route drawn"
                    }
                    Surface(
                        shape = RoundedCornerShape(20.dp),
                        color = Color(0xFF2196F3),
                        shadowElevation = 4.dp
                    ) {
                        Text(
                            text,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                            color = Color.White,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Medium
                        )
                    }
                }
            }

            // Loading indicator for route
            if (isLoadingRoute) {
                CircularProgressIndicator(
                    modifier = Modifier.align(Alignment.Center)
                )
            }

            // Route info card at bottom
            AnimatedVisibility(
                visible = routeDistance != null,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 80.dp, start = 16.dp, end = 16.dp),
                enter = fadeIn(),
                exit = fadeOut()
            ) {
                Card(
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.95f)
                    ),
                    elevation = CardDefaults.cardElevation(defaultElevation = 8.dp)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        horizontalArrangement = Arrangement.SpaceEvenly,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text("Distance", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(
                                "${routeDistance ?: 0.0} km",
                                fontWeight = FontWeight.Bold,
                                fontSize = 16.sp,
                                color = Color(0xFF2196F3)
                            )
                        }
                        Divider(
                            modifier = Modifier
                                .height(32.dp)
                                .width(1.dp)
                        )
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text("Duration", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(
                                "${routeDuration ?: 0} min",
                                fontWeight = FontWeight.Bold,
                                fontSize = 16.sp,
                                color = Color(0xFF4CAF50)
                            )
                        }
                    }
                }
            }

            // Marker count badge
            if (userMarkerCount > 0) {
                Surface(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(top = 8.dp, end = 8.dp),
                    shape = CircleShape,
                    color = Color(0xFFE91E63)
                ) {
                    Text(
                        "$userMarkerCount",
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                        color = Color.White,
                        fontWeight = FontWeight.Bold,
                        fontSize = 12.sp
                    )
                }
            }
        }
    }
}

private const val MAX_LAYER_MARKERS = 80
private const val MAX_SAFE_ZONE_MARKERS = 24
