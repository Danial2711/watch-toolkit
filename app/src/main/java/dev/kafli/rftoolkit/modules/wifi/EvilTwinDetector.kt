package dev.kafli.rftoolkit.modules.wifi

import dev.kafli.rftoolkit.core.Finding
import dev.kafli.rftoolkit.core.FindingType
import dev.kafli.rftoolkit.core.Severity
import org.json.JSONArray
import org.json.JSONObject

/**
 * Remembers, per SSID, which security profiles and hardware vendors (BSSID OUIs) it has been seen with,
 * and flags sightings that don't fit:
 *
 *  - EVIL_TWIN_DOWNGRADE: the SSID appears with *weaker* security than every profile seen before
 *    (e.g. eduroam/WPA2-Enterprise suddenly Open or WPA2-PSK). Such profiles are NOT learned,
 *    so the alert keeps firing while the twin is around.
 *  - EVIL_TWIN_VENDOR: same security, but a BSSID vendor never seen for this SSID, after at least
 *    [minBssidsForVendorCheck] BSSIDs were learned. Randomized (locally administered) BSSIDs are ignored.
 *
 * The first sighting of an SSID is trusted (trust-on-first-use) — the baseline grows over time.
 */
class EvilTwinDetector(
    private val minBssidsForVendorCheck: Int = 3,
    private val maxSsids: Int = 5_000,
    private val maxBssidsPerSsid: Int = 64,
) {
    class Entry(
        val signatures: MutableSet<String> = linkedSetOf(),
        val ouis: MutableSet<String> = linkedSetOf(),
        val bssids: MutableSet<String> = linkedSetOf(),
        var firstMs: Long = 0,
        var lastMs: Long = 0,
    )

    private val baseline = object : LinkedHashMap<String, Entry>(256, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Entry>?) = size > maxSsids
    }

    val size: Int @Synchronized get() = baseline.size

    @Synchronized
    fun observe(ap: ApProfile): List<Finding> {
        if (ap.hidden || ap.ssid.isBlank()) return emptyList()
        val e = baseline[ap.ssid]
        if (e == null) {
            baseline[ap.ssid] = Entry(firstMs = ap.ms, lastMs = ap.ms).also { learn(it, ap) }
            return emptyList()
        }
        e.lastMs = maxOf(e.lastMs, ap.ms)

        if (ap.signature !in e.signatures) {
            val weakest = e.signatures.minOfOrNull { strengthOf(it) }
                ?: run { learn(e, ap); return emptyList() }
            if (ap.security.strength < weakest) {
                val usual = e.signatures.joinToString { sig -> labelOf(sig) }
                return listOf(
                    Finding(
                        FindingType.EVIL_TWIN_DOWNGRADE, ap.bssid, ap.ssid, ap.rssi, ap.ms,
                        "Usually $usual, now ${ap.security.label} (vendor ${ap.oui})",
                    ),
                )
            }
            // Equal or stronger new profile (e.g. upgrade to WPA3): learn it silently.
            learn(e, ap)
            return emptyList()
        }

        val out = mutableListOf<Finding>()
        if (!ap.bssidLocallyAdministered && ap.oui !in e.ouis && e.ouis.isNotEmpty() &&
            e.bssids.size >= minBssidsForVendorCheck && ap.bssid !in e.bssids
        ) {
            out += Finding(
                FindingType.EVIL_TWIN_VENDOR, ap.bssid, ap.ssid, ap.rssi, ap.ms,
                "Vendor ${ap.oui} new; known: ${e.ouis.take(4).joinToString()}",
            )
        }
        learn(e, ap)
        return out
    }

    private fun learn(e: Entry, ap: ApProfile) {
        e.signatures += ap.signature
        if (!ap.bssidLocallyAdministered) e.ouis += ap.oui
        if (e.bssids.size < maxBssidsPerSsid) e.bssids += ap.bssid
    }

    private fun strengthOf(signature: String): Int =
        runCatching { SecurityClass.valueOf(signature.substringBefore('/')).strength }.getOrDefault(0)

    private fun labelOf(signature: String): String =
        runCatching { SecurityClass.valueOf(signature.substringBefore('/')).label }.getOrDefault(signature)

    // ------------------------------------------------------------- persistence

    @Synchronized
    fun toJson(): JSONObject {
        val ssids = JSONObject()
        for ((ssid, e) in baseline) {
            ssids.put(
                ssid, JSONObject()
                    .put("sigs", JSONArray(e.signatures.toList()))
                    .put("ouis", JSONArray(e.ouis.toList()))
                    .put("bssids", JSONArray(e.bssids.toList()))
                    .put("first", e.firstMs).put("last", e.lastMs),
            )
        }
        return JSONObject().put("v", 1).put("ssids", ssids)
    }

    @Synchronized
    fun load(json: JSONObject) {
        baseline.clear()
        val ssids = json.optJSONObject("ssids") ?: return
        for (ssid in ssids.keys()) {
            val o = ssids.getJSONObject(ssid)
            fun JSONArray?.strings() = if (this == null) emptyList() else List(length()) { getString(it) }
            baseline[ssid] = Entry(
                signatures = o.optJSONArray("sigs").strings().toMutableSet(),
                ouis = o.optJSONArray("ouis").strings().toMutableSet(),
                bssids = o.optJSONArray("bssids").strings().toMutableSet(),
                firstMs = o.optLong("first"), lastMs = o.optLong("last"),
            )
        }
    }

    @Synchronized
    fun clear() = baseline.clear()
}
