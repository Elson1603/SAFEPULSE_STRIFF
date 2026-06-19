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
 * Repository that loads risk data for the map.
 * Primary source: master_dataset.csv. Legacy JSON assets are used only as fallback.
 */
class RiskZoneRepository(private val context: Context) {

    private var crimeZonesCache: List<CrimeRiskZone>? = null
    private var disasterZonesCache: List<DisasterRiskZone>? = null
    private var safetyPlacesCache: List<SafetyPlace>? = null

    fun loadCrimeRiskZones(): List<CrimeRiskZone> {
        crimeZonesCache?.let { return it }

        loadMasterDatasetCrimeZones()?.let { zones ->
            crimeZonesCache = zones
            return zones
        }

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

        loadMasterDatasetDisasterZones()?.let { zones ->
            disasterZonesCache = zones
            return zones
        }

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

    private fun loadMasterDatasetCrimeZones(): List<CrimeRiskZone>? {
        return runCatching {
            context.assets.open(MASTER_DATASET_ASSET).bufferedReader().useLines { lines ->
                lines.drop(1)
                    .mapNotNull { line -> parseMasterDatasetRow(line)?.toCrimeRiskZone() }
                    .toList()
            }
        }.getOrNull()?.takeIf { it.isNotEmpty() }
    }

    private fun loadMasterDatasetDisasterZones(): List<DisasterRiskZone>? {
        return runCatching {
            context.assets.open(MASTER_DATASET_ASSET).bufferedReader().useLines { lines ->
                lines.drop(1)
                    .mapNotNull { line -> parseMasterDatasetRow(line)?.toDisasterRiskZone() }
                    .toList()
            }
        }.getOrNull()?.takeIf { it.isNotEmpty() }
    }

    private fun parseMasterDatasetRow(line: String): MasterRiskRow? {
        val c = line.split(',')
        if (c.size < 20) return null

        val start = LatLng(
            c[3].toDoubleOrNull() ?: return null,
            c[4].toDoubleOrNull() ?: return null
        )
        val end = LatLng(
            c[5].toDoubleOrNull() ?: return null,
            c[6].toDoubleOrNull() ?: return null
        )
        if (!start.isLikelyIndiaCoordinate() || !end.isLikelyIndiaCoordinate()) return null

        return MasterRiskRow(
            segmentId = c[0],
            region = c[1],
            subRegion = c[2],
            start = start,
            end = end,
            roadType = c[7],
            crimeType = c[8],
            crimeCount = c[9].toIntOrNull() ?: 0,
            crimeScore = c[10].toFloatOrNull() ?: 0f,
            timeSlot = c[11],
            weekday = c[12],
            crowdDensity = c[13].toIntOrNull() ?: 0,
            lightingScore = c[14].toIntOrNull() ?: 0,
            policeDistanceKm = c[15].toFloatOrNull() ?: 0f,
            weather = c[16],
            transitHub = c[17] == "1",
            riskScore = (c[18].toFloatOrNull() ?: 0f).coerceIn(0f, 1f),
            riskLabel = c[19]
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

    fun getCrimeZonesForMapNear(
        location: LatLng,
        maxDistanceKm: Double = 100.0,
        maxResults: Int = 500
    ): List<CrimeZoneData> {
        return loadCrimeRiskZones()
            .asSequence()
            .filter { distanceKm(location, it.location) <= maxDistanceKm }
            .sortedWith(compareByDescending<CrimeRiskZone> { it.crimeRiskScore }
                .thenBy { distanceKm(location, it.location) })
            .take(maxResults)
            .map { zone ->
                CrimeZoneData(
                    lat = zone.location.latitude,
                    lng = zone.location.longitude,
                    radiusMeters = zone.radiusMeters.toDouble(),
                    crimeRiskScore = zone.crimeRiskScore
                )
            }
            .toList()
    }

    companion object {
        private const val MASTER_DATASET_ASSET = "master_dataset.csv"

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

private data class MasterRiskRow(
    val segmentId: String,
    val region: String,
    val subRegion: String,
    val start: LatLng,
    val end: LatLng,
    val roadType: String,
    val crimeType: String,
    val crimeCount: Int,
    val crimeScore: Float,
    val timeSlot: String,
    val weekday: String,
    val crowdDensity: Int,
    val lightingScore: Int,
    val policeDistanceKm: Float,
    val weather: String,
    val transitHub: Boolean,
    val riskScore: Float,
    val riskLabel: String
) {
    private val midpoint: LatLng
        get() = LatLng(
            (start.latitude + end.latitude) / 2.0,
            (start.longitude + end.longitude) / 2.0
        )

    fun toCrimeRiskZone(): CrimeRiskZone {
        val segmentLength = RiskZoneRepository.distanceMeters(start, end)
        val radius = (segmentLength / 4f).coerceIn(700f, 6_000f)
        val violent = if (crimeType in VIOLENT_CRIMES) crimeCount else 0
        return CrimeRiskZone(
            city = subRegion,
            state = region,
            location = midpoint,
            totalCrimes = crimeCount,
            violentCrimes = violent,
            crimeRiskScore = riskScore,
            violentCrimeRatio = if (crimeCount > 0) violent.toFloat() / crimeCount else 0f,
            radiusMeters = radius,
            dominantCrimes = listOf(crimeType, roadType, timeSlot).filter { it.isNotBlank() },
            hotspots = listOf(
                CrimeHotspot(start, crimeScore.coerceIn(0f, 1f), "$crimeType start - $segmentId"),
                CrimeHotspot(end, riskScore, "$crimeType end - $segmentId")
            )
        )
    }

    fun toDisasterRiskZone(): DisasterRiskZone? {
        val weatherText = weather.lowercase()
        val floodRisk = when {
            "flood" in weatherText -> riskScore
            "rain" in weatherText || "storm" in weatherText -> riskScore * 0.65f
            else -> 0f
        }.coerceIn(0f, 1f)
        val landslideRisk = when {
            "landslide" in weatherText -> riskScore
            roadType.contains("ghat", ignoreCase = true) ||
                roadType.contains("hill", ignoreCase = true) -> riskScore * 0.55f
            else -> 0f
        }.coerceIn(0f, 1f)

        if (floodRisk < 0.4f && landslideRisk < 0.4f) return null

        return DisasterRiskZone(
            city = subRegion,
            state = region,
            location = midpoint,
            landslideRisk = landslideRisk,
            floodRisk = floodRisk,
            earthquakeFrequency = 0,
            avgRainfall = if (floodRisk > 0f) floodRisk * 100f else 0f,
            elevation = 0,
            radiusMeters = 3_000f,
            riskFactors = listOfNotNull(
                weather.takeIf { it.isNotBlank() },
                roadType.takeIf { it.isNotBlank() }
            )
        )
    }

    companion object {
        private val VIOLENT_CRIMES = setOf("Assault", "Homicide", "Rape", "Robbery")
    }
}

private fun LatLng.isLikelyIndiaCoordinate(): Boolean {
    return latitude in 6.0..37.5 && longitude in 68.0..98.5
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
