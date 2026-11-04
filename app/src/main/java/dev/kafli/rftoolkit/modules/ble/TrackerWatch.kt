package dev.kafli.rftoolkit.modules.ble

import android.content.Context
import dev.kafli.rftoolkit.core.Finding
import dev.kafli.rftoolkit.core.FindingType
import dev.kafli.rftoolkit.core.Haptics
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Process-wide tracker state, fed by the survey/guard service and the quick scan.
 * Classifies advertisements, runs [FollowDetector], publishes tracks for the UI and fires haptics.
 */
object TrackerWatch {
    private val detector = FollowDetector()
    private val _tracks = MutableStateFlow<List<TrackerTrack>>(emptyList())
    val tracks: StateFlow<List<TrackerTrack>> = _tracks.asStateFlow()
    @Volatile private var lastPublishMs = 0L

    /**
     * Returns findings for notable events (callers dedupe by [Finding.key]); non-tracker adverts
     * return immediately.
     */
    fun process(ctx: Context, address: String, raw: ByteArray?, rssi: Int, ms: Long, fix: GeoFix?): List<Finding> {
        if (raw == null) return emptyList()
        val info = Trackers.classify(Adv.parse(raw)) ?: return emptyList()
        val events = detector.observe(Sighting(address, info, rssi, ms, fix))

        val now = System.currentTimeMillis()
        if (events.isNotEmpty() || now - lastPublishMs > 1_000) {
            lastPublishMs = now
            _tracks.value = sorted(detector.snapshot())
        }
        if (FollowEvent.FOLLOWING in events) Haptics.play(ctx, Haptics.ALARM)
        else if (FollowEvent.PERSISTENT in events) Haptics.play(ctx, Haptics.NOTICE)

        val track = detector.snapshot().firstOrNull { it.id == address }
        // Separated trackers are reported on every sighting (LOW, no haptics) so each session log
        // gets one entry even if the detector already knew the tracker; callers dedupe by key.
        val reported = if (info.separated == true && FollowEvent.NEW_SEPARATED !in events) {
            events + FollowEvent.NEW_SEPARATED
        } else {
            events
        }
        return reported.map { e ->
            val type = when (e) {
                FollowEvent.FOLLOWING -> FindingType.TRACKER_FOLLOWING
                FollowEvent.PERSISTENT -> FindingType.TRACKER_PERSISTENT
                FollowEvent.NEW_SEPARATED -> FindingType.TRACKER_SEPARATED
            }
            val detail = buildString {
                append(info.detail)
                track?.let { append(" · ${it.places.size} places · ${it.spanMs / 60_000} min · ${it.sightings} sightings") }
            }
            Finding(type, address, info.kind.label, rssi, ms, detail)
        }
    }

    fun clear() {
        detector.clear()
        _tracks.value = emptyList()
    }

    private fun sorted(list: List<TrackerTrack>) = list.sortedWith(
        compareByDescending<TrackerTrack> { it.following }
            .thenByDescending { it.persistent }
            .thenByDescending { it.tracker.separated == true }
            .thenByDescending { it.lastMs },
    )
}
