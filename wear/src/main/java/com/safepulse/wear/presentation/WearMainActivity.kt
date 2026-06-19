package com.safepulse.wear.presentation

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.safepulse.wear.ui.theme.SafePulseWearTheme

/**
 * Main entry point for the SafePulse Wear OS app.
 * Uses a round-aware Compose layout designed for smartwatch screens.
 */
class WearMainActivity : ComponentActivity() {

    companion object {
        private const val REQUEST_WEAR_PERMISSIONS = 100
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        requestWearPermissions()

        setContent {
            SafePulseWearTheme {
                WearNavigation()
            }
        }
    }

    private fun requestWearPermissions() {
        val permissions = mutableListOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION,
            Manifest.permission.BODY_SENSORS,
            Manifest.permission.ACTIVITY_RECOGNITION,
            Manifest.permission.RECORD_AUDIO
        )

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.POST_NOTIFICATIONS)
        }

        val missingPermissions = permissions.filter {
            checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED
        }

        if (missingPermissions.isNotEmpty()) {
            requestPermissions(
                missingPermissions.toTypedArray(),
                REQUEST_WEAR_PERMISSIONS
            )
        }
    }
}
