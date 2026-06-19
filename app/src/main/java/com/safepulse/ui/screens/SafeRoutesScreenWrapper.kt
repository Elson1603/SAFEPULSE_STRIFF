package com.safepulse.ui.screens

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.viewmodel.compose.viewModel
import com.safepulse.SafePulseApplication
import com.safepulse.data.prefs.UserPreferences
import com.safepulse.data.repository.RiskZoneRepository
import com.safepulse.data.repository.SafeRoutesRepository
import com.safepulse.domain.saferoutes.RouteRiskAnalyzer
import com.safepulse.domain.saferoutes.VehicleRecommender
import com.safepulse.service.SafetyForegroundService
import com.safepulse.ui.saferoutes.SafeRoutesScreenWithMap
import com.safepulse.ui.saferoutes.SafeRoutesViewModel
import com.safepulse.ui.saferoutes.SafeRoutesViewModelFactory
import kotlinx.coroutines.launch

/**
 * Wrapper to provide dependencies to SafeRoutesScreen
 */
@Composable
fun SafeRoutesScreenWrapper(
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val application = context.applicationContext as SafePulseApplication
    val scope = rememberCoroutineScope()
    val userPreferences = remember { UserPreferences(context) }
    
    // Create dependencies
    val hotspotRepository = application.database.hotspotDao().let { dao ->
        com.safepulse.data.repository.HotspotRepository(dao)
    }
    
    val riskAnalyzer = RouteRiskAnalyzer(hotspotRepository)
    val vehicleRecommender = VehicleRecommender()
    val safeRoutesRepository = SafeRoutesRepository(context, riskAnalyzer)
    val riskZoneRepository = remember { RiskZoneRepository(context) }
    
    // Create ViewModel with factory
    val viewModel: SafeRoutesViewModel = viewModel(
        factory = SafeRoutesViewModelFactory(safeRoutesRepository, vehicleRecommender, riskZoneRepository)
    )
    
    // Load nearby safety places (police stations + hospitals) for the map
    // Pre-filter to 30km radius from default location; SafeRoutesScreen also filters at draw time
    val safetyPlaces = remember {
        riskZoneRepository.getSafetyPlacesNear(
            com.google.android.gms.maps.model.LatLng(28.6139, 77.2090), 30.0
        )
    }
    
    SafeRoutesScreenWithMap(
        viewModel = viewModel,
        onNavigateBack = onBack,
        safetyPlaces = safetyPlaces,
        onStartCommuteNavigation = { destination, route ->
            SafetyForegroundService.start(application)
            scope.launch {
                userPreferences.setServiceEnabled(true)
                val destinationLabel = route?.summary
                    ?.takeIf { it.isNotBlank() }
                    ?.let { "Safe route via $it" }
                    ?: "Safe route to ${destination.latitude}, ${destination.longitude}"
                application.journeySessionManager.startJourney(destinationLabel)
            }
        }
    )
}
