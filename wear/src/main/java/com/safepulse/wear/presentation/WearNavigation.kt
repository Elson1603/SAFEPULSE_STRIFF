package com.safepulse.wear.presentation

import androidx.compose.runtime.Composable
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.wear.compose.navigation.SwipeDismissableNavHost
import androidx.wear.compose.navigation.composable
import androidx.wear.compose.navigation.rememberSwipeDismissableNavController
import com.safepulse.wear.ui.screens.*

/**
 * Navigation graph for the Wear OS app.
 * Uses SwipeDismissable navigation for natural watch gestures.
 */
@Composable
fun WearNavigation() {
    val navController = rememberSwipeDismissableNavController()
    val viewModel: WearHomeViewModel = viewModel()

    SwipeDismissableNavHost(
        navController = navController,
        startDestination = "home"
    ) {
        composable("home") {
            WearHomeScreen(
                viewModel = viewModel,
                onNavigateToSos = { navController.navigate("sos") },
                onNavigateToQuickActions = { navController.navigate("quick_actions") },
                onNavigateToContacts = { navController.navigate("contacts") },
                onNavigateToSettings = { navController.navigate("settings") },
                onNavigateToMap = { navController.navigate("map") }
            )
        }

        composable("sos") {
            WearSOSScreen(
                viewModel = viewModel,
                onBack = { navController.popBackStack() }
            )
        }

        composable("quick_actions") {
            WearQuickActionsScreen(
                viewModel = viewModel,
                onBack = { navController.popBackStack() }
            )
        }

        composable("contacts") {
            WearContactsScreen(
                viewModel = viewModel,
                onBack = { navController.popBackStack() }
            )
        }

        composable("settings") {
            WearSettingsScreen(
                onBack = { navController.popBackStack() }
            )
        }

        composable("map") {
            WearMapScreen(
                viewModel = viewModel,
                onBack = { navController.popBackStack() }
            )
        }
    }
}
