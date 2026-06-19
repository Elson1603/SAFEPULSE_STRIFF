package com.safepulse.ui.components

import com.google.android.gms.maps.model.LatLng
import com.google.maps.android.PolyUtil
import com.safepulse.domain.saferoutes.RiskLevel
import com.safepulse.domain.saferoutes.SafeRoute

/**
 * Create mock routes for testing without API calls
 */
fun createMockRoutes(
    origin: LatLng,
    destination: LatLng
): List<SafeRoute> {
    val route1Points = listOf(origin, destination)
    val route2Points = listOf(
        origin,
        LatLng(
            (origin.latitude + destination.latitude) / 2,
            (origin.longitude + destination.longitude) / 2 + 0.005
        ),
        destination
    )
    val route3Points = listOf(
        origin,
        LatLng(
            (origin.latitude + destination.latitude) / 2,
            (origin.longitude + destination.longitude) / 2 - 0.005
        ),
        destination
    )

    // Encode polylines manually
    val route1Polyline = PolyUtil.encode(route1Points)
    val route2Polyline = PolyUtil.encode(route2Points)
    val route3Polyline = PolyUtil.encode(route3Points)

    return listOf(
        SafeRoute(
            id = "test1",
            polyline = route1Polyline,
            distance = 3200L, // 3.2 km in meters
            duration = 480L,  // 8 mins in seconds
            riskScore = 0.2f,
            riskLevel = RiskLevel.LOW,
            summary = "Test Route 1 (Direct)",
            isRecommended = true
        ),
        SafeRoute(
            id = "test2",
            polyline = route2Polyline,
            distance = 3800L, // 3.8 km
            duration = 600L,  // 10 mins
            riskScore = 0.5f,
            riskLevel = RiskLevel.MEDIUM,
            summary = "Test Route 2 (Via North)",
            isRecommended = false
        ),
        SafeRoute(
            id = "test3",
            polyline = route3Polyline,
            distance = 4100L, // 4.1 km
            duration = 720L,  // 12 mins
            riskScore = 0.8f,
            riskLevel = RiskLevel.HIGH,
            summary = "Test Route 3 (Via South)",
            isRecommended = false
        )
    )
}
