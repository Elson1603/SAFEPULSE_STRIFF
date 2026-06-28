package com.safepulse.data.repository

import android.content.Context
import com.google.android.gms.maps.model.LatLng
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.safepulse.data.cache.MapDataCacheManager
import com.safepulse.domain.riskmap.*
import com.safepulse.ui.map.CrimeZoneData
import com.safepulse.ui.map.HospitalData
import com.safepulse.ui.map.PoliceStationData
import com.safepulse.ui.map.SafeZoneData
import kotlin.math.*

/**
 * Repository that loads risk data for the map.
 * Primary source: live public feeds. master_dataset.csv and legacy JSON assets are fallback.
 */
class RiskZoneRepository(private val context: Context) {

    private val liveRiskFeedClient = LiveRiskFeedClient()
    private val livePoliceStationClient = LivePoliceStationClient()
    private val mapDataCache = MapDataCacheManager(context)
    private var crimeZonesCache: List<CrimeRiskZone>? = null
    private var curatedCrimeZonesCache: List<CrimeRiskZone>? = null
    private var disasterZonesCache: List<DisasterRiskZone>? = null
    private var safetyPlacesCache: List<SafetyPlace>? = null
    private var policePlacesCache: List<SafetyPlace>? = null
    private var hospitalPlacesCache: List<SafetyPlace>? = null
    private var liveRiskDataCache: CombinedRiskData? = null
    private var liveRiskFetchedAtMillis: Long = 0L
    private var livePoliceCache: List<SafetyPlace> = emptyList()
    private var livePoliceCacheCenter: LatLng? = null
    private var livePoliceCacheRadiusKm: Double = 0.0
    private var livePoliceFetchedAtMillis: Long = 0L

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

    fun loadCuratedCrimeRiskZones(): List<CrimeRiskZone> {
        curatedCrimeZonesCache?.let { return it }

        return try {
            val json = context.assets.open("crime_risk_zones.json")
                .bufferedReader().use { it.readText() }

            val type = object : TypeToken<List<CrimeRiskZoneJson>>() {}.type
            val parsed: List<CrimeRiskZoneJson> = Gson().fromJson(json, type)

            parsed.map { it.toDomain() }.also { curatedCrimeZonesCache = it }
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

    suspend fun loadLiveRiskDataPreferred(forceRefresh: Boolean = false): CombinedRiskData {
        val now = System.currentTimeMillis()
        if (!forceRefresh && now - liveRiskFetchedAtMillis < LIVE_RISK_TTL_MILLIS) {
            liveRiskDataCache?.let { return it }
        }

        val liveResult = runCatching {
            liveRiskFeedClient.fetchLiveRiskFeeds()
        }.getOrNull()

        val liveCrimeZones = liveResult?.crimeZones.orEmpty()
        val merged = CombinedRiskData(
            crimeZones = liveCrimeZones.ifEmpty { loadCrimeRiskZones() },
            disasterZones = emptyList()
        )

        liveRiskDataCache = merged
        liveRiskFetchedAtMillis = now
        crimeZonesCache = merged.crimeZones
        disasterZonesCache = merged.disasterZones
        return merged
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
     * Load police stations and hospitals from bundled OSM datasets.
     */
    fun loadSafetyPlaces(): List<SafetyPlace> {
        safetyPlacesCache?.let { return it }

        return (loadPolicePlaces() + loadHospitalPlaces())
            .also { safetyPlacesCache = it }
    }

    fun loadPolicePlaces(): List<SafetyPlace> {
        policePlacesCache?.let { return it }

        val places = mutableListOf<SafetyPlace>()
        val seenPoliceKeys = mutableSetOf<String>()

        // Load police stations from the comprehensive OSM dataset
        try {
            val policeJson = context.assets.open("police_stations_india.json")
                .bufferedReader().use { it.readText() }
            val policeType = object : TypeToken<List<PoliceStationJson>>() {}.type
            val policeStations: List<PoliceStationJson> = Gson().fromJson(policeJson, policeType)
            policeStations.map { it.toDomain() }.forEach { place ->
                seenPoliceKeys.add(place.dedupeKey())
                places.add(place)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }

        // Merge additional police facilities from the GeoJSON export.
        try {
            val facilitiesJson = context.assets.open("emergency_facilities.geojson")
                .bufferedReader().use { it.readText() }
            val facilitiesType = object : TypeToken<EmergencyFacilitiesGeoJson>() {}.type
            val facilities: EmergencyFacilitiesGeoJson = Gson().fromJson(facilitiesJson, facilitiesType)
            facilities.features
                .asSequence()
                .mapIndexedNotNull { index, feature -> feature.toPolicePlace(index) }
                .filter { seenPoliceKeys.add(it.dedupeKey()) }
                .forEach { places.add(it) }
        } catch (e: Exception) {
            e.printStackTrace()
        }

        policePlacesCache = places
        return places
    }

    fun loadHospitalPlaces(): List<SafetyPlace> {
        hospitalPlacesCache?.let { return it }

        // Load hospitals from the comprehensive OSM dataset (54K entries)
        val places = try {
            val hospitalJson = context.assets.open("hospitals_india.json")
                .bufferedReader().use { it.readText() }
            val hospitalType = object : TypeToken<List<HospitalJson>>() {}.type
            val hospitals: List<HospitalJson> = Gson().fromJson(hospitalJson, hospitalType)
            hospitals.map { it.toDomain() }
        } catch (e: Exception) {
            e.printStackTrace()
            emptyList()
        }

        hospitalPlacesCache = places
        return places
    }

    /**
     * Get safety places near a given location, sorted by distance
     */
    fun getSafetyPlacesNear(
        location: LatLng,
        maxDistanceKm: Double = 50.0
    ): List<SafetyPlace> {
        return getSafetyPlacesNear(location, maxDistanceKm, type = null)
    }

    fun getSafetyPlacesNear(
        location: LatLng,
        maxDistanceKm: Double,
        type: SafetyPlaceType?,
        maxResults: Int = Int.MAX_VALUE
    ): List<SafetyPlace> {
        val cacheKey = nearbySafetyCacheKey(location, maxDistanceKm, type, maxResults)
        mapDataCache.get<List<CachedSafetyPlace>>(
            namespace = MAP_CACHE_NAMESPACE_NEARBY_SAFETY,
            key = cacheKey,
            type = CACHED_SAFETY_PLACE_LIST_TYPE,
            ttlMillis = NEARBY_SAFETY_CACHE_TTL_MILLIS
        )?.let { cached ->
            return cached.map { it.toDomain() }
        }

        val places = when (type) {
            SafetyPlaceType.POLICE -> loadPolicePlaces()
            SafetyPlaceType.HOSPITAL -> loadHospitalPlaces()
            null -> loadSafetyPlaces()
        }

        val result = places
            .asSequence()
            .map { place -> place to distanceKm(location, place.location) }
            .filter { it.second <= maxDistanceKm }
            .sortedBy { it.second }
            .take(maxResults)
            .map { it.first }
            .toList()

        if (result.isNotEmpty()) {
            mapDataCache.put(
                namespace = MAP_CACHE_NAMESPACE_NEARBY_SAFETY,
                key = cacheKey,
                value = result.map { it.toCachedSafetyPlace() }
            )
        }

        return result
    }

    /**
     * Get nearby police stations from bundled OSM assets, with a live Overpass fallback
     * when the local dataset has too few results around the user.
     */
    suspend fun getPolicePlacesNearIncludingLive(
        location: LatLng,
        maxDistanceKm: Double = 30.0,
        maxResults: Int = Int.MAX_VALUE
    ): List<SafetyPlace> {
        val bundled = getSafetyPlacesNear(
            location = location,
            maxDistanceKm = maxDistanceKm,
            type = SafetyPlaceType.POLICE,
            maxResults = maxResults
        )

        val live = if (bundled.size < MIN_NEARBY_POLICE_RESULTS) {
            fetchLivePolicePlaces(location, maxDistanceKm, maxResults)
        } else {
            emptyList()
        }

        return mergeSafetyPlaces(location, maxDistanceKm, maxResults, bundled, live)
    }

    private suspend fun fetchLivePolicePlaces(
        location: LatLng,
        maxDistanceKm: Double,
        maxResults: Int
    ): List<SafetyPlace> {
        val now = System.currentTimeMillis()
        val cachedCenter = livePoliceCacheCenter
        val canReuseCache = cachedCenter != null &&
                now - livePoliceFetchedAtMillis < LIVE_POLICE_TTL_MILLIS &&
                livePoliceCacheRadiusKm >= maxDistanceKm &&
                distanceKm(cachedCenter, location) <= LIVE_POLICE_CACHE_REUSE_DISTANCE_KM

        if (canReuseCache) return livePoliceCache

        val cacheKey = livePoliceCacheKey(location, maxDistanceKm, maxResults)
        mapDataCache.get<List<CachedSafetyPlace>>(
            namespace = MAP_CACHE_NAMESPACE_LIVE_POLICE,
            key = cacheKey,
            type = CACHED_SAFETY_PLACE_LIST_TYPE,
            ttlMillis = LIVE_POLICE_DISK_CACHE_TTL_MILLIS
        )?.let { cached ->
            val cachedPlaces = cached.map { it.toDomain() }
            livePoliceCache = cachedPlaces
            livePoliceCacheCenter = location
            livePoliceCacheRadiusKm = maxDistanceKm
            livePoliceFetchedAtMillis = now
            return cachedPlaces
        }

        val radiusMeters = (maxDistanceKm * 1000).toInt()
        val livePlaces = runCatching {
            livePoliceStationClient.fetchPoliceStationsNear(
                location = location,
                radiusMeters = radiusMeters,
                maxResults = maxResults.coerceAtMost(MAX_LIVE_POLICE_RESULTS)
            )
        }.getOrDefault(emptyList())

        livePoliceCache = livePlaces
        livePoliceCacheCenter = location
        livePoliceCacheRadiusKm = maxDistanceKm
        livePoliceFetchedAtMillis = now
        if (livePlaces.isNotEmpty()) {
            mapDataCache.put(
                namespace = MAP_CACHE_NAMESPACE_LIVE_POLICE,
                key = cacheKey,
                value = livePlaces.map { it.toCachedSafetyPlace() }
            )
        }
        return livePlaces
    }

    private fun mergeSafetyPlaces(
        location: LatLng,
        maxDistanceKm: Double,
        maxResults: Int,
        vararg sources: List<SafetyPlace>
    ): List<SafetyPlace> {
        return sources
            .flatMap { it }
            .asSequence()
            .filter { distanceKm(location, it.location) <= maxDistanceKm }
            .distinctBy { it.dedupeKey() }
            .sortedBy { distanceKm(location, it.location) }
            .take(maxResults)
            .toList()
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
        return computeCrimeRisk(location)
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

        // Generate route options: direct, and 2 alternatives that curve away from high-risk areas
        val routes = mutableListOf<SafeRouteOption>()

        // Route 1: Direct path
        val directWaypoints = interpolateRoute(origin, destination, 10)
        val directCrimeRisk = evaluateRouteCrimeRisk(directWaypoints, crimeZones)
        val directWarnings = buildCrimeWarnings(directWaypoints, crimeZones)

        routes.add(
            SafeRouteOption(
                name = "Direct Route",
                waypoints = directWaypoints,
                totalRiskScore = directCrimeRisk,
                crimeRisk = directCrimeRisk,
                disasterRisk = 0f,
                distanceKm = directDistance,
                warnings = directWarnings
            )
        )

        // Route 2: Offset north/east to avoid risk zones
        val offset1 = generateOffsetRoute(origin, destination, 0.008, 10)
        val offset1Crime = evaluateRouteCrimeRisk(offset1, crimeZones)

        routes.add(
            SafeRouteOption(
                name = "Northern Alternative",
                waypoints = offset1,
                totalRiskScore = offset1Crime,
                crimeRisk = offset1Crime,
                disasterRisk = 0f,
                distanceKm = directDistance * 1.15f,
                warnings = buildCrimeWarnings(offset1, crimeZones)
            )
        )

        // Route 3: Offset south/west
        val offset2 = generateOffsetRoute(origin, destination, -0.008, 10)
        val offset2Crime = evaluateRouteCrimeRisk(offset2, crimeZones)

        routes.add(
            SafeRouteOption(
                name = "Southern Alternative",
                waypoints = offset2,
                totalRiskScore = offset2Crime,
                crimeRisk = offset2Crime,
                disasterRisk = 0f,
                distanceKm = directDistance * 1.2f,
                warnings = buildCrimeWarnings(offset2, crimeZones)
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

    private fun buildCrimeWarnings(
        waypoints: List<LatLng>,
        crimeZones: List<CrimeRiskZone>
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
        return getSafetyPlacesNear(location, maxDistanceKm, SafetyPlaceType.POLICE)
            .map { PoliceStationData(it.location.latitude, it.location.longitude, it.name, "place_${it.id}") }
    }

    suspend fun getPoliceStationsNearIncludingLive(
        location: LatLng,
        maxDistanceKm: Double = 30.0,
        maxResults: Int = Int.MAX_VALUE
    ): List<PoliceStationData> {
        return getPolicePlacesNearIncludingLive(location, maxDistanceKm, maxResults)
            .map { PoliceStationData(it.location.latitude, it.location.longitude, it.name, "place_${it.id}") }
    }

    /**
     * Get ALL police stations across India for map display
     */
    fun getAllPoliceStations(): List<PoliceStationData> {
        return loadPolicePlaces()
            .map { PoliceStationData(it.location.latitude, it.location.longitude, it.name, "place_${it.id}") }
    }

    /**
     * Get ALL hospitals across India for map display
     */
    fun getAllHospitals(): List<HospitalData> {
        return loadHospitalPlaces()
            .map { HospitalData(it.location.latitude, it.location.longitude, it.name, "place_${it.id}") }
    }

    /**
     * Get hospitals near a location for map display
     */
    fun getHospitalsNear(location: LatLng, maxDistanceKm: Double = 50.0): List<HospitalData> {
        return getSafetyPlacesNear(location, maxDistanceKm, SafetyPlaceType.HOSPITAL)
            .map { HospitalData(it.location.latitude, it.location.longitude, it.name, "place_${it.id}") }
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

    fun getSafeZonesNearFast(location: LatLng, maxDistanceKm: Double = 50.0): List<SafeZoneData> {
        return loadCuratedCrimeRiskZones()
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

    fun getCrimeZonesForMapNearFast(
        location: LatLng,
        maxDistanceKm: Double = 100.0,
        maxResults: Int = 250
    ): List<CrimeZoneData> {
        return loadCuratedCrimeRiskZones()
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
        private const val LIVE_RISK_TTL_MILLIS = 15 * 60_000L
        private const val LIVE_POLICE_TTL_MILLIS = 30 * 60_000L
        private const val LIVE_POLICE_DISK_CACHE_TTL_MILLIS = 7L * 24L * 60L * 60L * 1000L
        private const val NEARBY_SAFETY_CACHE_TTL_MILLIS = 14L * 24L * 60L * 60L * 1000L
        private const val LIVE_POLICE_CACHE_REUSE_DISTANCE_KM = 2.0
        private const val MIN_NEARBY_POLICE_RESULTS = 5
        private const val MAX_LIVE_POLICE_RESULTS = 80
        private const val MAP_CACHE_NAMESPACE_NEARBY_SAFETY = "nearby_safety_v1"
        private const val MAP_CACHE_NAMESPACE_LIVE_POLICE = "live_police_v1"

        private val CACHED_SAFETY_PLACE_LIST_TYPE =
            object : TypeToken<List<CachedSafetyPlace>>() {}.type

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

private data class CachedSafetyPlace(
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
            SafetyPlaceType.HOSPITAL.name -> SafetyPlaceType.HOSPITAL
            else -> SafetyPlaceType.POLICE
        },
        address = address,
        phoneNumber = phoneNumber,
        location = LatLng(lat, lng),
        city = city
    )
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

private data class EmergencyFacilitiesGeoJson(
    val features: List<EmergencyFacilityFeature> = emptyList()
)

private data class EmergencyFacilityFeature(
    val id: String? = null,
    val properties: EmergencyFacilityProperties? = null,
    val geometry: EmergencyFacilityGeometry? = null
) {
    fun toPolicePlace(index: Int): SafetyPlace? {
        val props = properties ?: return null
        if (!props.isPoliceFacility()) return null
        val coordinates = geometry?.coordinates ?: return null
        if (coordinates.size < 2) return null
        val lng = coordinates[0]
        val lat = coordinates[1]
        if (!LatLng(lat, lng).isLikelyIndiaCoordinate()) return null

        return SafetyPlace(
            id = GEOJSON_POLICE_ID_OFFSET + stablePoliceId(id, props.name, index),
            name = props.name?.takeIf { it.isNotBlank() } ?: "Police Station",
            type = SafetyPlaceType.POLICE,
            address = "",
            phoneNumber = "",
            location = LatLng(lat, lng),
            city = ""
        )
    }
}

private data class EmergencyFacilityProperties(
    val amenity: String? = null,
    val name: String? = null,
    val police: String? = null
) {
    fun isPoliceFacility(): Boolean {
        return amenity.equals("police", ignoreCase = true) ||
                police.equals("station", ignoreCase = true) ||
                police.equals("checkpoint", ignoreCase = true)
    }
}

private data class EmergencyFacilityGeometry(
    val type: String? = null,
    val coordinates: List<Double> = emptyList()
)

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

private fun SafetyPlace.dedupeKey(): String {
    val roundedLat = "%.5f".format(location.latitude)
    val roundedLng = "%.5f".format(location.longitude)
    return "${type.name}:${name.lowercase().trim()}:$roundedLat:$roundedLng"
}

private fun SafetyPlace.toCachedSafetyPlace(): CachedSafetyPlace {
    return CachedSafetyPlace(
        id = id,
        name = name,
        type = type.name,
        address = address,
        phoneNumber = phoneNumber,
        lat = location.latitude,
        lng = location.longitude,
        city = city
    )
}

private fun nearbySafetyCacheKey(
    location: LatLng,
    maxDistanceKm: Double,
    type: SafetyPlaceType?,
    maxResults: Int
): String {
    val resultLimit = if (maxResults == Int.MAX_VALUE) "all" else maxResults.toString()
    return listOf(
        cacheGridKey(location),
        "radius_${maxDistanceKm.toInt()}",
        "type_${type?.name ?: "ALL"}",
        "limit_$resultLimit"
    ).joinToString("|")
}

private fun livePoliceCacheKey(
    location: LatLng,
    maxDistanceKm: Double,
    maxResults: Int
): String {
    val resultLimit = if (maxResults == Int.MAX_VALUE) "all" else maxResults.toString()
    return listOf(
        cacheGridKey(location),
        "radius_${maxDistanceKm.toInt()}",
        "limit_$resultLimit"
    ).joinToString("|")
}

private fun cacheGridKey(location: LatLng): String {
    val lat = "%.3f".format(location.latitude)
    val lng = "%.3f".format(location.longitude)
    return "$lat,$lng"
}

private fun stablePoliceId(rawId: String?, name: String?, index: Int): Long {
    val digits = rawId?.filter { it.isDigit() }?.toLongOrNull()
    if (digits != null) return digits
    val hash = "${rawId.orEmpty()}|${name.orEmpty()}|$index".hashCode().toLong()
    return abs(hash)
}

private const val GEOJSON_POLICE_ID_OFFSET = 9_000_000_000L
