package dev.kafli.rftoolkit.modules.survey

import dev.kafli.rftoolkit.modules.wifi.RawIe
import org.json.JSONArray
import org.json.JSONObject

/**
 * JSONL record schema v1 for survey sessions. One JSON object per line, discriminated by "t".
 *
 *  session  – header: id, startMs, preset, device info
 *  loc      – location fix
 *  wifi     – one AP sighting; "ies" (raw information elements) only when new/changed for that BSSID;
 *             "rssi" is null when the driver reports 0 (seen on ~2% of SM-R890 results — not a real level)
 *  ble      – one advertisement; "raw" = full advertising payload as hex
 *  scan     – per-scan summary (counts), used later for jamming/anomaly heuristics
 *  batt     – battery level + charge counter, for drain measurement
 *  end      – footer with totals
 *
 * Every sighting carries "loc": [lat, lon, accuracyM] of the latest fix if it is fresh, else null.
 * Raw data is kept on purpose: better parsers can be re-run on old sessions later.
 */
object Records {
    const val SCHEMA_VERSION = 1

    data class Fix(val lat: Double, val lon: Double, val accM: Float, val altM: Double?, val ms: Long, val provider: String?)

    fun session(id: String, startMs: Long, preset: SurveyPreset, device: JSONObject): JSONObject =
        JSONObject().put("t", "session").put("v", SCHEMA_VERSION).put("id", id)
            .put("startMs", startMs).put("preset", preset.name).put("device", device)

    fun loc(fix: Fix, speedMps: Float?): JSONObject =
        JSONObject().put("t", "loc").put("ms", fix.ms)
            .put("lat", fix.lat).put("lon", fix.lon).put("acc", fix.accM.toDouble())
            .put("alt", fix.altM ?: JSONObject.NULL)
            .put("spd", speedMps?.toDouble() ?: JSONObject.NULL)
            .put("prov", fix.provider ?: JSONObject.NULL)

    fun wifi(
        ms: Long, ageMs: Long, bssid: String, ssid: String?, freqMhz: Int, channelWidth: Int,
        rssi: Int?, capabilities: String, wifiStandard: Int, ies: List<RawIe>?, fix: Fix?,
    ): JSONObject {
        val o = JSONObject().put("t", "wifi").put("ms", ms).put("age", ageMs)
            .put("bssid", bssid).put("ssid", ssid ?: JSONObject.NULL)
            .put("freq", freqMhz).put("ch", WifiChannels.channel(freqMhz)).put("width", channelWidth)
            .put("rssi", rssi ?: JSONObject.NULL).put("caps", capabilities).put("std", wifiStandard)
            .put("loc", locArray(fix))
        if (ies != null) {
            o.put("ies", JSONArray().apply { ies.forEach { put(JSONArray().put(it.id).put(it.idExt).put(hex(it.body))) } })
        }
        return o
    }

    fun ble(
        ms: Long, address: String, rssi: Int, txPower: Int?, connectable: Boolean,
        primaryPhy: Int, raw: ByteArray?, fix: Fix?,
    ): JSONObject =
        JSONObject().put("t", "ble").put("ms", ms).put("addr", address).put("rssi", rssi)
            .put("tx", txPower ?: JSONObject.NULL).put("conn", connectable).put("phy", primaryPhy)
            .put("raw", raw?.let(::hex) ?: JSONObject.NULL)
            .put("loc", locArray(fix))

    fun scan(ms: Long, kind: String, count: Int, extra: JSONObject? = null): JSONObject =
        JSONObject().put("t", "scan").put("ms", ms).put("kind", kind).put("n", count)
            .also { o -> extra?.keys()?.forEach { o.put(it, extra.get(it)) } }

    fun batt(ms: Long, pct: Int, chargeCounterUah: Int): JSONObject =
        JSONObject().put("t", "batt").put("ms", ms).put("pct", pct).put("chg", chargeCounterUah)

    fun end(ms: Long, totals: JSONObject): JSONObject =
        JSONObject().put("t", "end").put("ms", ms).put("totals", totals)

    private fun locArray(fix: Fix?): Any =
        fix?.let { JSONArray().put(it.lat).put(it.lon).put(it.accM.toDouble()) } ?: JSONObject.NULL

    /** Stable hash of a BSSID's IE set, to detect when beacon contents change. */
    fun iesHash(ies: List<RawIe>): Int =
        ies.fold(17) { h, ie -> 31 * (31 * (31 * h + ie.id) + ie.idExt) + ie.body.contentHashCode() }

    private val HEX = "0123456789abcdef".toCharArray()

    fun hex(bytes: ByteArray): String {
        val out = CharArray(bytes.size * 2)
        bytes.forEachIndexed { i, b ->
            val v = b.toInt() and 0xFF
            out[i * 2] = HEX[v ushr 4]
            out[i * 2 + 1] = HEX[v and 0x0F]
        }
        return String(out)
    }
}
