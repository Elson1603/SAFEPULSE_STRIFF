package com.safepulse.service

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.os.Looper
import androidx.core.app.ActivityCompat
import com.google.android.gms.location.*
import com.safepulse.domain.model.LocationData
import com.safepulse.domain.model.SafetyMode
import com.safepulse.utils.SafetyConstants
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.tasks.await

/**
 * Manages location tracking using FusedLocationProviderClient.
 * Adjusts tracking parameters based on safety mode.
 */
class LocationTracker(private val context: Context) {
    
    private val fusedLocationClient: FusedLocationProviderClient =
        LocationServices.getFusedLocationProviderClient(context)
    
    private var locationCallback: LocationCallback? = null
    
    private val _currentLocation = MutableStateFlow<LocationData?>(null)
    val currentLocation: StateFlow<LocationData?> = _currentLocation
    
    private val _isTracking = MutableStateFlow(false)
    val isTracking: StateFlow<Boolean> = _isTracking
    
    // Location history for emergency path logging
    private val locationHistory = mutableListOf<LocationData>()
    private val maxHistorySize = 100
    
    // Current mode
    private var currentMode = SafetyMode.NORMAL
    
    /**
     * Start location tracking
     */
    @SuppressLint("MissingPermission")
    fun startTracking(mode: SafetyMode = SafetyMode.NORMAL) {
        if (!hasLocationPermission()) {
            return
        }
        
        currentMode = mode
        
        val locationRequest = createLocationRequest(mode)
        
        locationCallback = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                result.lastLocation?.let { location ->
                    val locationData = location.toLocationData()
                    _currentLocation.value = locationData
                    addToHistory(locationData)
                }
            }
        }
        
        fusedLocationClient.requestLocationUpdates(
            locationRequest,
            locationCallback!!,
            Looper.getMainLooper()
        )
        
        _isTracking.value = true
    }
    
    /**
     * Stop location tracking
     */
    fun stopTracking() {
        locationCallback?.let {
            fusedLocationClient.removeLocationUpdates(it)
        }
        locationCallback = null
        _isTracking.value = false
    }
    
    /**
     * Update tracking mode (changes location update frequency)
     */
    @SuppressLint("MissingPermission")
    fun updateMode(mode: SafetyMode) {
        if (mode != currentMode && _isTracking.value) {
            stopTracking()
            startTracking(mode)
        }
    }
    
    /**
     * Get last known location immediately
     */
    @SuppressLint("MissingPermission")
    suspend fun getLastLocation(): LocationData? {
        if (!hasLocationPermission()) return null
        
        return try {
            val location = fusedLocationClient.lastLocation.await()
            location?.toLocationData()
        } catch (e: Exception) {
            null
        }
    }
    
    /**
     * Get location updates as Flow
     */
    @SuppressLint("MissingPermission")
    fun locationFlow(mode: SafetyMode = SafetyMode.NORMAL): Flow<LocationData> = callbackFlow {
        if (!hasLocationPermission()) {
            close()
            return@callbackFlow
        }
        
        val locationRequest = createLocationRequest(mode)
        
        val callback = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                result.lastLocation?.let { location ->
                    trySend(location.toLocationData())
                }
            }
        }
        
        fusedLocationClient.requestLocationUpdates(
            locationRequest,
            callback,
            Looper.getMainLooper()
        )
        
        awaitClose {
            fusedLocationClient.removeLocationUpdates(callback)
        }
    }
    
    /**
     * Get location history for emergency logging
     */
    fun getLocationHistory(): List<LocationData> = locationHistory.toList()
    
    /**
     * Clear location history
     */
    fun clearHistory() {
        locationHistory.clear()
    }
    
    /**
     * Create location request based on mode
     */
    private fun createLocationRequest(mode: SafetyMode): LocationRequest {
        val interval = when (mode) {
            SafetyMode.HEIGHTENED -> SafetyConstants.LOCATION_INTERVAL_HEIGHTENED_MS
            SafetyMode.NORMAL -> SafetyConstants.LOCATION_INTERVAL_NORMAL_MS
        }
        
        val priority = when (mode) {
            SafetyMode.HEIGHTENED -> Priority.PRIORITY_HIGH_ACCURACY
            SafetyMode.NORMAL -> Priority.PRIORITY_BALANCED_POWER_ACCURACY
        }
        
        return LocationRequest.Builder(priority, interval)
            .setMinUpdateIntervalMillis(SafetyConstants.LOCATION_FASTEST_INTERVAL_MS)
            .setWaitForAccurateLocation(false)
            .build()
    }
    
    /**
     * Add location to history
     */
    private fun addToHistory(location: LocationData) {
        locationHistory.add(location)
        if (locationHistory.size > maxHistorySize) {
            locationHistory.removeAt(0)
        }
    }
    
    /**
     * Check if location permission is granted
     */
    private fun hasLocationPermission(): Boolean {
        return ActivityCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
    }
    
    /**
     * Extension to convert Location to LocationData
     */
    private fun Location.toLocationData(): LocationData {
        return LocationData(
            latitude = latitude,
            longitude = longitude,
            accuracy = accuracy,
            speed = speed,
            timestamp = time
        )
    }
}
