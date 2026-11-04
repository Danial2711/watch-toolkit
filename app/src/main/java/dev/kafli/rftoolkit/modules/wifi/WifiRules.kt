package dev.kafli.rftoolkit.modules.wifi

import dev.kafli.rftoolkit.core.Finding
import dev.kafli.rftoolkit.core.FindingType
import dev.kafli.rftoolkit.core.Severity
/**
 * Stateless per-AP rules. Heuristic SSID patterns are deliberately conservative;
 * they are hints, not proof — the detail string says what matched.
 */
object WifiRules {

    /** Smart-device setup hotspots (open AP waiting to be provisioned). Pattern → vendor/product. */
    val IOT_SETUP_PATTERNS: List<Pair<Regex, String>> = listOf(
        "^ESP[-_]?[0-9A-F]{6}$" to "Espressif ESP8266/ESP32 (default AP)",
        "^ESP32[-_ ].*" to "Espressif ESP32",
        "^shelly[a-z0-9]*-[0-9A-F]{6,12}$" to "Shelly",
        "^tasmota[-_][0-9A-F]{4,6}(-\\d{1,4})?$" to "Tasmota firmware",
        "^WLED-AP$" to "WLED",
        "^SmartLife-[0-9A-F]{4}$" to "Tuya / Smart Life",
        ".*_miap[0-9A-F]{4}$" to "Xiaomi Mi Home device",
        "^TP-LINK_Smart (Plug|Bulb)_[0-9A-F]{4}$" to "TP-Link Kasa",
        "^Tapo_[A-Za-z]+_[0-9A-F]{4}$" to "TP-Link Tapo",
        "^Meross_[A-Z]{2,}_[0-9A-F]{4}$" to "Meross",
        "^ITEAD-\\d{6,}$" to "Sonoff / eWeLink",
        "^HP-Setup.*" to "HP printer setup",
        "^Chromecast\\d{4}.*" to "Chromecast setup",
        "^OpenBeken_.*" to "OpenBeken firmware",
    ).map { (p, v) -> Regex(p, RegexOption.IGNORE_CASE) to v }

    /** Factory-default router SSIDs. */
    val DEFAULT_SSID_PATTERNS: List<Pair<Regex, String>> = listOf(
        "^TP-Link_[0-9A-F]{4}(_5G)?$" to "TP-Link",
        "^NETGEAR\\d{2}(-5G)?$" to "Netgear",
        "^Linksys\\d{5}(_5GHz)?$" to "Linksys",
        "^dlink-[0-9A-F]{4}(-5GHz)?$" to "D-Link",
        "^ASUS(_[0-9A-F]{2})?(_5G)?$" to "ASUS",
        "^UPC\\d{6,7}$" to "UPC",
        "^Vodafone-[0-9A-Z]{4,6}$" to "Vodafone",
        "^HUAWEI-[0-9A-Z]{4}(-5G)?$" to "Huawei",
        "^Tenda_[0-9A-F]{6}(_5G)?$" to "Tenda",
        "^Xiaomi_[0-9A-F]{4}(_5G)?$" to "Xiaomi",
        "^MikroTik-[0-9A-F]{6}$" to "MikroTik",
        "^FRITZ!Box \\d{4}( Cable)?( [A-Z]{2})?$" to "AVM FRITZ!Box",
        "^Livebox-[0-9A-F]{4}$" to "Orange Livebox",
        "^SKY[0-9A-Z]{5}$" to "Sky",
        "^Keenetic-\\d{4}$" to "Keenetic",
        "^Telekom-[0-9A-Z]{6}$" to "Telekom",
        "^ZTE[-_][0-9A-Z]{4,6}$" to "ZTE",
        "^Mercusys_[0-9A-F]{4}$" to "Mercusys",
    ).map { (p, v) -> Regex(p, RegexOption.IGNORE_CASE) to v }

    private val WIFI_DIRECT = Regex("^DIRECT-[A-Za-z0-9]{2}-.*")

    fun analyze(ap: ApProfile): List<Finding> {
        val out = mutableListOf<Finding>()
        fun add(type: FindingType, detail: String) = out.add(Finding(type, ap.bssid, ap.ssid, ap.rssi, ap.ms, detail))
        val wpsLabel = ap.wps?.deviceLabel?.let { " · $it" } ?: ""

        when (ap.security) {
            SecurityClass.WEP -> add(FindingType.WEP_IN_USE, "Router${wpsLabel.ifEmpty { " unknown" }}")
            SecurityClass.WPA1 -> add(FindingType.WPA1_ONLY, "Pairwise ${ap.wpa?.pairwise?.joinToString("/")}")
            SecurityClass.OPEN -> if (!ap.oweTransition && !ap.hidden) {
                val iot = IOT_SETUP_PATTERNS.firstOrNull { it.first.matches(ap.ssid) }
                if (iot != null) add(FindingType.IOT_SETUP_MODE, iot.second)
                else add(FindingType.OPEN_NETWORK, ap.country?.let { "Country $it" } ?: "")
            }
            else -> {}
        }

        if (ap.tkipPairwise && ap.security != SecurityClass.WPA1) {
            add(FindingType.TKIP_ALLOWED, "Pairwise ${(ap.rsn?.pairwise.orEmpty() + ap.wpa?.pairwise.orEmpty()).distinct().joinToString("/")}")
        }

        ap.wps?.let { wps ->
            when {
                wps.selectedRegistrar == true ->
                    add(FindingType.WPS_ACTIVE_NOW, "Password ID 0x%04X%s".format(wps.devicePasswordId ?: 0, wpsLabel))
                wps.unconfigured -> add(FindingType.WPS_UNCONFIGURED, wpsLabel.removePrefix(" · ").ifEmpty { "No device info" })
            }
            if (!wps.unconfigured && wps.apSetupLocked != true && ap.security != SecurityClass.OPEN) {
                add(FindingType.WPS_ENABLED, wpsLabel.removePrefix(" · ").ifEmpty { "No device info" })
            }
        }

        if (ap.security == SecurityClass.WPA2_PSK && !ap.pmfCapable) add(FindingType.NO_PMF, "WPA2-PSK, PMF off")
        if (ap.security == SecurityClass.WPA3_TRANSITION) {
            add(FindingType.WPA3_TRANSITION, if (ap.pmfRequired) "PMF required" else "PMF optional")
        }

        if (ap.hidden) add(FindingType.HIDDEN_SSID, ap.security.label)
        if (WIFI_DIRECT.matches(ap.ssid)) add(FindingType.WIFI_DIRECT, ap.security.label + wpsLabel)
        DEFAULT_SSID_PATTERNS.firstOrNull { it.first.matches(ap.ssid) }?.let {
            add(FindingType.DEFAULT_SSID, it.second + wpsLabel)
        }
        return out
    }
}
