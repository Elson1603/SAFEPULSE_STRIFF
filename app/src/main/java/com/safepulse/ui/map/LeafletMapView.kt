package com.safepulse.ui.map

import android.annotation.SuppressLint
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.webkit.ConsoleMessage
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import com.google.android.gms.maps.model.LatLng
import com.google.maps.android.PolyUtil
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * Callback interface for events coming from the Leaflet JavaScript map.
 */
data class LeafletMapCallbacks(
    val onMapReady: (LeafletMapController) -> Unit = {},
    val onMapTapped: (Double, Double) -> Unit = { _, _ -> },
    val onMarkerAdded: (String, Double, Double) -> Unit = { _, _, _ -> },
    val onMarkerRemoved: (String) -> Unit = {},
    val onRouteReady: (Double, Int, String) -> Unit = { _, _, _ -> },
    val onRouteError: (String) -> Unit = {}
)

/**
 * Leaflet-based map composable using a WebView loading leaflet_map.html from assets.
 * Supports advanced features: tap-to-add markers, marker removal, OSRM routing,
 * dynamic location updates, and bidirectional Kotlin <-> JavaScript communication.
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun LeafletMapView(
    modifier: Modifier = Modifier,
    onMapReady: (LeafletMapController) -> Unit = {},
    callbacks: LeafletMapCallbacks = LeafletMapCallbacks(onMapReady = onMapReady)
) {
    val mainHandler = remember { Handler(Looper.getMainLooper()) }

    AndroidView(
        factory = { ctx ->
            WebView.setWebContentsDebuggingEnabled(true)
            val tileCache = TileCacheManager(ctx)

            WebView(ctx).apply {
                layoutParams = android.view.ViewGroup.LayoutParams(
                    android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                    android.view.ViewGroup.LayoutParams.MATCH_PARENT
                )

                @Suppress("DEPRECATION")
                settings.apply {
                    javaScriptEnabled = true
                    domStorageEnabled = true
                    allowFileAccess = true
                    allowContentAccess = true
                    allowFileAccessFromFileURLs = true
                    allowUniversalAccessFromFileURLs = true
                    mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                    // Use cache when available, fetch from network otherwise
                    cacheMode = if (isNetworkAvailable(ctx)) {
                        WebSettings.LOAD_DEFAULT
                    } else {
                        WebSettings.LOAD_CACHE_ELSE_NETWORK
                    }
                }

                val ctrl = LeafletMapController(this)

                addJavascriptInterface(object {
                    @JavascriptInterface
                    fun onMapReady() {
                        mainHandler.post { callbacks.onMapReady(ctrl) }
                    }

                    @JavascriptInterface
                    fun onMapTapped(lat: Double, lng: Double) {
                        mainHandler.post { callbacks.onMapTapped(lat, lng) }
                    }

                    @JavascriptInterface
                    fun onMarkerAdded(markerId: String, lat: Double, lng: Double) {
                        mainHandler.post { callbacks.onMarkerAdded(markerId, lat, lng) }
                    }

                    @JavascriptInterface
                    fun onMarkerRemoved(markerId: String) {
                        mainHandler.post { callbacks.onMarkerRemoved(markerId) }
                    }

                    @JavascriptInterface
                    fun onRouteReady(distanceKm: Double, durationMin: Int, coordsJson: String) {
                        mainHandler.post { callbacks.onRouteReady(distanceKm, durationMin, coordsJson) }
                    }

                    @JavascriptInterface
                    fun onRouteError(error: String) {
                        mainHandler.post { callbacks.onRouteError(error) }
                    }

                    @JavascriptInterface
                    fun getCacheSize(): String {
                        return tileCache.getCacheSizeFormatted()
                    }
                }, "AndroidBridge")

                webViewClient = object : WebViewClient() {
                    override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                        return false
                    }

                    /**
                     * Intercept tile requests for offline caching.
                     * - If tile is cached on disk, serve it directly (works offline)
                     * - If online, fetch tile, cache it, then serve
                     * - Only intercepts OSM tile.openstreetmap.org PNG requests
                     */
                    override fun shouldInterceptRequest(
                        view: WebView?,
                        request: WebResourceRequest?
                    ): WebResourceResponse? {
                        val url = request?.url?.toString() ?: return null

                        // Only intercept OSM tile requests
                        if (!isTileUrl(url)) return null

                        // Try serving from disk cache first
                        val cachedBytes = tileCache.getCachedTileBytes(url)
                        if (cachedBytes != null) {
                            return WebResourceResponse(
                                "image/png",
                                null,
                                ByteArrayInputStream(cachedBytes)
                            )
                        }

                        // Not cached — fetch from network, cache, then serve
                        return try {
                            val connection = URL(url).openConnection() as HttpURLConnection
                            connection.connectTimeout = 10000
                            connection.readTimeout = 10000
                            connection.setRequestProperty("User-Agent", "SafePulse/1.0")

                            if (connection.responseCode == HttpURLConnection.HTTP_OK) {
                                val bytes = connection.inputStream.use { it.readBytes() }
                                // Cache the tile to disk
                                tileCache.cacheTile(url, bytes)
                                // Periodic eviction check (lightweight)
                                if (Math.random() < 0.01) {
                                    tileCache.evictIfNeeded()
                                }
                                WebResourceResponse(
                                    "image/png",
                                    null,
                                    ByteArrayInputStream(bytes)
                                )
                            } else {
                                null // Let WebView handle the error
                            }
                        } catch (e: Exception) {
                            // Network error — return null to show default error tile
                            Log.w("LeafletMapView", "Tile fetch failed (offline?): ${e.message}")
                            null
                        }
                    }

                    override fun onReceivedError(
                        view: WebView?,
                        request: WebResourceRequest?,
                        error: WebResourceError?
                    ) {
                        Log.e("LeafletMapView", "WebView error: ${error?.description} for ${request?.url}")
                    }

                    override fun onPageFinished(view: WebView?, url: String?) {
                        Log.d("LeafletMapView", "Page loaded: $url")
                    }
                }

                webChromeClient = object : WebChromeClient() {
                    override fun onConsoleMessage(message: ConsoleMessage?): Boolean {
                        Log.d("LeafletMapView", "JS: ${message?.message()} [${message?.sourceId()}:${message?.lineNumber()}]")
                        return true
                    }
                }

                loadUrl("file:///android_asset/leaflet_map.html")
            }
        },
        modifier = modifier
    )
}

/**
 * Check if a URL is an OSM tile request that should be cached.
 */
private fun isTileUrl(url: String): Boolean {
    return url.contains("tile.openstreetmap.org") && url.endsWith(".png")
}

/**
 * Check if the device has network connectivity.
 */
private fun isNetworkAvailable(context: android.content.Context): Boolean {
    val cm = context.getSystemService(android.content.Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        ?: return false
    val network = cm.activeNetwork ?: return false
    val capabilities = cm.getNetworkCapabilities(network) ?: return false
    return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
}

/**
 * Controller to interact with the Leaflet map via JavaScript calls.
 * Provides methods for all map operations: markers, routing, circles, polylines, etc.
 */
class LeafletMapController(private val webView: WebView) {

    // ─── View Control ────────────────────────────────────
    fun setCenter(lat: Double, lng: Double, zoom: Float = 12f) {
        js("setCenter($lat, $lng, $zoom)")
    }

    fun animateTo(lat: Double, lng: Double, zoom: Float = 12f) {
        js("animateTo($lat, $lng, $zoom)")
    }

    fun animateTo(latLng: LatLng, zoom: Float = 12f) {
        animateTo(latLng.latitude, latLng.longitude, zoom)
    }

    // ─── Clear Operations ────────────────────────────────
    fun clearAll() {
        js("clearAll()")
    }

    fun clearRoutes() {
        js("clearRoutes()")
    }

    fun clearUserMarkers() {
        js("clearUserMarkers()")
    }

    // ─── Current Location (updates dynamically) ──────────
    fun setCurrentLocation(lat: Double, lng: Double) {
        js("setCurrentLocation($lat, $lng)")
    }

    fun setCurrentLocation(latLng: LatLng) {
        setCurrentLocation(latLng.latitude, latLng.longitude)
    }

    // ─── Markers ─────────────────────────────────────────
    fun addMarker(lat: Double, lng: Double, title: String, snippet: String = "", color: String = "#F44336") {
        js("addMarker($lat, $lng, '${esc(title)}', '${esc(snippet)}', '$color')")
    }

    fun addMarkerWithIcon(lat: Double, lng: Double, title: String, snippet: String = "", emoji: String, bgColor: String) {
        js("addMarkerWithIcon($lat, $lng, '${esc(title)}', '${esc(snippet)}', '$emoji', '$bgColor')")
    }

    // ─── User-Added Markers (tap-to-add & removable) ──────
    fun addUserMarker(lat: Double, lng: Double, title: String? = null) {
        val t = if (title != null) "'${esc(title)}'" else "null"
        js("addUserMarker($lat, $lng, $t)")
    }

    fun removeUserMarker(markerId: String) {
        js("removeUserMarker('${esc(markerId)}')")
    }

    fun enableTapToAdd() {
        js("enableTapToAdd()")
    }

    fun disableTapToAdd() {
        js("disableTapToAdd()")
    }

    // ─── Police Stations Layer ─────────────────────────────
    fun addPoliceStations(stations: List<PoliceStationData>) {
        val arr = JSONArray()
        stations.forEach { s ->
            arr.put(JSONObject().apply {
                put("lat", s.lat)
                put("lng", s.lng)
                put("name", s.name)
            })
        }
        js("addPoliceStations('${arr.toString().replace("'", "\\'")}')")
    }

    fun clearPolice() {
        js("clearPolice()")
    }

    // ─── Hospitals Layer ──────────────────────────────────────
    fun addHospitals(hospitals: List<HospitalData>) {
        val arr = JSONArray()
        hospitals.forEach { h ->
            arr.put(JSONObject().apply {
                put("lat", h.lat)
                put("lng", h.lng)
                put("name", h.name)
            })
        }
        js("addHospitals('${arr.toString().replace("'", "\\'")}')")
    }

    fun clearHospitals() {
        js("clearHospitals()")
    }

    // ─── Safe Zones Layer ─────────────────────────────────────
    fun addSafeZones(zones: List<SafeZoneData>) {
        val arr = JSONArray()
        zones.forEach { z ->
            arr.put(JSONObject().apply {
                put("lat", z.lat)
                put("lng", z.lng)
                put("name", z.name)
                put("state", z.state)
                put("radiusMeters", z.radiusMeters)
            })
        }
        js("addSafeZones('${arr.toString().replace("'", "\\'")}')")
    }

    fun clearSafeZones() {
        js("clearSafeZones()")
    }

    // ─── OSRM Routing ────────────────────────────────────
    fun drawRoute(startLat: Double, startLng: Double, endLat: Double, endLng: Double) {
        js("drawRoute($startLat, $startLng, $endLat, $endLng)")
    }

    fun drawRoute(start: LatLng, end: LatLng) {
        drawRoute(start.latitude, start.longitude, end.latitude, end.longitude)
    }

    /**
     * Draw the safest route between two points, analyzing OSRM alternatives against crime zones.
     * Crime zones are passed as JSON to JavaScript for client-side risk scoring.
     */
    fun drawSafeRoute(
        start: LatLng, end: LatLng,
        crimeZones: List<CrimeZoneData> = emptyList()
    ) {
        val arr = JSONArray()
        crimeZones.forEach { z ->
            arr.put(JSONObject().apply {
                put("lat", z.lat)
                put("lng", z.lng)
                put("radiusMeters", z.radiusMeters)
                put("crimeRiskScore", z.crimeRiskScore)
            })
        }
        val zonesStr = arr.toString().replace("'", "\\'").replace("\n", "")
        js("drawSafeRoute(${start.latitude}, ${start.longitude}, ${end.latitude}, ${end.longitude}, '$zonesStr')")
    }

    fun drawRouteViaWaypoints(waypoints: List<LatLng>, color: String = "#2196F3", weight: Int = 6) {
        val arr = JSONArray()
        waypoints.forEach { p ->
            val pt = JSONArray()
            pt.put(p.latitude)
            pt.put(p.longitude)
            arr.put(pt)
        }
        js("drawRouteViaWaypoints('${arr.toString().replace("'", "\\'")}', '$color', $weight)")
    }

    // ─── Circles ─────────────────────────────────────────
    fun addCircle(
        lat: Double, lng: Double, radiusMeters: Double,
        fillColor: String, strokeColor: String,
        fillOpacity: Double = 0.25, strokeOpacity: Double = 0.5
    ) {
        js("addCircle($lat, $lng, $radiusMeters, '$fillColor', '$strokeColor', $fillOpacity, $strokeOpacity)")
    }

    // ─── Polylines ───────────────────────────────────────
    fun addPolyline(points: List<LatLng>, color: String = "#2196F3", weight: Int = 6, dashArray: String? = null) {
        val arr = JSONArray()
        points.forEach { p ->
            val pt = JSONArray()
            pt.put(p.latitude)
            pt.put(p.longitude)
            arr.put(pt)
        }
        val dash = if (dashArray != null) "'$dashArray'" else "null"
        js("addPolyline('${arr.toString().replace("'", "\\'")}', '$color', $weight, $dash)")
    }

    fun addEncodedPolyline(encoded: String, color: String = "#2196F3", weight: Int = 6, dashArray: String? = null) {
        val points = PolyUtil.decode(encoded)
        addPolyline(points, color, weight, dashArray)
    }

    // ─── Bounds ──────────────────────────────────────────
    fun fitBounds(bounds: List<LatLng>) {
        if (bounds.size < 2) return
        val arr = JSONArray()
        bounds.forEach { p ->
            val pt = JSONArray()
            pt.put(p.latitude)
            pt.put(p.longitude)
            arr.put(pt)
        }
        js("fitBounds('${arr.toString().replace("'", "\\'")}')")
    }

    // ─── Batch Update ────────────────────────────────────
    fun batchUpdate(data: MapUpdateData) {
        val json = data.toJson()
        js("batchUpdate('${json.replace("'", "\\'").replace("\n", "")}')")
    }

    // ─── Internals ───────────────────────────────────────
    private fun js(script: String) {
        webView.post {
            webView.evaluateJavascript(script, null)
        }
    }

    private fun esc(s: String): String = s.replace("'", "\\'").replace("\n", " ")
}

/**
 * Data class for batch map updates.
 */
data class MapUpdateData(
    val clear: Boolean = false,
    val center: Triple<Double, Double, Float>? = null,
    val currentLocation: Pair<Double, Double>? = null,
    val markers: List<MarkerData> = emptyList(),
    val circles: List<CircleData> = emptyList(),
    val polylines: List<PolylineData> = emptyList(),
    val fitBoundsPoints: List<LatLng>? = null,
    val fitToLayers: Boolean = false
) {
    fun toJson(): String {
        val obj = JSONObject()
        if (clear) obj.put("clear", true)

        center?.let {
            obj.put("center", JSONArray().apply {
                put(it.first); put(it.second); put(it.third)
            })
        }

        currentLocation?.let {
            obj.put("currentLocation", JSONArray().apply {
                put(it.first); put(it.second)
            })
        }

        if (markers.isNotEmpty()) {
            val arr = JSONArray()
            markers.forEach { m ->
                arr.put(JSONObject().apply {
                    put("lat", m.lat)
                    put("lng", m.lng)
                    put("title", m.title)
                    put("snippet", m.snippet)
                    put("color", m.color)
                    if (m.emoji != null) {
                        put("emoji", m.emoji)
                        put("bgColor", m.bgColor ?: m.color)
                    }
                })
            }
            obj.put("markers", arr)
        }

        if (circles.isNotEmpty()) {
            val arr = JSONArray()
            circles.forEach { c ->
                arr.put(JSONObject().apply {
                    put("lat", c.lat)
                    put("lng", c.lng)
                    put("radius", c.radius)
                    put("fillColor", c.fillColor)
                    put("strokeColor", c.strokeColor)
                    put("fillOpacity", c.fillOpacity)
                    put("strokeOpacity", c.strokeOpacity)
                })
            }
            obj.put("circles", arr)
        }

        if (polylines.isNotEmpty()) {
            val arr = JSONArray()
            polylines.forEach { p ->
                val lineObj = JSONObject()
                val points = JSONArray()
                p.points.forEach { pt ->
                    points.put(JSONArray().apply { put(pt.latitude); put(pt.longitude) })
                }
                lineObj.put("points", points)
                lineObj.put("color", p.color)
                lineObj.put("weight", p.weight)
                if (p.dashArray != null) lineObj.put("dashArray", p.dashArray)
                arr.put(lineObj)
            }
            obj.put("polylines", arr)
        }

        fitBoundsPoints?.let { points ->
            if (points.size >= 2) {
                val arr = JSONArray()
                points.forEach { p ->
                    arr.put(JSONArray().apply { put(p.latitude); put(p.longitude) })
                }
                obj.put("fitBounds", arr)
            }
        }

        if (fitToLayers) obj.put("fitToLayers", true)

        return obj.toString()
    }
}

data class MarkerData(
    val lat: Double,
    val lng: Double,
    val title: String = "",
    val snippet: String = "",
    val color: String = "#F44336",
    val emoji: String? = null,
    val bgColor: String? = null
)

data class CircleData(
    val lat: Double,
    val lng: Double,
    val radius: Double,
    val fillColor: String,
    val strokeColor: String,
    val fillOpacity: Double = 0.25,
    val strokeOpacity: Double = 0.5
)

data class PolylineData(
    val points: List<LatLng>,
    val color: String = "#2196F3",
    val weight: Int = 6,
    val dashArray: String? = null
)

data class PoliceStationData(
    val lat: Double,
    val lng: Double,
    val name: String
)

data class CrimeZoneData(
    val lat: Double,
    val lng: Double,
    val radiusMeters: Double,
    val crimeRiskScore: Float
)

data class HospitalData(
    val lat: Double,
    val lng: Double,
    val name: String
)

data class SafeZoneData(
    val lat: Double,
    val lng: Double,
    val name: String,
    val state: String = "",
    val radiusMeters: Double = 2000.0
)
