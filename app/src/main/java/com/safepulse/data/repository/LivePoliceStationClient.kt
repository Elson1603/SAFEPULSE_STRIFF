package com.safepulse.data.repository

import com.google.android.gms.maps.model.LatLng
import com.google.gson.Gson
import com.safepulse.domain.riskmap.SafetyPlace
import com.safepulse.domain.riskmap.SafetyPlaceType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import java.time.Duration

/**
 * Fetches nearby police facilities from OpenStreetMap via the public Overpass API.
 */
class LivePoliceStationClient(
    private val httpClient: OkHttpClient = OkHttpClient.Builder()
        .callTimeout(Duration.ofSeconds(6))
        .build(),
    private val gson: Gson = Gson()
) {
    suspend fun fetchPoliceStationsNear(
        location: LatLng,
        radiusMeters: Int,
        maxResults: Int
    ): List<SafetyPlace> = withContext(Dispatchers.IO) {
        val query = buildPoliceQuery(location, radiusMeters)

        OVERPASS_ENDPOINTS.asSequence()
            .mapNotNull { endpoint -> fetchFromEndpoint(endpoint, query).getOrNull() }
            .firstOrNull { it.isNotEmpty() }
            ?.sortedBy { RiskZoneRepository.distanceKm(location, it.location) }
            ?.take(maxResults)
            .orEmpty()
    }

    private fun fetchFromEndpoint(endpoint: String, query: String): Result<List<SafetyPlace>> {
        return runCatching {
            val request = Request.Builder()
                .url(endpoint)
                .header("User-Agent", "SafePulse/1.0 nearby-police")
                .post(FormBody.Builder().add("data", query).build())
                .build()

            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@use emptyList()
                val body = response.body?.string().orEmpty()
                val parsed = gson.fromJson(body, OverpassResponse::class.java) ?: return@use emptyList()
                parsed.elements.orEmpty()
                    .asSequence()
                    .mapNotNull { it.toSafetyPlace() }
                    .distinctBy { it.dedupeKey() }
                    .toList()
            }
        }
    }

    private fun buildPoliceQuery(location: LatLng, radiusMeters: Int): String {
        val radius = radiusMeters.coerceIn(MIN_RADIUS_METERS, MAX_RADIUS_METERS)
        val lat = location.latitude
        val lng = location.longitude
        return """
            [out:json][timeout:6];
            (
              node["amenity"="police"](around:$radius,$lat,$lng);
              way["amenity"="police"](around:$radius,$lat,$lng);
              relation["amenity"="police"](around:$radius,$lat,$lng);
              node["police"="station"](around:$radius,$lat,$lng);
              way["police"="station"](around:$radius,$lat,$lng);
              relation["police"="station"](around:$radius,$lat,$lng);
            );
            out center tags;
        """.trimIndent()
    }

    private data class OverpassResponse(
        val elements: List<OverpassElement>? = emptyList()
    )

    private data class OverpassElement(
        val type: String = "",
        val id: Long = 0L,
        val lat: Double? = null,
        val lon: Double? = null,
        val center: OverpassCenter? = null,
        val tags: Map<String, String>? = emptyMap()
    ) {
        fun toSafetyPlace(): SafetyPlace? {
            val stationLat = lat ?: center?.lat ?: return null
            val stationLng = lon ?: center?.lon ?: return null
            val osmTags = tags.orEmpty()
            val name = osmTags["name"]
                ?: osmTags["operator"]
                ?: osmTags["official_name"]
                ?: "Police Station"

            return SafetyPlace(
                id = stableId(type, id),
                name = name,
                type = SafetyPlaceType.POLICE,
                address = addressText(osmTags),
                phoneNumber = osmTags["contact:phone"] ?: osmTags["phone"].orEmpty(),
                location = LatLng(stationLat, stationLng),
                city = osmTags["addr:city"] ?: osmTags["addr:district"].orEmpty()
            )
        }
    }

    private data class OverpassCenter(
        val lat: Double? = null,
        val lon: Double? = null
    )

    companion object {
        private const val MIN_RADIUS_METERS = 1_000
        private const val MAX_RADIUS_METERS = 50_000
        private const val LIVE_POLICE_ID_OFFSET = 40_000_000_000L

        private val OVERPASS_ENDPOINTS = listOf(
            "https://overpass-api.de/api/interpreter",
            "https://overpass.kumi.systems/api/interpreter"
        )

        private fun stableId(type: String, id: Long): Long {
            val typeBucket = when (type) {
                "node" -> 1L
                "way" -> 2L
                "relation" -> 3L
                else -> 9L
            }
            return LIVE_POLICE_ID_OFFSET + typeBucket * 10_000_000_000L + id.coerceAtLeast(0L)
        }

        private fun addressText(tags: Map<String, String>): String {
            tags["addr:full"]?.takeIf { it.isNotBlank() }?.let { return it }

            return listOfNotNull(
                tags["addr:housename"],
                tags["addr:housenumber"],
                tags["addr:street"],
                tags["addr:suburb"],
                tags["addr:city"],
                tags["addr:district"],
                tags["addr:state"]
            )
                .filter { it.isNotBlank() }
                .joinToString(", ")
        }

        private fun SafetyPlace.dedupeKey(): String {
            val roundedLat = "%.5f".format(location.latitude)
            val roundedLng = "%.5f".format(location.longitude)
            return "${name.lowercase().trim()}:$roundedLat:$roundedLng"
        }
    }
}
