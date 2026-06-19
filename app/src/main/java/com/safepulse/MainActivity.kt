package com.safepulse

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.MenuBook
import androidx.compose.material.icons.filled.Route
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Timeline
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberDrawerState
import androidx.compose.material3.DrawerValue
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.lifecycleScope
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.safepulse.data.prefs.UserPreferences
import com.safepulse.ui.journey.JourneyTimelineScreen
import com.safepulse.ui.journey.CompanionJourneyScreen
import com.safepulse.ui.onboarding.OnboardingOverlayScreen
import com.safepulse.ui.screens.*
import com.safepulse.ui.theme.SafePulseTheme
import com.safepulse.ui.viewmodel.HomeViewModel
import com.safepulse.utils.PermissionHelper
import com.safepulse.worker.DataRefreshWorker
import com.safepulse.worker.SafetyCheckWorker
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private val userPreferences by lazy { UserPreferences(this) }

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        // Handle permission results
        val allGranted = permissions.all { it.value }
        if (!allGranted) {
            // Some permissions denied - app will work with limited functionality
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Request permissions
        requestPermissions()

        // Schedule background workers
        SafetyCheckWorker.schedule(this)
        DataRefreshWorker.schedule(this)

        setContent {
            var darkMode by remember { mutableStateOf(false) }
            
            LaunchedEffect(Unit) {
                userPreferences.darkModeEnabledFlow.collect {
                    darkMode = it
                }
            }
            
            SafePulseTheme(darkTheme = darkMode) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    var onboardingComplete by remember { mutableStateOf<Boolean?>(null) }
                    var tutorialComplete by remember { mutableStateOf<Boolean?>(null) }

                    LaunchedEffect(Unit) {
                        onboardingComplete = userPreferences.onboardingCompleteFlow.first()
                        tutorialComplete = userPreferences.onboardingTutorialCompleteFlow.first()
                    }

                    when {
                        onboardingComplete == null || tutorialComplete == null -> {
                            // Loading state - could show splash
                        }
                        onboardingComplete == false -> {
                            // Show permission onboarding first
                            OnboardingFlow(
                                onComplete = {
                                    onboardingComplete = true
                                }
                            )
                        }
                        tutorialComplete == false -> {
                            // Show voice-guided tutorial after permissions are done
                            LaunchedEffect(Unit) {
                                userPreferences.setOnboardingTutorialComplete(true)
                            }

                            MainNavigationWithTutorial(
                                onTutorialComplete = {
                                    lifecycleScope.launch {
                                        userPreferences.setOnboardingTutorialComplete(true)
                                    }
                                    tutorialComplete = true
                                }
                            )
                        }
                        else -> {
                            // Both complete, show normal navigation
                            MainNavigation()
                        }
                    }
                }
            }
        }
    }

    private fun requestPermissions() {
        val missingPermissions = PermissionHelper.getMissingPermissions(this)
        if (missingPermissions.isNotEmpty()) {
            permissionLauncher.launch(missingPermissions.toTypedArray())
        }
    }
}

@Composable
fun OnboardingFlow(onComplete: () -> Unit) {
    OnboardingScreen(onComplete = onComplete)
}

@Composable
fun MainNavigation() {
    val navController = rememberNavController()
    val homeViewModel: HomeViewModel = viewModel()
    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route ?: "home"

    fun navigateTo(route: String) {
        scope.launch { drawerState.close() }
        if (currentRoute != route) {
            navController.navigate(route) {
                launchSingleTop = true
                restoreState = true
                popUpTo("home") {
                    saveState = true
                }
            }
        }
    }

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            AppDrawer(
                currentRoute = currentRoute,
                onDestinationSelected = ::navigateTo
            )
        }
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            NavHost(
                navController = navController,
                startDestination = "home"
            ) {
                composable("home") {
                    HomeScreen(
                        onNavigateToLogs = { navigateTo("logs") },
                        onNavigateToSettings = { navigateTo("settings") },
                        onNavigateToSafeRoutes = { navigateTo("safe_routes") },
                        onNavigateToRiskMap = { navigateTo("risk_map") },
                        onNavigateToAdvancedSafety = { navigateTo("advanced_safety") },
                        onNavigateToJourneyTimeline = { navigateTo("journey_timeline") },
                        onNavigateToCompanionJourney = { navigateTo("companion_journey") },
                        onOpenMenu = { scope.launch { drawerState.open() } },
                        viewModel = homeViewModel
                    )
                }

                composable("logs") {
                    EventLogsScreen(
                        onBack = { navController.popBackStack() }
                    )
                }

                composable("settings") {
                    SettingsScreen(
                        onBack = { navController.popBackStack() },
                        onNavigateToRiskMap = { navigateTo("risk_map") },
                        onNavigateToUserManual = { navigateTo("user_manual") },
                        onNavigateToPermissionHealth = { navigateTo("permission_health") }
                    )
                }

                composable("permission_health") {
                    PermissionHealthScreen(
                        onBack = { navController.popBackStack() }
                    )
                }

                composable("user_manual") {
                    UserManualScreen(
                        onBack = { navController.popBackStack() }
                    )
                }

                composable("safe_routes") {
                    SafeRoutesScreenWrapper(
                        onBack = { navController.popBackStack() }
                    )
                }

                composable("risk_map") {
                    RiskMapScreenWrapper(
                        onBack = { navController.popBackStack() }
                    )
                }

                composable("advanced_safety") {
                    AdvancedSafetyScreen(
                        onBack = { navController.popBackStack() },
                        viewModel = homeViewModel
                    )
                }

                composable("journey_timeline") {
                    JourneyTimelineScreen(
                        onBack = { navController.popBackStack() }
                    )
                }

                composable("companion_journey") {
                    CompanionJourneyScreen(
                        onBack = { navController.popBackStack() }
                    )
                }
            }
        }
    }
}

@Composable
private fun AppDrawer(
    currentRoute: String,
    onDestinationSelected: (String) -> Unit
) {
    val destinations = remember {
        listOf(
            DrawerDestination("home", "Home", Icons.Default.Home),
            DrawerDestination("safe_routes", "Safe Routes", Icons.Default.Route),
            DrawerDestination("journey_timeline", "Journey Timeline", Icons.Default.Timeline),
            DrawerDestination("companion_journey", "Companion Journey", Icons.Default.Shield),
            DrawerDestination("risk_map", "Risk Map", Icons.Default.Map),
            DrawerDestination("advanced_safety", "Advanced Safety", Icons.Default.Shield),
            DrawerDestination("logs", "Event Logs", Icons.Default.History),
            DrawerDestination("settings", "Settings", Icons.Default.Settings),
            DrawerDestination("permission_health", "Permission Health", Icons.Default.CheckCircle),
            DrawerDestination("user_manual", "User Manual", Icons.Default.MenuBook)
        )
    }

    ModalDrawerSheet(
        modifier = Modifier
            .width(304.dp)
            .widthIn(max = 340.dp)
    ) {
        Column(modifier = Modifier.padding(20.dp)) {
            Icon(
                imageVector = Icons.Default.Shield,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(34.dp)
            )
            Spacer(modifier = Modifier.size(10.dp))
            Text(
                text = "SafePulse",
                style = MaterialTheme.typography.titleLarge
            )
            Text(
                text = "Commute and safety tools",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        HorizontalDivider()

        destinations.forEach { destination ->
            NavigationDrawerItem(
                selected = currentRoute == destination.route,
                onClick = { onDestinationSelected(destination.route) },
                icon = {
                    Icon(
                        imageVector = destination.icon,
                        contentDescription = null
                    )
                },
                label = { Text(destination.title) },
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 2.dp)
            )
        }
    }
}

private data class DrawerDestination(
    val route: String,
    val title: String,
    val icon: ImageVector
)

@Composable
fun MainNavigationWithTutorial(onTutorialComplete: () -> Unit) {
    var showTutorial by remember { mutableStateOf(true) }
    
    Box(modifier = Modifier.fillMaxSize()) {
        // Main navigation as background
        MainNavigation()
        
        // Tutorial overlay on top
        if (showTutorial) {
            OnboardingOverlayScreen(
                onComplete = {
                    showTutorial = false
                    onTutorialComplete()
                }
            )
        }
    }
}
