package com.safepulse

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.material3.NavigationDrawerItemDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberDrawerState
import androidx.compose.material3.DrawerValue
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.lifecycleScope
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.safepulse.data.prefs.UserPreferences
import com.safepulse.service.BatteryDeadModeManager
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
    val context = LocalContext.current
    val navController = rememberNavController()
    val homeViewModel: HomeViewModel = viewModel()
    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    val batteryDeadModeManager = remember(context) { BatteryDeadModeManager.getInstance(context) }
    val batteryDeadState by batteryDeadModeManager.state.collectAsState()
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

            if (batteryDeadState.isEnabled) {
                BatteryDeadModeScreen(
                    onExitPinEntered = { batteryDeadModeManager.verifyExitPin(it) }
                )
            }
        }
    }
}

@Composable
private fun AppDrawer(
    currentRoute: String,
    onDestinationSelected: (String) -> Unit
) {
    val primaryDestinations = remember {
        listOf(
            DrawerDestination("home", "Home", Icons.Default.Home),
            DrawerDestination("safe_routes", "Safe Routes", Icons.Default.Route),
            DrawerDestination("journey_timeline", "Journey Timeline", Icons.Default.Timeline),
            DrawerDestination("companion_journey", "Companion Journey", Icons.Default.Shield),
            DrawerDestination("risk_map", "Risk Map", Icons.Default.Map),
            DrawerDestination("advanced_safety", "Advanced Safety", Icons.Default.Shield)
        )
    }
    val supportDestinations = remember {
        listOf(
            DrawerDestination("logs", "Event Logs", Icons.Default.History),
            DrawerDestination("settings", "Settings", Icons.Default.Settings),
            DrawerDestination("permission_health", "Permission Health", Icons.Default.CheckCircle),
            DrawerDestination("user_manual", "User Manual", Icons.Default.MenuBook)
        )
    }

    ModalDrawerSheet(
        modifier = Modifier
            .width(300.dp)
            .widthIn(max = 320.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp)
                    .clip(RoundedCornerShape(18.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.38f))
                    .padding(16.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(44.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.16f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.Shield,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(28.dp)
                        )
                    }
                    Spacer(modifier = Modifier.width(12.dp))
                    Column {
                        Text(
                            text = "SafePulse",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "Commute safety suite",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(MaterialTheme.colorScheme.background.copy(alpha = 0.45f))
                        .padding(horizontal = 12.dp, vertical = 9.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .clip(CircleShape)
                            .background(com.safepulse.ui.theme.SafeGreen)
                    )
                    Text(
                        text = "Ready to monitor",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
            }

            DrawerSectionTitle("Navigation")
            primaryDestinations.forEach { destination ->
                SafePulseDrawerItem(
                    destination = destination,
                    selected = currentRoute == destination.route,
                    onClick = { onDestinationSelected(destination.route) }
                )
            }

            HorizontalDivider(modifier = Modifier.padding(horizontal = 20.dp, vertical = 10.dp))

            DrawerSectionTitle("Support")
            supportDestinations.forEach { destination ->
                SafePulseDrawerItem(
                    destination = destination,
                    selected = currentRoute == destination.route,
                    onClick = { onDestinationSelected(destination.route) }
                )
            }

            Text(
                text = "SafePulse v1.0",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.72f),
                modifier = Modifier.padding(start = 24.dp, end = 24.dp, top = 12.dp, bottom = 24.dp)
            )
        }
    }
}

@Composable
private fun DrawerSectionTitle(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.labelSmall,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.72f),
        modifier = Modifier.padding(start = 24.dp, top = 6.dp, bottom = 4.dp)
    )
}

@Composable
private fun SafePulseDrawerItem(
    destination: DrawerDestination,
    selected: Boolean,
    onClick: () -> Unit
) {
    NavigationDrawerItem(
        selected = selected,
        onClick = onClick,
        icon = {
            Icon(
                imageVector = destination.icon,
                contentDescription = null,
                modifier = Modifier.size(22.dp)
            )
        },
        label = {
            Text(
                text = destination.title,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal
            )
        },
        shape = RoundedCornerShape(14.dp),
        colors = NavigationDrawerItemDefaults.colors(
            selectedContainerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.16f),
            selectedIconColor = MaterialTheme.colorScheme.primary,
            selectedTextColor = MaterialTheme.colorScheme.onSurface,
            unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
            unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant
        ),
        modifier = Modifier.padding(horizontal = 12.dp, vertical = 1.dp)
    )
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
