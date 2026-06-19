package com.safepulse.wear.ui.screens

import android.Manifest
import android.annotation.SuppressLint
import android.content.pm.PackageManager
import android.webkit.JavascriptInterface
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.wear.compose.material.*
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import com.safepulse.wear.data.WearRiskLevel
import com.safepulse.wear.presentation.WearHomeViewModel
import com.safepulse.wear.ui.theme.*
import kotlinx.coroutines.delay
import java.util.Locale
import kotlin.math.cos
import kotlin.math.sin

/**
 * Watch-optimized live map screen.
 * A native live-location view is used by default because Wear WebView tile
 * loading can be unreliable on some watches.
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun WearMapScreen(
    viewModel: WearHomeViewModel,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val state by viewModel.state.collectAsState()
    var webView by remember { mutableStateOf<WebView?>(null) }
    var isMapReady by remember { mutableStateOf(false) }
    var showTileMap by remember { mutableStateOf(false) }
    var webMapFailed by remember { mutableStateOf(false) }
    var currentLocation by remember { mutableStateOf<Pair<Double, Double>?>(null) }
    var mapStatus by remember { mutableStateOf("Getting location...") }

    var hasLocationPermission by remember {
        mutableStateOf(hasAnyLocationPermission(context))
    }

    val locationPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        hasLocationPermission = permissions[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
            permissions[Manifest.permission.ACCESS_COARSE_LOCATION] == true ||
            hasAnyLocationPermission(context)
        if (hasLocationPermission) {
            getWearLocation(context) { lat, lng ->
                currentLocation = lat to lng
            }
        }
    }

    // Ask for watch GPS access once; phone GPS can still drive the map.
    LaunchedEffect(Unit) {
        if (!hasLocationPermission) {
            locationPermissionLauncher.launch(
                arrayOf(
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION
                )
            )
        }
    }

    val phoneLocation = state.safetyState.let { safetyState ->
        if (safetyState.latitude != 0.0 && safetyState.longitude != 0.0) {
            safetyState.latitude to safetyState.longitude
        } else {
            null
        }
    }
    val displayLocation = currentLocation ?: phoneLocation
    val locationSource = when {
        currentLocation != null -> "Watch GPS"
        phoneLocation != null -> "Phone GPS"
        else -> null
    }

    // Keep live location moving while the map is open.
    LaunchedEffect(hasLocationPermission) {
        while (true) {
            viewModel.refresh()
            if (hasLocationPermission) {
                getWearLocation(context) { lat, lng ->
                    currentLocation = lat to lng
                }
            }
            delay(8000)
        }
    }

    // Update map when state changes
    LaunchedEffect(
        displayLocation,
        locationSource,
        state.safetyState.riskLevel,
        state.isPhoneConnected,
        isMapReady,
        showTileMap
    ) {
        val location = displayLocation

        if (location == null) {
            mapStatus = if (state.isPhoneConnected) {
                "Waiting for GPS..."
            } else {
                "Connect phone or enable GPS"
            }
            if (showTileMap && isMapReady) webView?.post {
                webView?.evaluateJavascript(
                    "setStatus('${mapStatus}'); setCenter(20.5937, 78.9629, 4);",
                    null
                )
            }
            return@LaunchedEffect
        }

        val (lat, lng) = location
        val source = locationSource ?: "GPS"
        mapStatus = if (showTileMap && isMapReady) source else "$source live"
        if (showTileMap && isMapReady) webView?.post {
            val riskColor = when (state.safetyState.riskLevel) {
                WearRiskLevel.LOW -> "#4CAF50"
                WearRiskLevel.MEDIUM -> "#FF9800"
                WearRiskLevel.HIGH -> "#F44336"
            }
            webView?.evaluateJavascript(
                "clearAll(); setStatus('$source'); setCenter($lat, $lng, 15); setCurrentLocation($lat, $lng); addCircle($lat, $lng, 500, '$riskColor', '$riskColor', 0.18, 0.45);",
                null
            )
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        if (showTileMap && !webMapFailed) {
            // Optional Leaflet tile map. If it fails, the native live map below
            // remains the reliable watch experience.
            AndroidView(
                factory = { ctx ->
                    WebView(ctx).apply {
                        val mapWebView = this

                        settings.javaScriptEnabled = true
                        settings.domStorageEnabled = true
                        settings.allowFileAccess = true
                        settings.allowContentAccess = true
                        settings.allowFileAccessFromFileURLs = true
                        settings.allowUniversalAccessFromFileURLs = true
                        settings.cacheMode = WebSettings.LOAD_DEFAULT
                        settings.loadWithOverviewMode = true
                        settings.useWideViewPort = true
                        settings.mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW

                        addJavascriptInterface(object {
                            @JavascriptInterface
                            fun onMapReady() {
                                mapWebView.post {
                                    isMapReady = true
                                    mapStatus = locationSource ?: "Map ready"
                                }
                            }
                        }, "AndroidBridge")

                        webChromeClient = WebChromeClient()
                        webViewClient = object : WebViewClient() {
                            override fun onPageFinished(view: WebView?, url: String?) {
                                super.onPageFinished(view, url)
                                mapWebView.postDelayed({
                                    if (!isMapReady) {
                                        isMapReady = true
                                        mapStatus = locationSource ?: "Map loaded"
                                    }
                                }, 600)
                            }

                            override fun onReceivedError(
                                view: WebView?,
                                request: WebResourceRequest?,
                                error: WebResourceError?
                            ) {
                                super.onReceivedError(view, request, error)
                                if (request?.isForMainFrame == true) {
                                    webMapFailed = true
                                    showTileMap = false
                                    isMapReady = false
                                    mapStatus = locationSource?.let { "$it live" } ?: "Live view"
                                }
                            }
                        }
                        loadUrl("file:///android_asset/leaflet_map_wear.html")
                        webView = this
                    }
                },
                modifier = Modifier
                    .fillMaxSize()
                    .clip(CircleShape)
            )
        } else {
            NativeWearMapView(
                location = displayLocation,
                locationSource = locationSource,
                riskLevel = state.safetyState.riskLevel,
                phoneConnected = state.isPhoneConnected,
                modifier = Modifier.fillMaxSize()
            )
        }

        // Risk level badge (top)
        val (riskColor, riskText) = when (state.safetyState.riskLevel) {
            WearRiskLevel.LOW -> SafeGreen to "Safe"
            WearRiskLevel.MEDIUM -> RiskMedium to "Caution"
            WearRiskLevel.HIGH -> RiskHigh to "Danger"
        }

        Box(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = 28.dp)
                .background(
                    color = riskColor.copy(alpha = 0.85f),
                    shape = MaterialTheme.shapes.small
                )
                .padding(horizontal = 10.dp, vertical = 3.dp)
        ) {
            Text(
                text = riskText,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                color = Color.White
            )
        }

        // Location source/status badge
        Box(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 52.dp)
                .background(
                    color = SurfaceDark.copy(alpha = 0.78f),
                    shape = MaterialTheme.shapes.small
                )
                .padding(horizontal = 8.dp, vertical = 3.dp)
        ) {
            Text(
                text = mapStatus,
                fontSize = 9.sp,
                color = Color.White.copy(alpha = 0.85f)
            )
        }

        // Toggle between the reliable native live view and the optional tile map.
        Button(
            onClick = {
                showTileMap = !showTileMap
                webMapFailed = false
                isMapReady = false
                mapStatus = if (showTileMap) {
                    "Loading tiles..."
                } else {
                    locationSource?.let { "$it live" } ?: "Live view"
                }
            },
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .padding(end = 8.dp)
                .size(38.dp),
            colors = ButtonDefaults.buttonColors(
                backgroundColor = SurfaceDark.copy(alpha = 0.82f)
            )
        ) {
            Text(
                text = if (showTileMap) "Live" else "Tiles",
                fontSize = 8.sp,
                color = Color.White,
                textAlign = TextAlign.Center
            )
        }

        // Back button (bottom)
        Button(
            onClick = onBack,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 12.dp)
                .size(32.dp),
            colors = ButtonDefaults.buttonColors(
                backgroundColor = SurfaceDark.copy(alpha = 0.8f)
            )
        ) {
            Text("✕", fontSize = 14.sp, color = Color.White)
        }
    }
}

@Composable
private fun NativeWearMapView(
    location: Pair<Double, Double>?,
    locationSource: String?,
    riskLevel: WearRiskLevel,
    phoneConnected: Boolean,
    modifier: Modifier = Modifier
) {
    val riskColor = when (riskLevel) {
        WearRiskLevel.LOW -> SafeGreen
        WearRiskLevel.MEDIUM -> RiskMedium
        WearRiskLevel.HIGH -> RiskHigh
    }

    Box(
        modifier = modifier
            .clip(CircleShape)
            .background(Color(0xFF101722)),
        contentAlignment = Alignment.Center
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val center = Offset(size.width / 2f, size.height / 2f)
            val radius = size.minDimension / 2f
            val gridColor = Color.White.copy(alpha = 0.09f)

            drawCircle(
                color = Color(0xFF111D2E),
                radius = radius,
                center = center
            )

            for (index in 1..4) {
                drawCircle(
                    color = gridColor,
                    radius = radius * index / 5f,
                    center = center,
                    style = Stroke(width = 1.dp.toPx())
                )
            }

            for (angleDegrees in 0 until 360 step 45) {
                val angle = Math.toRadians(angleDegrees.toDouble())
                val end = Offset(
                    x = center.x + cos(angle).toFloat() * radius,
                    y = center.y + sin(angle).toFloat() * radius
                )
                drawLine(
                    color = gridColor,
                    start = center,
                    end = end,
                    strokeWidth = 1.dp.toPx()
                )
            }

            drawCircle(
                color = riskColor.copy(alpha = if (location == null) 0.08f else 0.16f),
                radius = radius * 0.34f,
                center = center
            )
            drawCircle(
                color = riskColor.copy(alpha = if (location == null) 0.18f else 0.38f),
                radius = radius * 0.34f,
                center = center,
                style = Stroke(width = 2.dp.toPx())
            )

            val markerColor = if (location == null) {
                Color.White.copy(alpha = 0.38f)
            } else {
                Color(0xFF4285F4)
            }
            drawCircle(
                color = Color.White,
                radius = 8.dp.toPx(),
                center = center
            )
            drawCircle(
                color = markerColor,
                radius = 5.5.dp.toPx(),
                center = center
            )
        }

        Column(
            modifier = Modifier
                .align(Alignment.Center)
                .offset(y = 42.dp)
                .padding(horizontal = 28.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = locationSource ?: if (phoneConnected) "Waiting for GPS" else "Phone offline",
                fontSize = 9.sp,
                fontWeight = FontWeight.Bold,
                color = Color.White,
                textAlign = TextAlign.Center,
                maxLines = 1
            )
            if (location != null) {
                Text(
                    text = "${formatCoordinate(location.first)}, ${formatCoordinate(location.second)}",
                    fontSize = 7.sp,
                    color = Color.White.copy(alpha = 0.72f),
                    textAlign = TextAlign.Center,
                    maxLines = 1
                )
            }
        }
    }
}

private fun hasAnyLocationPermission(context: android.content.Context): Boolean {
    return ContextCompat.checkSelfPermission(
        context,
        Manifest.permission.ACCESS_FINE_LOCATION
    ) == PackageManager.PERMISSION_GRANTED ||
        ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_COARSE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
}

private fun hasFineLocationPermission(context: android.content.Context): Boolean {
    return ContextCompat.checkSelfPermission(
        context,
        Manifest.permission.ACCESS_FINE_LOCATION
    ) == PackageManager.PERMISSION_GRANTED
}

private fun formatCoordinate(value: Double): String {
    return String.format(Locale.US, "%.4f", value)
}

@SuppressLint("MissingPermission")
private fun getWearLocation(
    context: android.content.Context,
    onLocation: (Double, Double) -> Unit
) {
    if (!hasAnyLocationPermission(context)) return

    val fusedLocationClient = LocationServices.getFusedLocationProviderClient(context)
    val cancellationTokenSource = CancellationTokenSource()
    val priority = if (hasFineLocationPermission(context)) {
        Priority.PRIORITY_HIGH_ACCURACY
    } else {
        Priority.PRIORITY_BALANCED_POWER_ACCURACY
    }

    fusedLocationClient.getCurrentLocation(
        priority,
        cancellationTokenSource.token
    ).addOnSuccessListener { location ->
        if (location != null) {
            onLocation(location.latitude, location.longitude)
        } else {
            fusedLocationClient.lastLocation.addOnSuccessListener { lastLocation ->
                lastLocation?.let {
                    onLocation(it.latitude, it.longitude)
                }
            }
        }
    }
}
