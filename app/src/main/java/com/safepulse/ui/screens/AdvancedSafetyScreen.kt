package com.safepulse.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.HealthAndSafety
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.safepulse.ui.theme.PrimaryRed
import com.safepulse.ui.viewmodel.HomeViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AdvancedSafetyScreen(
    onBack: () -> Unit,
    viewModel: HomeViewModel = viewModel()
) {
    val state by viewModel.state.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Default.HealthAndSafety,
                            contentDescription = null,
                            tint = PrimaryRed
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            "Advanced Safety",
                            fontWeight = FontWeight.Bold
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background
                )
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
        ) {
            Spacer(modifier = Modifier.height(12.dp))

            AdvancedSafetyCard(
                features = state.safetyFeatures,
                onOfflineChanged = { viewModel.setOfflineSafetyMode(it) },
                onScheduleFakeCall = { viewModel.scheduleFakeCall(it) },
                onStartCheckIn = { viewModel.startSafetyCheckIn(it) },
                onCancelCheckIn = { viewModel.cancelSafetyCheckIn() },
                onRunEmergencyDrill = { viewModel.runEmergencyDrill() },
                onShareTimeline = { viewModel.shareEmergencyTimeline() },
                onStartJourney = { destination, minutes ->
                    viewModel.startTrustedJourney(destination, minutes)
                },
                onCompleteJourney = { viewModel.completeTrustedJourney() },
                onCancelPin = { viewModel.handleCancelPin(it) },
                onSimulateHelp = { viewModel.simulateContactHelpComing() },
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(modifier = Modifier.height(24.dp))
        }
    }
}
