package dev.kafli.rftoolkit.modules.ble

import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

data class GeoFix(val lat: Double, val lon: Double, val accM: Float)

data class Sighting(
    val id: String,
    val tracker: TrackerInfo,
    val rssi: Int,
    val ms: Long,
    val fix: GeoFix?,
)

enum class FollowEvent { NEW_SEPARATED, PERSISTENT, FOLLOWING }

/** Live per-tracker state, also used by the UI. */
data class TrackerTrack(
    val id: String,
    val tracker: TrackerInfo,
    val firstMs: Long,
    val lastMs: Long,
    val sightings: Int,
    val places: List<GeoFix>,
    val lastRssi: Int,
    val bestRssi: Int,
    val following: Boolean,
    val persistent: Boolean,
) {
    val spanMs: Long get() = lastMs - firstMs
}

/**
 * "Is this tracker travelling with me?"
 *
 *  - FOLLOWING: seen at >= [minPlaces] distinct places (>= [placeRadiusM] apart, fixes better than
 *    [maxAccM]) over >= [minSpanMs]. Trackers in owner-nearby mode are excluded.
 *  - PERSISTENT: fallback without movement evidence — seen >= [persistMinSightings] times over
 *    >= [persistSpanMs] while we have at most one place.
 *  - NEW_SEPARATED: first sighting of a tracker that reports separated-from-owner.
 *
 * Identity is the BLE address. Apple separated-mode addresses are stable for hours; FMDN rotates
 * faster, so following detection is weaker for Google trackers (documented limitation).
 */
class FollowDetector(
    private val minPlaces: Int = 3,
    private val placeRadiusM: Double = 150.0,
    private val minSpanMs: Long = 15 * 60_000L,
    private val persistSpanMs: Long = 30 * 60_000L,
    private val persistMinSightings: Int = 6,
    private val maxAccM: Float = 100f,
    private val expiryMs: Long = 6 * 60 * 60_000L,
) {
    private val tracks = LinkedHashMap<String, TrackerTrack>()

    @Synchronized
    fun observe(s: Sighting): List<FollowEvent> {
        prune(s.ms)
        val events = mutableListOf<FollowEvent>()
        val prev = tracks[s.id]
        var t = prev?.copy(
            tracker = s.tracker,
            lastMs = maxOf(prev.lastMs, s.ms),
            sightings = prev.sightings + 1,
            lastRssi = s.rssi,
            bestRssi = maxOf(prev.bestRssi, s.rssi),
        ) ?: TrackerTrack(s.id, s.tracker, s.ms, s.ms, 1, emptyList(), s.rssi, s.rssi, following = false, persistent = false)
            .also { if (s.tracker.separated == true) events += FollowEvent.NEW_SEPARATED }

        val fix = s.fix
        if (fix != null && fix.accM <= maxAccM && t.places.none { distanceM(it, fix) < placeRadiusM }) {
            t = t.copy(places = t.places + fix)
        }

        val ownerNearby = s.tracker.separated == false
        if (!t.following && !ownerNearby && t.places.size >= minPlaces && t.spanMs >= minSpanMs) {
            t = t.copy(following = true)
            events += FollowEvent.FOLLOWING
        } else if (!t.following && !t.persistent && !ownerNearby && t.places.size <= 1 &&
            t.spanMs >= persistSpanMs && t.sightings >= persistMinSightings
        ) {
            t = t.copy(persistent = true)
            events += FollowEvent.PERSISTENT
        }
        tracks[s.id] = t
        return events
    }

    @Synchronized
    fun snapshot(): List<TrackerTrack> = tracks.values.toList()

    @Synchronized
    fun clear() = tracks.clear()

    private fun prune(nowMs: Long) {
        tracks.values.removeAll { nowMs - it.lastMs > expiryMs }
    }

    companion object {
        /** Haversine distance in metres. */
        fun distanceM(a: GeoFix, b: GeoFix): Double {
            val r = 6_371_000.0
            val dLat = Math.toRadians(b.lat - a.lat)
            val dLon = Math.toRadians(b.lon - a.lon)
            val h = sin(dLat / 2).pow(2) + cos(Math.toRadians(a.lat)) * cos(Math.toRadians(b.lat)) * sin(dLon / 2).pow(2)
            return 2 * r * asin(sqrt(h))
        }
    }
}
