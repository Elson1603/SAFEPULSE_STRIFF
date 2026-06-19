package com.safepulse.data.repository

import android.content.Context
import com.google.android.gms.maps.model.LatLng
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.safepulse.domain.riskmap.*
import com.safepulse.ui.map.CrimeZoneData
import com.safepulse.ui.map.HospitalData
import com.safepulse.ui.map.PoliceStationData
import com.safepulse.ui.map.SafeZoneData
import kotlin.math.*

/**
 * Repository that loads pre-processed crime and disaster risk data from CSV-trained JSON assets.
 * Data sources: crime_dataset_india.csv (40K+ crime records) and landslide.csv (4K+ disaster records)
 */
class RiskZoneRepository(private val context: Context) {

    private var crimeZonesCache: List<CrimeRiskZone>? = null
    private var disasterZonesCache: List<DisasterRiskZone>? = null
    private var safetyPlacesCache: List<SafetyPlace>? = null

    fun loadCrimeRiskZones(): List<CrimeRiskZone> {
        crimeZonesCache?.let { return it }

        return try {
            val json = context.assets.open("crime_risk_zones.json")
                .bufferedReader().use { it.readText() }

            val type = object : TypeToken<List<CrimeRiskZoneJson>>() {}.type
            val parsed: List<CrimeRiskZoneJson> = Gson().fromJson(json, type)

            parsed.map { it.toDomain() }.also { crimeZonesCache = it }
        } catch (e: Exception) {
            e.printStackTrace()
            emptyList()
        }
    }

    fun loadDisasterRiskZones(): List<DisasterRiskZone> {
        disasterZonesCache?.let { return it }

        return try {
            val json = context.assets.open("disaster_risk_zones.json")
                .bufferedReader().use { it.readText() }

            val type = object : TypeToken<List<DisasterRiskZoneJson>>() {}.type
            val parsed: List<DisasterRiskZoneJson> = Gson().fromJson(json, type)

            parsed.map { it.toDomain() }.also { disasterZonesCache = it }
        } catch (e: Exception) {
            e.printStackTrace()
            emptyList()
        }
    }

    fun loadAllRiskData(): CombinedRiskData {
        return CombinedRiskData(
            crimeZones = loadCrimeRiskZones(),
            disasterZones = loadDisasterRiskZones()
        )
    }

    /**
     * Load police stations from police_stations_india.json (3,704 OSM entries)
     * and hospitals from emergency_services.json, combining into one list.
     */
    fun loadSafetyPlaces(): List<SafetyPlace> {
        safetyPlacesCache?.let { return it }

        val places = mutableListOf<SafetyPlace>()

        // Load police stations from the comprehensive OSM dataset
        try {
            val policeJson = context.assets.open("police_stations_india.json")
                .bufferedReader().use { it.readText() }
            val policeType = object : TypeToken<List<PoliceStationJson>>() {}.type
            val policeStations: List<PoliceStationJson> = Gson().fromJson(policeJson, policeType)
            places.addAll(policeStations.map { it.toDomain() })
        } catch (e: Exception) {
            e.printStackTrace()
        }

        // Load hospitals from the comprehensive OSM dataset (54K entries)
        try {
            val hospitalJson = context.assets.open("hospitals_india.json")
                .bufferedReader().use { it.readText() }
            val hospitalType = object : TypeToken<List<HospitalJson>>() {}.type
            val hospitals: List<HospitalJson> = Gson().fromJson(hospitalJson, hospitalType)
            places.addAll(hospitals.map { it.toDomain() })
        } catch (e: Exception) {
            e.printStackTrace()
        }

        safetyPlacesCache = places
        return places
    }

    /**
     * Get safety places near a given location, sorted by distance
     */
    fun getSafetyPlacesNear(
        location: LatLng,
        maxDistanceKm: Double = 50.0
    ): List<SafetyPlace> {
        return loadSafetyPlaces()
            .filter { distanceKm(location, it.location) <= maxDistanceKm }
            .sortedBy { distanceKm(location, it.location) }
    }

    /**
     * Get risk zones near a given location, sorted by distance
     */
    fun getCrimeZonesNear(location: LatLng, maxDistanceKm: Double = 50.0): List<CrimeRiskZone> {
        return loadCrimeRiskZones()
            .filter { distanceKm(location, it.location) <= maxDistanceKm }
            .sortedBy { distanceKm(location, it.location) }
    }

    fun getDisasterZonesNear(location: LatLng, maxDistanceKm: Double = 50.0): List<DisasterRiskZone> {
        return loadDisasterRiskZones()
            .filter { distanceKm(location, it.location) <= maxDistanceKm }
            .sortedBy { distanceKm(location, it.location) }
    }

    /**
     * Compute overall risk score at a given location considering all risk factors
     */
    fun computeRiskAtLocation(location: LatLng): Float {
        val crimeRisk = computeCrimeRisk(location)
        val disasterRisk = computeDisasterRisk(location)
        // Weighted combination: 60% crime, 40% disaster
        return (crimeRisk * 0.6f + disasterRisk * 0.4f).coerceIn(0f, 1f)
    }

    fun computeCrimeRisk(location: LatLng): Float {
        val crimeZones = loadCrimeRiskZones()
        var maxRisk = 0f

        for (zone in crimeZones) {
            val dist = distanceMeters(location, zone.location)
            if (dist <= zone.radiusMeters * 2) {
                val decay = 1f - (dist / (zone.radiusMeters * 2)).coerceIn(0f, 1f)
                val risk = zone.crimeRiskScore * decay
                if (risk > maxRisk) maxRisk = risk
            }
            // Also check individual hotspots
            for (hotspot in zone.hotspots) {
                val hDist = distanceMeters(location, hotspot.location)
                if (hDist <= 1000f) {
                    val hDecay = 1f - (hDist / 1000f)
                    val hRisk = hotspot.risk * hDecay
                    if (hRisk > maxRisk) maxRisk = hRisk
                }
            }
        }
        return maxRisk
    }

    fun computeDisasterRisk(location: LatLng): Float {
        val disasterZones = loadDisasterRiskZones()
        var maxRisk = 0f

        for (zone in disasterZones) {
            val dist = distanceMeters(location, zone.location)
            if (dist <= zone.radiusMeters * 2) {
                val decay = 1f - (dist / (zone.radiusMeters * 2)).coerceIn(0f, 1f)
                val risk = zone.combinedDisasterRisk * decay
                if (risk > maxRisk) maxRisk = risk
            }
        }
        return maxRisk
    }

    /**
     * Suggest safe route direction: returns waypoints that avoid high-risk zones
     */
    fun suggestSafeWaypoints(
        origin: LatLng,
        destination: LatLng
    ): List<SafeRouteOption> {
        val directDistance = distanceKm(origin, destination).toFloat()
        val crimeZones = loadCrimeRiskZones()
        val disasterZones = loadDisasterRiskZones()

        // Generate route options: direct, and 2 alternatives that curve away from high-risk areas
        val routes = mutableListOf<SafeRouteOption>()

        // Route 1: Direct path
        val directWaypoints = interpolateRoute(origin, destination, 10)
        val directCrimeRisk = evaluateRouteCrimeRisk(directWaypoints, crimeZones)
        val directDisasterRisk = evaluateRouteDisasterRisk(directWaypoints, disasterZones)
        val directWarnings = buildWarnings(directWaypoints, crimeZones, disasterZones)

        routes.add(
            SafeRouteOption(
                name = "Direct Route",
                waypoints = directWaypoints,
                totalRiskScore = directCrimeRisk * 0.6f + directDisasterRisk * 0.4f,
                crimeRisk = directCrimeRisk,
                disasterRisk = directDisasterRisk,
                distanceKm = directDistance,
                warnings = directWarnings
            )
        )

        // Route 2: Offset north/east to avoid risk zones
        val offset1 = generateOffsetRoute(origin, destination, 0.008, 10)
        val offset1Crime = evaluateRouteCrimeRisk(offset1, crimeZones)
        val offset1Disaster = evaluateRouteDisasterRisk(offset1, disasterZones)

        routes.add(
            SafeRouteOption(
                name = "Northern Alternative",
                waypoints = offset1,
                totalRiskScore = offset1Crime * 0.6f + offset1Disaster * 0.4f,
                crimeRisk = offset1Crime,
                disasterRisk = offset1Disaster,
                distanceKm = directDistance * 1.15f,
                warnings = buildWarnings(offset1, crimeZones, disasterZones)
            )
        )

        // Route 3: Offset south/west
        val offset2 = generateOffsetRoute(origin, destination, -0.008, 10)
        val offset2Crime = evaluateRouteCrimeRisk(offset2, crimeZones)
        val offset2Disaster = evaluateRouteDisasterRisk(offset2, disasterZones)

        routes.add(
            SafeRouteOption(
                name = "Southern Alternative",
                waypoints = offset2,
                totalRiskScore = offset2Crime * 0.6f + offset2Disaster * 0.4f,
                crimeRisk = offset2Crime,
                disasterRisk = offset2Disaster,
                distanceKm = directDistance * 1.2f,
                warnings = buildWarnings(offset2, crimeZones, disasterZones)
            )
        )

        // Mark safest route
        val safestIdx = routes.indices.minByOrNull { routes[it].totalRiskScore } ?: 0
        return routes.mapIndexed { index, route ->
            route.copy(isSafest = index == safestIdx)
        }.sortedBy { it.totalRiskScore }
    }

    // --- Private helpers ---

    private fun evaluateRouteCrimeRisk(
        waypoints: List<LatLng>,
        crimeZones: List<CrimeRiskZone>
    ): Float {
        var totalRisk = 0f
        for (point in waypoints) {
            for (zone in crimeZones) {
                val dist = distanceMeters(point, zone.location)
                if (dist <= zone.radiusMeters) {
                    val decay = 1f - (dist / zone.radiusMeters)
                    totalRisk += zone.crimeRiskScore * decay * 0.15f
                }
                for (hs in zone.hotspots) {
                    val hDist = distanceMeters(point, hs.location)
                    if (hDist <= 500f) {
                        totalRisk += hs.risk * (1f - hDist / 500f) * 0.1f
                    }
                }
            }
        }
        return totalRisk.coerceIn(0f, 1f)
    }

    private fun evaluateRouteDisasterRisk(
        waypoints: List<LatLng>,
        disasterZones: List<DisasterRiskZone>
    ): Float {
        var totalRisk = 0f
        for (point in waypoints) {
            for (zone in disasterZones) {
                val dist = distanceMeters(point, zone.location)
                if (dist <= zone.radiusMeters) {
                    val decay = 1f - (dist / zone.radiusMeters)
                    totalRisk += zone.combinedDisasterRisk * decay * 0.1f
                }
            }
        }
        return totalRisk.coerceIn(0f, 1f)
    }

    private fun buildWarnings(
        waypoints: List<LatLng>,
        crimeZones: List<CrimeRiskZone>,
        disasterZones: List<DisasterRiskZone>
    ): List<String> {
        val warnings = mutableListOf<String>()
        val seenCities = mutableSetOf<String>()

        for (point in waypoints) {
            for (zone in crimeZones) {
                if (zone.city !in seenCities && distanceMeters(point, zone.location) <= zone.radiusMeters) {
                    if (zone.crimeRiskScore >= 0.5f) {
                        warnings.add("⚠️ High crime area: ${zone.city} (${zone.totalCrimes} reported crimes)")
                        seenCities.add(zone.city)
                    }
                }
            }
            for (zone in disasterZones) {
                if (zone.city !in seenCities && distanceMeters(point, zone.location) <= zone.radiusMeters) {
                    if (zone.floodRisk >= 0.5f) {
                        warnings.add("🌊 Flood risk zone: ${zone.city}")
                        seenCities.add(zone.city)
                    }
                    if (zone.landslideRisk >= 0.5f) {
                        warnings.add("⛰️ Landslide risk: ${zone.city}")
                        seenCities.add(zone.city)
                    }
                }
            }
        }
        return warnings
    }

    private fun interpolateRoute(origin: LatLng, dest: LatLng, steps: Int): List<LatLng> {
        return (0..steps).map { step ->
            val fraction = step.toDouble() / steps
            LatLng(
                origin.latitude + (dest.latitude - origin.latitude) * fraction,
                origin.longitude + (dest.longitude - origin.longitude) * fraction
            )
        }
    }

    private fun generateOffsetRoute(
        origin: LatLng,
        dest: LatLng,
        offset: Double,
        steps: Int
    ): List<LatLng> {
        return (0..steps).map { step ->
            val fraction = step.toDouble() / steps
            // Apply sinusoidal offset that peaks at midpoint
            val curveAmount = sin(fraction * Math.PI) * offset
            LatLng(
                origin.latitude + (dest.latitude - origin.latitude) * fraction + curveAmount,
                origin.longitude + (dest.longitude - origin.longitude) * fraction + curveAmount * 0.5
            )
        }
    }

    /**
     * Get police stations near a location as PoliceStationData for the map layer
     */
    fun getPoliceStationsNear(location: LatLng, maxDistanceKm: Double = 30.0): List<PoliceStationData> {
        return loadSafetyPlaces()
            .filter { it.type == SafetyPlaceType.POLICE }
            .filter { distanceKm(location, it.location) <= maxDistanceKm }
            .sortedBy { distanceKm(location, it.location) }
            .map { PoliceStationData(it.location.latitude, it.location.longitude, it.name) }
    }

    /**
     * Get ALL police stations across India for map display
     */
    fun getAllPoliceStations(): List<PoliceStationData> {
        return loadSafetyPlaces()
            .filter { it.type == SafetyPlaceType.POLICE }
            .map { PoliceStationData(it.location.latitude, it.location.longitude, it.name) }
    }

    /**
     * Get ALL hospitals across India for map display
     */
    fun getAllHospitals(): List<HospitalData> {
        return loadSafetyPlaces()
            .filter { it.type == SafetyPlaceType.HOSPITAL }
            .map { HospitalData(it.location.latitude, it.location.longitude, it.name) }
    }

    /**
     * Get hospitals near a location for map display
     */
    fun getHospitalsNear(location: LatLng, maxDistanceKm: Double = 50.0): List<HospitalData> {
        return loadSafetyPlaces()
            .filter { it.type == SafetyPlaceType.HOSPITAL }
            .filter { distanceKm(location, it.location) <= maxDistanceKm }
            .sortedBy { distanceKm(location, it.location) }
            .map { HospitalData(it.location.latitude, it.location.longitude, it.name) }
    }

    /**
     * Get nearby low-risk areas for map display without pushing the full India layer.
     */
    fun getSafeZonesNear(location: LatLng, maxDistanceKm: Double = 50.0): List<SafeZoneData> {
        return loadCrimeRiskZones()
            .filter { it.crimeRiskScore < 0.3f }
            .filter { distanceKm(location, it.location) <= maxDistanceKm }
            .sortedBy { distanceKm(location, it.location) }
            .map { zone ->
                SafeZoneData(
                    lat = zone.location.latitude,
                    lng = zone.location.longitude,
                    name = "${zone.city} Safe Area",
                    state = zone.state,
                    radiusMeters = zone.radiusMeters.toDouble()
                )
            }
    }

    /**
     * Get safe zones (low crime areas) for map display across all India
     */
    fun getSafeZonesForMap(): List<SafeZoneData> {
        return loadCrimeRiskZones()
            .filter { it.crimeRiskScore < 0.3f }
            .map { zone ->
                SafeZoneData(
                    lat = zone.location.latitude,
                    lng = zone.location.longitude,
                    name = "${zone.city} Safe Area",
                    state = zone.state,
                    radiusMeters = zone.radiusMeters.toDouble()
                )
            }
    }

    /**
     * Get crime zones formatted for the map's safe-route scoring
     */
    fun getCrimeZonesForMap(): List<CrimeZoneData> {
        return loadCrimeRiskZones().map { zone ->
            CrimeZoneData(
                lat = zone.location.latitude,
                lng = zone.location.longitude,
                radiusMeters = zone.radiusMeters.toDouble(),
                crimeRiskScore = zone.crimeRiskScore
            )
        }
    }

    companion object {
        fun distanceMeters(p1: LatLng, p2: LatLng): Float {
            val earthRadius = 6371000.0
            val dLat = Math.toRadians(p2.latitude - p1.latitude)
            val dLng = Math.toRadians(p2.longitude - p1.longitude)
            val a = sin(dLat / 2).pow(2) +
                    cos(Math.toRadians(p1.latitude)) * cos(Math.toRadians(p2.latitude)) *
                    sin(dLng / 2).pow(2)
            val c = 2 * atan2(sqrt(a), sqrt(1 - a))
            return (earthRadius * c).toFloat()
        }

        fun distanceKm(p1: LatLng, p2: LatLng): Double {
            return distanceMeters(p1, p2).toDouble() / 1000.0
        }
    }
}

// --- JSON parsing models ---

private data class CrimeRiskZoneJson(
    val city: String,
    val state: String,
    val lat: Double,
    val lng: Double,
    val totalCrimes: Int,
    val violentCrimes: Int,
    val crimeRiskScore: Float,
    val violentCrimeRatio: Float,
    val radiusMeters: Float,
    val dominantCrimes: List<String>,
    val hotspots: List<CrimeHotspotJson>
) {
    fun toDomain() = CrimeRiskZone(
        city = city,
        state = state,
        location = LatLng(lat, lng),
        totalCrimes = totalCrimes,
        violentCrimes = violentCrimes,
        crimeRiskScore = crimeRiskScore,
        violentCrimeRatio = violentCrimeRatio,
        radiusMeters = radiusMeters,
        dominantCrimes = dominantCrimes,
        hotspots = hotspots.map { it.toDomain() }
    )
}

private data class CrimeHotspotJson(
    val lat: Double,
    val lng: Double,
    val risk: Float,
    val label: String
) {
    fun toDomain() = CrimeHotspot(
        location = LatLng(lat, lng),
        risk = risk,
        label = label
    )
}

private data class SafetyPlaceJson(
    val id: Long,
    val name: String,
    val type: String,
    val address: String,
    val phoneNumber: String,
    val lat: Double,
    val lng: Double,
    val city: String
) {
    fun toDomain() = SafetyPlace(
        id = id,
        name = name,
        type = when (type) {
            "HOSPITAL" -> SafetyPlaceType.HOSPITAL
            else -> SafetyPlaceType.POLICE
        },
        address = address,
        phoneNumber = phoneNumber,
        location = LatLng(lat, lng),
        city = city
    )
}

/**
 * JSON model for police_stations_india.json (OpenStreetMap data)
 */
private data class PoliceStationJson(
    val id: Long,
    val name: String,
    val lat: Double,
    val lng: Double
) {
    fun toDomain() = SafetyPlace(
        id = id,
        name = name,
        type = SafetyPlaceType.POLICE,
        address = "",
        phoneNumber = "",
        location = LatLng(lat, lng),
        city = ""
    )
}

/**
 * JSON model for hospitals_india.json (OpenStreetMap data, 54K hospitals)
 */
private data class HospitalJson(
    val id: Long,
    val name: String,
    val lat: Double,
    val lng: Double
) {
    fun toDomain() = SafetyPlace(
        id = id,
        name = name,
        type = SafetyPlaceType.HOSPITAL,
        address = "",
        phoneNumber = "",
        location = LatLng(lat, lng),
        city = ""
    )
}

private data class DisasterRiskZoneJson(
    val city: String,
    val state: String,
    val lat: Double,
    val lng: Double,
    val landslideRisk: Float,
    val floodRisk: Float,
    val earthquakeFrequency: Int,
    val avgRainfall: Float,
    val elevation: Int,
    val radiusMeters: Float,
    val riskFactors: List<String>
) {
    fun toDomain() = DisasterRiskZone(
        city = city,
        state = state,
        location = LatLng(lat, lng),
        landslideRisk = landslideRisk,
        floodRisk = floodRisk,
        earthquakeFrequency = earthquakeFrequency,
        avgRainfall = avgRainfall,
        elevation = elevation,
        radiusMeters = radiusMeters,
        riskFactors = riskFactors
    )
}
