package dev.kafli.rftoolkit.modules.wifi

import android.content.Context
import dev.kafli.rftoolkit.core.Haptics
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject
import java.io.File

/** Aggregated finding shown in the UI: one per (type, BSSID). */
data class FindingEntry(
    val finding: Finding,
    val firstMs: Long,
    val lastMs: Long,
    val count: Int,
    val bestRssi: Int?,
)

/**
 * Process-wide Wi-Fi findings: runs [WifiRules] + [EvilTwinDetector] over AP profiles from the
 * survey or a one-off scan, aggregates results for the UI and fires haptics for new alerts.
 * The evil-twin baseline is persisted in `files/baseline/wifi.json`.
 */
object WifiFindings {
    private val lock = Any()
    private val detector = EvilTwinDetector()
    private val entries = LinkedHashMap<String, FindingEntry>()
    private var loaded = false

    private val _list = MutableStateFlow<List<FindingEntry>>(emptyList())
    val list: StateFlow<List<FindingEntry>> = _list.asStateFlow()

    private val _baselineSize = MutableStateFlow(0)
    val baselineSize: StateFlow<Int> = _baselineSize.asStateFlow()

    /** Types that trigger the ALARM pattern; other HIGH findings trigger NOTICE. */
    private val ALARM_TYPES = setOf(FindingType.EVIL_TWIN_DOWNGRADE, FindingType.WPS_ACTIVE_NOW)

    private fun baselineFile(ctx: Context) = File(File(ctx.filesDir, "baseline").apply { mkdirs() }, "wifi.json")

    fun ensureLoaded(ctx: Context) = synchronized(lock) {
        if (loaded) return@synchronized
        runCatching { baselineFile(ctx).takeIf { it.exists() }?.readText()?.let { detector.load(JSONObject(it)) } }
        loaded = true
        _baselineSize.value = detector.size
    }

    fun saveBaseline(ctx: Context) = synchronized(lock) {
        runCatching { baselineFile(ctx).writeText(detector.toJson().toString()) }
    }

    fun clearBaseline(ctx: Context) = synchronized(lock) {
        detector.clear()
        baselineFile(ctx).delete()
        _baselineSize.value = 0
    }

    fun clearFindings() = synchronized(lock) {
        entries.clear()
        publish()
    }

    /**
     * Analyzes one scan's worth of APs. Returns every finding produced (the caller decides what to log);
     * haptics fire only for findings whose key is new to the UI list.
     */
    fun process(ctx: Context, aps: List<ApProfile>, haptics: Boolean = true): List<Finding> {
        ensureLoaded(ctx)
        val all = mutableListOf<Finding>()
        var alarm = false
        var notice = false
        synchronized(lock) {
            for (ap in aps) {
                val fs = WifiRules.analyze(ap) + detector.observe(ap)
                for (f in fs) {
                    all += f
                    val prev = entries[f.key]
                    entries[f.key] = if (prev == null) {
                        if (f.type in ALARM_TYPES) alarm = true
                        else if (f.type.severity == Severity.HIGH) notice = true
                        FindingEntry(f, f.ms, f.ms, 1, f.rssi)
                    } else {
                        prev.copy(
                            finding = f, lastMs = maxOf(prev.lastMs, f.ms), count = prev.count + 1,
                            bestRssi = listOfNotNull(prev.bestRssi, f.rssi).maxOrNull(),
                        )
                    }
                }
            }
            publish()
            _baselineSize.value = detector.size
        }
        if (haptics) {
            if (alarm) Haptics.play(ctx, Haptics.ALARM) else if (notice) Haptics.play(ctx, Haptics.NOTICE)
        }
        return all
    }

    private fun publish() {
        _list.value = entries.values.sortedWith(
            compareBy<FindingEntry> { it.finding.type.severity.ordinal }.thenByDescending { it.lastMs },
        )
    }
}
