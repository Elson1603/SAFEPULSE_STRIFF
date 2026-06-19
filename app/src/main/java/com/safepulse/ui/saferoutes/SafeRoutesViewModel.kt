package com.safepulse.ui.saferoutes

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.android.gms.maps.model.LatLng
import com.safepulse.data.repository.RiskZoneRepository
import com.safepulse.data.repository.SafeRoutesRepository
import com.safepulse.domain.saferoutes.*
import com.safepulse.ui.components.createMockRoutes
import com.safepulse.ui.map.CrimeZoneData
import com.safepulse.ui.map.HospitalData
import com.safepulse.ui.map.PoliceStationData
import com.safepulse.ui.map.SafeZoneData
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * ViewModel for Safe Routes feature
 */
class SafeRoutesViewModel(
    private val safeRoutesRepository: SafeRoutesRepository,
    private val vehicleRecommender: VehicleRecommender,
    private val riskZoneRepository: RiskZoneRepository? = null
) : ViewModel() {

    private val _uiState = MutableStateFlow<SafeRoutesUiState>(SafeRoutesUiState.Idle)
    val uiState: StateFlow<SafeRoutesUiState> = _uiState.asStateFlow()

    private val _currentLocation = MutableStateFlow<LatLng?>(null)
    val currentLocation: StateFlow<LatLng?> = _currentLocation.asStateFlow()

    private val _selectedRoute = MutableStateFlow<SafeRoute?>(null)
    val selectedRoute: StateFlow<SafeRoute?> = _selectedRoute.asStateFlow()

    private val _policeStations = MutableStateFlow<List<PoliceStationData>>(emptyList())
    val policeStations: StateFlow<List<PoliceStationData>> = _policeStations.asStateFlow()

    private val _hospitals = MutableStateFlow<List<HospitalData>>(emptyList())
    val hospitals: StateFlow<List<HospitalData>> = _hospitals.asStateFlow()

    private val _safeZones = MutableStateFlow<List<SafeZoneData>>(emptyList())
    val safeZones: StateFlow<List<SafeZoneData>> = _safeZones.asStateFlow()

    private val _crimeZonesForMap = MutableStateFlow<List<CrimeZoneData>>(emptyList())
    val crimeZonesForMap: StateFlow<List<CrimeZoneData>> = _crimeZonesForMap.asStateFlow()

    private val _destination = MutableStateFlow<LatLng?>(null)
    val destination: StateFlow<LatLng?> = _destination.asStateFlow()

    private var nearbyMapDataLoaded = false

    /**
     * Update current location and load bounded safety data for map.
     */
    fun updateCurrentLocation(location: LatLng) {
        _currentLocation.value = location
        riskZoneRepository?.let { repo ->
            viewModelScope.launch(Dispatchers.IO) {
                _crimeZonesForMap.value = repo.getCrimeZonesForMap()
                if (!nearbyMapDataLoaded) {
                    nearbyMapDataLoaded = true
                    _policeStations.value = repo.getPoliceStationsNear(location, 30.0)
                        .take(MAX_LAYER_MARKERS)
                    _hospitals.value = repo.getHospitalsNear(location, 20.0)
                        .take(MAX_LAYER_MARKERS)
                    _safeZones.value = repo.getSafeZonesNear(location, 50.0)
                        .take(MAX_SAFE_ZONE_MARKERS)
                }
            }
        }
    }

    /**
     * Search for safe routes to destination.
     * Falls back to locally generated mock routes if the API call fails.
     */
    fun searchSafeRoutes(destination: LatLng) {
        // Use current location or fall back to Delhi
        val origin = _currentLocation.value ?: LatLng(28.6139, 77.2090).also {
            _currentLocation.value = it
        }
        _destination.value = destination

        viewModelScope.launch {
            _uiState.value = SafeRoutesUiState.Loading

            try {
                val routes = safeRoutesRepository.getSafeRoutes(origin, destination)

                if (routes.isEmpty()) {
                    // Fallback to mock routes when API returns nothing
                    val fallbackRoutes = createMockRoutes(origin, destination)
                    val safestRoute = fallbackRoutes.first()
                    val recommendation = vehicleRecommender.recommendVehicle(safestRoute)
                    _uiState.value = SafeRoutesUiState.Success(
                        routes = fallbackRoutes,
                        vehicleRecommendation = recommendation
                    )
                } else {
                    val safestRoute = routes.first()
                    val recommendation = vehicleRecommender.recommendVehicle(safestRoute)

                    _uiState.value = SafeRoutesUiState.Success(
                        routes = routes,
                        vehicleRecommendation = recommendation
                    )
                }
            } catch (e: Exception) {
                // Fallback to mock routes when API call fails
                try {
                    val fallbackRoutes = createMockRoutes(origin, destination)
                    val safestRoute = fallbackRoutes.first()
                    val recommendation = vehicleRecommender.recommendVehicle(safestRoute)
                    _uiState.value = SafeRoutesUiState.Success(
                        routes = fallbackRoutes,
                        vehicleRecommendation = recommendation
                    )
                } catch (fallbackError: Exception) {
                    _uiState.value = SafeRoutesUiState.Error(
                        e.message ?: "Failed to find routes"
                    )
                }
            }
        }
    }

    /**
     * Select a specific route
     */
    fun selectRoute(route: SafeRoute) {
        _selectedRoute.value = route

        // Update vehicle recommendation for selected route
        val current = _uiState.value
        if (current is SafeRoutesUiState.Success) {
            val newRecommendation = vehicleRecommender.recommendVehicle(route)
            _uiState.value = current.copy(vehicleRecommendation = newRecommendation)
        }
    }

    /**
     * Clear search results
     */
    fun clearResults() {
        _uiState.value = SafeRoutesUiState.Idle
        _selectedRoute.value = null
        _destination.value = null
    }
}

/**
 * UI state for Safe Routes screen
 */
sealed class SafeRoutesUiState {
    object Idle : SafeRoutesUiState()
    object Loading : SafeRoutesUiState()
    data class Success(
        val routes: List<SafeRoute>,
        val vehicleRecommendation: VehicleRecommendation
    ) : SafeRoutesUiState()
    data class Error(val message: String) : SafeRoutesUiState()
}

private const val MAX_LAYER_MARKERS = 80
private const val MAX_SAFE_ZONE_MARKERS = 24
