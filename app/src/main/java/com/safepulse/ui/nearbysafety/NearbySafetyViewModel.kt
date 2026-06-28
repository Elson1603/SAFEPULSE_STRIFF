package com.safepulse.ui.nearbysafety

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.google.android.gms.maps.model.LatLng
import com.safepulse.data.repository.RiskZoneRepository
import com.safepulse.domain.riskmap.SafeRouteOption
import com.safepulse.domain.riskmap.SafetyPlace
import com.safepulse.domain.riskmap.SafetyPlaceType
import com.safepulse.domain.saferoutes.*
import com.safepulse.ui.map.CrimeZoneData
import com.safepulse.ui.map.HospitalData
import com.safepulse.ui.map.PoliceStationData
import com.safepulse.ui.map.SafeZoneData
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

enum class SafetyCategory { ALL, POLICE, HOSPITAL, SAFE_ZONE }

data class NearbySafetyItem(
    val id: String,
    val name: String,
    val category: SafetyCategory,
    val location: LatLng,
    val distanceKm: Double,
    val riskScore: Float,
    val riskLabel: String,
    val subtitle: String = "",
    val phoneNumber: String = ""
)

data class SelectedDestinationDetail(
    val item: NearbySafetyItem,
    val routeOptions: List<SafeRouteOption>,
    val vehicleRecommendation: VehicleRecommendation
)

sealed class NearbySafetyUiState {
    object Loading : NearbySafetyUiState()
    data class Success(
        val items: List<NearbySafetyItem>,
        val selectedCategory: SafetyCategory = SafetyCategory.ALL,
        val selectedDetail: SelectedDestinationDetail? = null
    ) : NearbySafetyUiState()
    data class Error(val message: String) : NearbySafetyUiState()
}

class NearbySafetyViewModel(
    private val riskZoneRepository: RiskZoneRepository,
    private val vehicleRecommender: VehicleRecommender
) : ViewModel() {

    private val _uiState = MutableStateFlow<NearbySafetyUiState>(NearbySafetyUiState.Loading)
    val uiState: StateFlow<NearbySafetyUiState> = _uiState.asStateFlow()

    private val _currentLocation = MutableStateFlow<LatLng?>(null)
    val currentLocation: StateFlow<LatLng?> = _currentLocation.asStateFlow()

    private val _policeStations = MutableStateFlow<List<PoliceStationData>>(emptyList())
    val policeStations: StateFlow<List<PoliceStationData>> = _policeStations.asStateFlow()

    private val _hospitals = MutableStateFlow<List<HospitalData>>(emptyList())
    val hospitals: StateFlow<List<HospitalData>> = _hospitals.asStateFlow()

    private val _safeZones = MutableStateFlow<List<SafeZoneData>>(emptyList())
    val safeZones: StateFlow<List<SafeZoneData>> = _safeZones.asStateFlow()

    private val _crimeZonesForMap = MutableStateFlow<List<CrimeZoneData>>(emptyList())
    val crimeZonesForMap: StateFlow<List<CrimeZoneData>> = _crimeZonesForMap.asStateFlow()

    private var nearbyLayerDataLoaded = false
    private var nearbyLoadJob: Job? = null

    fun updateCurrentLocation(location: LatLng) {
        _currentLocation.value = location
        loadNearbyPlaces(location)
    }

    private fun loadNearbyPlaces(location: LatLng) {
        nearbyLoadJob?.cancel()
        nearbyLoadJob = viewModelScope.launch(Dispatchers.IO) {
            try {
                coroutineScope {
                    val nearbyPolice = riskZoneRepository.getSafetyPlacesNear(
                        location = location,
                        maxDistanceKm = 30.0,
                        type = SafetyPlaceType.POLICE,
                        maxResults = MAX_NEARBY_POLICE_ITEMS
                    )
                    val policeItems = nearbyPolice.toNearbyItems(
                        origin = location,
                        category = SafetyCategory.POLICE,
                        fallbackSubtitle = "Police station"
                    )
                    _policeStations.value = nearbyPolice.toPoliceStationData()
                        .take(MAX_LAYER_MARKERS)
                    if (policeItems.isNotEmpty()) {
                        publishNearbyItems(policeItems)
                    }

                    val nearbyHospitals = riskZoneRepository.getSafetyPlacesNear(
                        location = location,
                        maxDistanceKm = 20.0,
                        type = SafetyPlaceType.HOSPITAL,
                        maxResults = MAX_NEARBY_HOSPITAL_ITEMS
                    )
                    val hospitalItems = nearbyHospitals.toNearbyItems(
                        origin = location,
                        category = SafetyCategory.HOSPITAL,
                        fallbackSubtitle = "Hospital"
                    )
                    _hospitals.value = nearbyHospitals.toHospitalData()
                        .take(MAX_LAYER_MARKERS)
                    val baseItems = policeItems + hospitalItems
                    publishNearbyItems(baseItems)

                    val safeZonesDeferred = async {
                        riskZoneRepository.getSafeZonesNearFast(location, 50.0)
                            .take(MAX_SAFE_ZONE_ITEMS)
                    }
                    val livePoliceDeferred = if (nearbyPolice.size < MIN_FAST_POLICE_RESULTS) {
                        async {
                            riskZoneRepository.getPolicePlacesNearIncludingLive(
                                location = location,
                                maxDistanceKm = 30.0,
                                maxResults = MAX_NEARBY_POLICE_ITEMS
                            )
                        }
                    } else {
                        null
                    }

                    val safeZones = safeZonesDeferred.await()
                    val safeZoneItems = safeZones.map { zone ->
                        val zoneLocation = LatLng(zone.lat, zone.lng)
                        NearbySafetyItem(
                            id = "safezone_${zone.name}_${zone.lat}_${zone.lng}",
                            name = zone.name,
                            category = SafetyCategory.SAFE_ZONE,
                            location = zoneLocation,
                            distanceKm = RiskZoneRepository.distanceKm(location, zoneLocation),
                            riskScore = 0f,
                            riskLabel = "LOW",
                            subtitle = "${zone.state} - Low crime area"
                        )
                    }
                    if (!nearbyLayerDataLoaded) {
                        _safeZones.value = safeZones
                            .take(MAX_SAFE_ZONE_MARKERS)
                        _crimeZonesForMap.value = riskZoneRepository.getCrimeZonesForMapNearFast(location)
                        nearbyLayerDataLoaded = true
                    }

                    var latestItems = baseItems + safeZoneItems
                    publishNearbyItems(latestItems)

                    val livePolice = livePoliceDeferred?.await().orEmpty()
                    if (livePolice.isNotEmpty() && livePolice.size > nearbyPolice.size) {
                        val livePoliceItems = livePolice.toNearbyItems(
                            origin = location,
                            category = SafetyCategory.POLICE,
                            fallbackSubtitle = "Police station"
                        )
                        _policeStations.value = livePolice.toPoliceStationData()
                            .take(MAX_LAYER_MARKERS)
                        latestItems = latestItems.filterNot { it.category == SafetyCategory.POLICE } +
                                livePoliceItems
                        publishNearbyItems(latestItems)
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _uiState.value = NearbySafetyUiState.Error(
                    e.message ?: "Failed to load nearby safety places"
                )
            }
        }
    }

    fun setCategory(category: SafetyCategory) {
        val current = _uiState.value
        if (current is NearbySafetyUiState.Success) {
            _uiState.value = current.copy(selectedCategory = category, selectedDetail = null)
        }
    }

    fun selectItem(item: NearbySafetyItem) {
        val origin = _currentLocation.value ?: return
        viewModelScope.launch(Dispatchers.IO) {
            val routes = riskZoneRepository.suggestSafeWaypoints(origin, item.location)
            val safest = routes.minByOrNull { it.totalRiskScore }
            val recommendation = if (safest != null) {
                val syntheticRoute = SafeRoute(
                    id = "route_${item.id}",
                    polyline = "",
                    distance = (safest.distanceKm * 1000).toLong(),
                    duration = (safest.distanceKm * 3 * 60).toLong(),
                    riskScore = safest.totalRiskScore,
                    riskLevel = when {
                        safest.totalRiskScore >= 0.7f -> RiskLevel.HIGH
                        safest.totalRiskScore >= 0.4f -> RiskLevel.MEDIUM
                        else -> RiskLevel.LOW
                    },
                    summary = "Route to ${item.name}",
                    isRecommended = safest.isSafest
                )
                vehicleRecommender.recommendVehicle(syntheticRoute)
            } else {
                VehicleRecommendation(
                    vehicle = RecommendedVehicle.TRACKED_CAB,
                    reason = "Use tracked transport for safety"
                )
            }

            val detail = SelectedDestinationDetail(item, routes, recommendation)
            val current = _uiState.value
            if (current is NearbySafetyUiState.Success) {
                _uiState.value = current.copy(selectedDetail = detail)
            }
        }
    }

    fun clearSelection() {
        val current = _uiState.value
        if (current is NearbySafetyUiState.Success) {
            _uiState.value = current.copy(selectedDetail = null)
        }
    }

    private fun List<SafetyPlace>.toNearbyItems(
        origin: LatLng,
        category: SafetyCategory,
        fallbackSubtitle: String
    ): List<NearbySafetyItem> {
        return map { place ->
            NearbySafetyItem(
                id = "place_${place.id}",
                name = place.name,
                category = category,
                location = place.location,
                distanceKm = RiskZoneRepository.distanceKm(origin, place.location),
                riskScore = 0f,
                riskLabel = "LOW",
                subtitle = place.address.ifEmpty { place.city.ifEmpty { fallbackSubtitle } },
                phoneNumber = place.phoneNumber
            )
        }
    }

    private fun List<SafetyPlace>.toPoliceStationData(): List<PoliceStationData> {
        return map {
            PoliceStationData(
                lat = it.location.latitude,
                lng = it.location.longitude,
                name = it.name,
                id = "place_${it.id}"
            )
        }
    }

    private fun List<SafetyPlace>.toHospitalData(): List<HospitalData> {
        return map {
            HospitalData(
                lat = it.location.latitude,
                lng = it.location.longitude,
                name = it.name,
                id = "place_${it.id}"
            )
        }
    }

    private fun publishNearbyItems(items: List<NearbySafetyItem>) {
        val current = _uiState.value as? NearbySafetyUiState.Success
        _uiState.value = NearbySafetyUiState.Success(
            items = items
                .distinctBy { it.id }
                .sortedBy { it.distanceKm },
            selectedCategory = current?.selectedCategory ?: SafetyCategory.ALL,
            selectedDetail = current?.selectedDetail
        )
    }
}

private const val MAX_LAYER_MARKERS = 80
private const val MAX_SAFE_ZONE_MARKERS = 24
private const val MAX_NEARBY_POLICE_ITEMS = 80
private const val MAX_NEARBY_HOSPITAL_ITEMS = 80
private const val MAX_SAFE_ZONE_ITEMS = 30
private const val MIN_FAST_POLICE_RESULTS = 5

class NearbySafetyViewModelFactory(
    private val riskZoneRepository: RiskZoneRepository,
    private val vehicleRecommender: VehicleRecommender
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(NearbySafetyViewModel::class.java)) {
            return NearbySafetyViewModel(riskZoneRepository, vehicleRecommender) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class")
    }
}
