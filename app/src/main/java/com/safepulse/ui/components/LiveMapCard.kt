package com.safepulse.ui.components

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
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
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import com.google.android.gms.location.LocationServices
import com.google.android.gms.maps.model.LatLng
import com.google.maps.android.PolyUtil
import com.safepulse.ui.map.CrimeZoneData
import com.safepulse.ui.map.LeafletMapController
import com.safepulse.ui.map.LeafletMapCallbacks
import com.safepulse.ui.map.LeafletMapView
import com.safepulse.ui.map.MapUpdateData
import com.safepulse.ui.map.MarkerData
import com.safepulse.data.db.entity.HotspotEntity
import com.safepulse.data.repository.RiskZoneRepository
import com.safepulse.domain.riskmap.SafeRouteOption
import com.safepulse.domain.riskmap.SafetyPlaceType
import com.safepulse.domain.saferoutes.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Expandable live map component with integrated route planning
 */
private enum class NearbyMapCategory { ALL, POLICE, HOSPITAL }

private data class MapNearbySafetyItem(
    val id: String,
    val name: String,
    val category: NearbyMapCategory,
    val location: LatLng,
    val distanceKm: Double,
    val riskScore: Float,
    val riskLabel: String,
    val subtitle: String = "",
    val phoneNumber: String = ""
)

private data class MapSelectedSafetyDetail(
    val item: MapNearbySafetyItem,
    val safestRoute: SafeRouteOption?,
    val recommendation: VehicleRecommendation
)

private data class NearbyMapLoadResult(
    val items: List<MapNearbySafetyItem>,
    val crimeZones: List<CrimeZoneData>
)

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

    var nearbyItems by remember { mutableStateOf<List<MapNearbySafetyItem>>(emptyList()) }
    var nearbyLoading by remember { mutableStateOf(false) }
    var nearbyError by remember { mutableStateOf<String?>(null) }
    var selectedNearbyCategory by remember { mutableStateOf<NearbyMapCategory?>(null) }
    var selectedNearbyDetail by remember { mutableStateOf<MapSelectedSafetyDetail?>(null) }
    var crimeZonesForRoutes by remember { mutableStateOf<List<CrimeZoneData>>(emptyList()) }

    val filteredNearbyItems = remember(nearbyItems, selectedNearbyCategory) {
        when (selectedNearbyCategory) {
            null -> emptyList()
            NearbyMapCategory.ALL -> nearbyItems
            else -> nearbyItems.filter { it.category == selectedNearbyCategory }
        }
    }

    val hasLocationPermission = ContextCompat.checkSelfPermission(
        context,
        Manifest.permission.ACCESS_FINE_LOCATION
    ) == PackageManager.PERMISSION_GRANTED

    val scope = rememberCoroutineScope()

    val selectNearbyItem: (MapNearbySafetyItem) -> Unit = { item ->
        currentLocation?.let { origin ->
            scope.launch {
                selectedNearbyDetail = null
                val detail = withContext(Dispatchers.IO) {
                    buildNearbySafetyDetail(context, origin, item, disasters)
                }
                selectedNearbyDetail = detail
                mapController?.drawSafeRoute(origin, item.location, crimeZonesForRoutes)
                mapController?.fitBounds(listOf(origin, item.location))
            }
        }
    }

    // Update location
    LaunchedEffect(hasLocationPermission) {
        if (hasLocationPermission) {
            getCurrentLocation(context, onLocationUpdate)
        }
    }

    // Update map markers when data changes
    LaunchedEffect(
        currentLocation,
        filteredNearbyItems,
        selectedNearbyDetail,
        crimeZonesForRoutes
    ) {
        mapController?.let { controller ->
            updateLeafletContent(
                controller,
                currentLocation,
                filteredNearbyItems,
                selectedNearbyDetail?.item
            )
            val selectedItem = selectedNearbyDetail?.item
            if (currentLocation != null && selectedItem != null) {
                controller.drawSafeRoute(currentLocation, selectedItem.location, crimeZonesForRoutes)
                controller.fitBounds(listOf(currentLocation, selectedItem.location))
            }
        }
    }

    LaunchedEffect(currentLocation) {
        val location = currentLocation ?: return@LaunchedEffect
        nearbyLoading = true
        nearbyError = null
        selectedNearbyDetail = null
        try {
            val result = withContext(Dispatchers.IO) {
                loadNearbySafetyForMap(context, location)
            }
            nearbyItems = result.items
            crimeZonesForRoutes = result.crimeZones
        } catch (e: Exception) {
            nearbyError = e.message ?: "Unable to load nearby safety places"
        } finally {
            nearbyLoading = false
        }
    }

    if (isExpanded) {
        Dialog(
            onDismissRequest = { isExpanded = false },
            properties = DialogProperties(usePlatformDefaultWidth = false)
        ) {
            Box(modifier = Modifier.fillMaxSize()) {
                LeafletMapView(
                    callbacks = LeafletMapCallbacks(
                        onMapReady = { controller ->
                            mapController = controller
                            currentLocation?.let { loc ->
                                controller.setCenter(loc.latitude, loc.longitude, 13f)
                                controller.setCurrentLocation(loc.latitude, loc.longitude)
                            }
                            updateLeafletContent(
                                controller,
                                currentLocation,
                                filteredNearbyItems,
                                selectedNearbyDetail?.item
                            )
                        },
                        onMarkerClicked = { markerId ->
                            nearbyItems.firstOrNull { it.id == markerId }?.let(selectNearbyItem)
                        }
                    ),
                    modifier = Modifier.fillMaxSize()
                )

                IconButton(
                    onClick = { isExpanded = false },
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(16.dp)
                        .background(Color.White, CircleShape)
                ) {
                    Icon(Icons.Default.Close, "Close", tint = Color.Black)
                }

                NearbySafetyMapPanel(
                    items = filteredNearbyItems,
                    allItems = nearbyItems,
                    selectedCategory = selectedNearbyCategory,
                    selectedDetail = selectedNearbyDetail,
                    isLoading = nearbyLoading,
                    error = nearbyError,
                    hasLocationPermission = hasLocationPermission,
                    onCategorySelected = {
                        selectedNearbyCategory = it
                        selectedNearbyDetail = null
                        mapController?.clearRoutes()
                    },
                    onItemSelected = selectNearbyItem,
                    onClearSelection = {
                        selectedNearbyDetail = null
                        mapController?.clearRoutes()
                    },
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(16.dp)
                )
            }
        }
    }

    Column(modifier = modifier.fillMaxWidth()) {
        // Compact map card
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .height(200.dp)
                .clickable { isExpanded = true },
            shape = RoundedCornerShape(16.dp)
        ) {
            Box {
            if (hasLocationPermission) {
                LeafletMapView(
                    callbacks = LeafletMapCallbacks(
                        onMapReady = { controller ->
                            mapController = controller
                            currentLocation?.let { loc ->
                                controller.setCenter(loc.latitude, loc.longitude, 13f)
                                controller.setCurrentLocation(loc.latitude, loc.longitude)
                            }
                            updateLeafletContent(
                                controller,
                                currentLocation,
                                filteredNearbyItems,
                                selectedNearbyDetail?.item
                            )
                        },
                        onMarkerClicked = { markerId ->
                            nearbyItems.firstOrNull { it.id == markerId }?.let(selectNearbyItem)
                        }
                    ),
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
                    Text("Tap for nearby safety", style = MaterialTheme.typography.labelSmall)
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
    }

    DisposableEffect(Unit) {
        onDispose { }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun NearbySafetyMapPanel(
    items: List<MapNearbySafetyItem>,
    allItems: List<MapNearbySafetyItem>,
    selectedCategory: NearbyMapCategory?,
    selectedDetail: MapSelectedSafetyDetail?,
    isLoading: Boolean,
    error: String?,
    hasLocationPermission: Boolean,
    onCategorySelected: (NearbyMapCategory) -> Unit,
    onItemSelected: (MapNearbySafetyItem) -> Unit,
    onClearSelection: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)
        )
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    Icons.Default.VerifiedUser,
                    contentDescription = null,
                    tint = Color(0xFF2E7D32),
                    modifier = Modifier.size(24.dp)
                )
                Spacer(modifier = Modifier.width(10.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text("Nearby Safety", fontWeight = FontWeight.SemiBold)
                    Text(
                        "Very-nearby police and hospitals",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                selectedDetail?.let {
                    IconButton(onClick = onClearSelection) {
                        Icon(Icons.Default.Close, contentDescription = "Clear route")
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            NearbyCategoryRow(
                selectedCategory = selectedCategory,
                allItems = allItems,
                onCategorySelected = onCategorySelected
            )

            Spacer(modifier = Modifier.height(12.dp))

            when {
                !hasLocationPermission -> {
                    NearbyPanelMessage(
                        icon = Icons.Default.LocationOff,
                        text = "Location permission is needed to find nearby safety places."
                    )
                }
                isLoading -> {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Finding nearby safety places...", style = MaterialTheme.typography.bodySmall)
                    }
                }
                error != null -> {
                    NearbyPanelMessage(icon = Icons.Default.Error, text = error)
                }
                selectedDetail != null -> {
                    NearbySelectedDetail(
                        detail = selectedDetail,
                        onBack = onClearSelection
                    )
                }
                selectedCategory == null -> {
                    NearbyPanelMessage(
                        icon = Icons.Default.TouchApp,
                        text = "Choose All, Police, or Hospitals to show nearby safety places on the map."
                    )
                }
                items.isEmpty() -> {
                    NearbyPanelMessage(
                        icon = Icons.Default.SearchOff,
                        text = "No nearby safety places found for this category."
                    )
                }
                else -> {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        items.take(3).forEach { item ->
                            NearbyMapItemRow(item = item, onClick = { onItemSelected(item) })
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun NearbyCategoryRow(
    selectedCategory: NearbyMapCategory?,
    allItems: List<MapNearbySafetyItem>,
    onCategorySelected: (NearbyMapCategory) -> Unit
) {
    val chips = listOf(
        CategoryChipInfo(NearbyMapCategory.ALL, "All", Icons.Default.Shield),
        CategoryChipInfo(NearbyMapCategory.POLICE, "Police", Icons.Default.LocalPolice),
        CategoryChipInfo(NearbyMapCategory.HOSPITAL, "Hospitals", Icons.Default.LocalHospital)
    )

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        chips.forEach { chip ->
            val count = if (chip.category == NearbyMapCategory.ALL) {
                allItems.size
            } else {
                allItems.count { it.category == chip.category }
            }
            FilterChip(
                selected = selectedCategory == chip.category,
                onClick = { onCategorySelected(chip.category) },
                label = { Text("${chip.label} $count", maxLines = 1) },
                leadingIcon = {
                    Icon(chip.icon, contentDescription = null, modifier = Modifier.size(16.dp))
                }
            )
        }
    }
}

private data class CategoryChipInfo(
    val category: NearbyMapCategory,
    val label: String,
    val icon: ImageVector
)

@Composable
private fun NearbyMapItemRow(
    item: MapNearbySafetyItem,
    onClick: () -> Unit
) {
    val (icon, color) = nearbyIconAndColor(item.category)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.72f))
            .clickable(onClick = onClick)
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(38.dp)
                .clip(CircleShape)
                .background(color.copy(alpha = 0.14f)),
            contentAlignment = Alignment.Center
        ) {
            Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(22.dp))
        }
        Spacer(modifier = Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                item.name,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                "${"%.1f".format(item.distanceKm)} km | Risk ${item.riskLabel}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        RiskTextBadge(item.riskLabel, item.riskScore)
        Icon(
            Icons.Default.Route,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.55f),
            modifier = Modifier.size(20.dp)
        )
    }
}

@Composable
private fun NearbySelectedDetail(
    detail: MapSelectedSafetyDetail,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val item = detail.item
    val phoneNumber = item.phoneNumber.ifBlank {
        when (item.category) {
            NearbyMapCategory.POLICE -> "112"
            NearbyMapCategory.HOSPITAL -> "108"
            NearbyMapCategory.ALL -> ""
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack, modifier = Modifier.size(34.dp)) {
                Icon(Icons.Default.ArrowBack, contentDescription = "Back", modifier = Modifier.size(18.dp))
            }
            Spacer(modifier = Modifier.width(6.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(item.name, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    "${"%.1f".format(item.distanceKm)} km away | ${item.riskLabel} risk",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        detail.safestRoute?.let { route ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .background(Color(0xFF2E7D32).copy(alpha = 0.1f))
                    .padding(10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Icons.Default.Route, contentDescription = null, tint = Color(0xFF2E7D32))
                Spacer(modifier = Modifier.width(8.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text("Safest route", fontWeight = FontWeight.SemiBold)
                    Text(
                        "${"%.1f".format(route.distanceKm)} km | Risk ${(route.totalRiskScore * 100).toInt()}%",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(10.dp))
                .background(MaterialTheme.colorScheme.primaryContainer)
                .padding(10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(Icons.Default.DirectionsCar, contentDescription = null)
            Spacer(modifier = Modifier.width(8.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(detail.recommendation.vehicle.displayName, fontWeight = FontWeight.SemiBold)
                Text(
                    detail.recommendation.reason,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(
                onClick = { openMapNavigation(context, item.location) },
                modifier = Modifier.weight(1f),
                shape = RoundedCornerShape(12.dp)
            ) {
                Icon(Icons.Default.Navigation, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(6.dp))
                Text("Navigate")
            }
            Button(
                onClick = { openPhoneDialer(context, phoneNumber) },
                enabled = phoneNumber.isNotBlank(),
                modifier = Modifier.weight(1f),
                shape = RoundedCornerShape(12.dp)
            ) {
                Icon(Icons.Default.Call, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(6.dp))
                Text("Call")
            }
        }
    }
}

@Composable
private fun NearbyPanelMessage(icon: ImageVector, text: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.72f))
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(modifier = Modifier.width(10.dp))
        Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun RiskTextBadge(label: String, score: Float) {
    val color = riskColor(score)
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(color.copy(alpha = 0.14f))
            .padding(horizontal = 8.dp, vertical = 4.dp)
    ) {
        Text(label, color = color, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
    }
    Spacer(modifier = Modifier.width(8.dp))
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

private fun loadNearbySafetyForMap(
    context: Context,
    location: LatLng
): NearbyMapLoadResult {
    val repository = RiskZoneRepository(context)
    val items = mutableListOf<MapNearbySafetyItem>()

    repository.getSafetyPlacesNear(location, NEARBY_SAFETY_RADIUS_KM)
        .take(MAX_NEARBY_ITEMS)
        .forEach { place ->
            val distance = RiskZoneRepository.distanceKm(location, place.location)
            val riskScore = repository.computeRiskAtLocation(place.location)
            items.add(
                MapNearbySafetyItem(
                    id = "place_${place.id}",
                    name = place.name,
                    category = when (place.type) {
                        SafetyPlaceType.POLICE -> NearbyMapCategory.POLICE
                        SafetyPlaceType.HOSPITAL -> NearbyMapCategory.HOSPITAL
                    },
                    location = place.location,
                    distanceKm = distance,
                    riskScore = riskScore,
                    riskLabel = riskLabelForScore(riskScore),
                    subtitle = place.address.ifBlank { place.city },
                    phoneNumber = place.phoneNumber
                )
            )
        }

    return NearbyMapLoadResult(
        items = items.sortedBy { it.distanceKm },
        crimeZones = repository.getCrimeZonesForMap()
    )
}

private fun buildNearbySafetyDetail(
    context: Context,
    origin: LatLng,
    item: MapNearbySafetyItem,
    disasters: List<DisasterAlert>
): MapSelectedSafetyDetail {
    val repository = RiskZoneRepository(context)
    val routeOptions = repository.suggestSafeWaypoints(origin, item.location)
    val safestRoute = routeOptions.firstOrNull { it.isSafest }
        ?: routeOptions.minByOrNull { it.totalRiskScore }
    val distanceKm = safestRoute?.distanceKm?.toDouble() ?: item.distanceKm
    val routeRisk = safestRoute?.totalRiskScore ?: item.riskScore
    val syntheticRoute = SafeRoute(
        id = "nearby_${item.id}",
        polyline = "",
        distance = (distanceKm * 1000).toLong(),
        duration = (distanceKm * 3 * 60).toLong(),
        riskScore = routeRisk,
        riskLevel = riskLevelForScore(routeRisk),
        summary = "Route to ${item.name}",
        isRecommended = true
    )
    return MapSelectedSafetyDetail(
        item = item,
        safestRoute = safestRoute,
        recommendation = VehicleRecommender().recommendVehicle(syntheticRoute, disasters)
    )
}

private fun nearbyIconAndColor(category: NearbyMapCategory): Pair<ImageVector, Color> {
    return when (category) {
        NearbyMapCategory.POLICE -> Icons.Default.LocalPolice to Color(0xFF1565C0)
        NearbyMapCategory.HOSPITAL -> Icons.Default.LocalHospital to Color(0xFFD32F2F)
        NearbyMapCategory.ALL -> Icons.Default.Place to Color(0xFF607D8B)
    }
}

private data class NearbyMarkerStyle(
    val label: String,
    val color: String
)

private fun nearbyMarkerStyle(category: NearbyMapCategory, selected: Boolean): NearbyMarkerStyle {
    if (selected) return NearbyMarkerStyle("!", "#111827")
    return when (category) {
        NearbyMapCategory.POLICE -> NearbyMarkerStyle("P", "#1565C0")
        NearbyMapCategory.HOSPITAL -> NearbyMarkerStyle("H", "#D32F2F")
        NearbyMapCategory.ALL -> NearbyMarkerStyle("N", "#607D8B")
    }
}

private fun riskLabelForScore(score: Float): String {
    return when {
        score >= 0.7f -> "HIGH"
        score >= 0.4f -> "MEDIUM"
        else -> "LOW"
    }
}

private fun riskLevelForScore(score: Float): RiskLevel {
    return when {
        score >= 0.7f -> RiskLevel.HIGH
        score >= 0.4f -> RiskLevel.MEDIUM
        else -> RiskLevel.LOW
    }
}

private fun riskColor(score: Float): Color {
    return when {
        score >= 0.7f -> Color(0xFFF44336)
        score >= 0.4f -> Color(0xFFFF9800)
        else -> Color(0xFF4CAF50)
    }
}

private fun openPhoneDialer(context: Context, phoneNumber: String) {
    if (phoneNumber.isBlank()) return
    val intent = Intent(Intent.ACTION_DIAL).apply {
        data = Uri.fromParts("tel", phoneNumber, null)
        flags = Intent.FLAG_ACTIVITY_NEW_TASK
    }
    context.startActivity(intent)
}

private fun openMapNavigation(context: Context, destination: LatLng) {
    val mapsIntent = Intent(
        Intent.ACTION_VIEW,
        Uri.parse("google.navigation:q=${destination.latitude},${destination.longitude}")
    ).apply {
        setPackage("com.google.android.apps.maps")
        flags = Intent.FLAG_ACTIVITY_NEW_TASK
    }

    try {
        context.startActivity(mapsIntent)
    } catch (e: Exception) {
        val fallbackIntent = Intent(
            Intent.ACTION_VIEW,
            Uri.parse("https://www.google.com/maps/dir/?api=1&destination=${destination.latitude},${destination.longitude}")
        ).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        context.startActivity(fallbackIntent)
    }
}

private fun updateLeafletContent(
    controller: LeafletMapController,
    currentLocation: com.google.android.gms.maps.model.LatLng?,
    nearbyItems: List<MapNearbySafetyItem> = emptyList(),
    selectedNearbyItem: MapNearbySafetyItem? = null
) {
    val markers = mutableListOf<MarkerData>()

    val nearbyForMap = if (selectedNearbyItem != null && nearbyItems.none { it.id == selectedNearbyItem.id }) {
        nearbyItems + selectedNearbyItem
    } else {
        nearbyItems
    }
    nearbyForMap.forEach { item ->
        val style = nearbyMarkerStyle(item.category, item.id == selectedNearbyItem?.id)
        markers.add(
            MarkerData(
                item.location.latitude,
                item.location.longitude,
                item.name,
                "${"%.1f".format(item.distanceKm)} km | Risk ${item.riskLabel}",
                style.color,
                style.label,
                style.color,
                id = item.id
            )
        )
    }

    controller.batchUpdate(MapUpdateData(
        clear = true,
        currentLocation = currentLocation?.let { it.latitude to it.longitude },
        markers = markers,
        fitToLayers = selectedNearbyItem == null && markers.isNotEmpty()
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

private const val NEARBY_SAFETY_RADIUS_KM = 5.0
private const val MAX_NEARBY_ITEMS = 24
