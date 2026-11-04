package dev.kafli.rftoolkit.modules.wifi

import org.json.JSONObject

enum class Severity(val symbol: String) { HIGH("‼"), MEDIUM("!"), LOW("·"), INFO("i") }

enum class FindingType(val title: String, val severity: Severity, val explain: String) {
    EVIL_TWIN_DOWNGRADE(
        "Evil twin: weaker security", Severity.HIGH,
        "This SSID was seen before with stronger security. A copy with weaker security is a classic credential/MITM trap.",
    ),
    EVIL_TWIN_VENDOR(
        "Possible twin: new vendor", Severity.MEDIUM,
        "Known SSID broadcast by an AP from a different hardware vendor than all previous APs for it.",
    ),
    WPS_ACTIVE_NOW(
        "WPS registration open", Severity.HIGH,
        "WPS 'selected registrar' is set — someone pressed the WPS button; anyone nearby can join right now.",
    ),
    WPS_UNCONFIGURED(
        "WPS unconfigured (setup state)", Severity.HIGH,
        "AP reports WPS state 'not configured' — likely factory default / never set up.",
    ),
    IOT_SETUP_MODE(
        "IoT device in setup mode", Severity.MEDIUM,
        "Open AP whose name matches a smart-device setup hotspot; whoever connects first can usually claim it.",
    ),
    WEP_IN_USE("WEP encryption", Severity.HIGH, "WEP is cracked in minutes."),
    WPA1_ONLY("WPA1 only", Severity.HIGH, "Legacy WPA (TKIP) without WPA2 — deprecated and weak."),
    WPS_ENABLED(
        "WPS enabled", Severity.MEDIUM,
        "WPS PIN mode can be brute-forced on many routers (Reaver / Pixie-Dust). Not locked.",
    ),
    TKIP_ALLOWED("TKIP still allowed", Severity.MEDIUM, "Mixed mode keeps the broken TKIP cipher available."),
    WPA3_TRANSITION(
        "WPA3 transition mode", Severity.LOW,
        "WPA2 still accepted alongside WPA3 — downgrade attacks (Dragonblood) possible.",
    ),
    NO_PMF(
        "No management frame protection", Severity.LOW,
        "Without PMF (802.11w), clients can be deauthenticated by spoofed frames.",
    ),
    OPEN_NETWORK("Open network", Severity.LOW, "No encryption: traffic is readable by anyone nearby."),
    DEFAULT_SSID("Default router name", Severity.LOW, "SSID looks factory-default; the rest of the config may be too."),
    WIFI_DIRECT("Wi-Fi Direct device", Severity.INFO, "Printer/TV/phone exposing a Wi-Fi Direct group."),
    HIDDEN_SSID("Hidden SSID", Severity.INFO, "Hiding the SSID is not a security measure; clients reveal it."),
}

data class Finding(
    val type: FindingType,
    val bssid: String,
    val ssid: String,
    val rssi: Int?,
    val ms: Long,
    val detail: String,
) {
    /** One entry per (type, BSSID): repeated sightings update the same entry. */
    val key: String get() = "${type.name}|$bssid"

    fun toJson(): JSONObject = JSONObject()
        .put("t", "finding").put("ms", ms).put("type", type.name).put("sev", type.severity.name)
        .put("bssid", bssid).put("ssid", ssid).put("rssi", rssi ?: JSONObject.NULL).put("detail", detail)
}
