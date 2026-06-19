package com.safepulse.ui.screens

import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.viewmodel.compose.viewModel
import com.safepulse.data.repository.RiskZoneRepository
import com.safepulse.ui.riskmap.RiskMapScreen
import com.safepulse.ui.riskmap.RiskMapViewModel
import com.safepulse.ui.riskmap.RiskMapViewModelFactory

/**
 * Wrapper to provide dependencies to RiskMapScreen
 */
@Composable
fun RiskMapScreenWrapper(
    onBack: () -> Unit
) {
    val context = LocalContext.current

    // Create repository (loads JSON from assets)
    val riskZoneRepository = RiskZoneRepository(context)

    // Create ViewModel with factory
    val viewModel: RiskMapViewModel = viewModel(
        factory = RiskMapViewModelFactory(riskZoneRepository)
    )

    RiskMapScreen(
        viewModel = viewModel,
        onNavigateBack = onBack
    )
}
