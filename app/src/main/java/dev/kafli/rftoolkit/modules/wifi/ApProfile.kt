package dev.kafli.rftoolkit.modules.wifi

/** Overall security class of a BSS, ordered roughly by strength (see [strength]). */
enum class SecurityClass(val label: String, val strength: Int) {
    OPEN("Open", 0),
    WEP("WEP", 1),
    WPA1("WPA1", 2),
    OWE("OWE (Enhanced Open)", 2),
    WPA2_PSK("WPA2-Personal", 3),
    WPA3_TRANSITION("WPA2/WPA3-Personal", 3),
    WPA2_EAP("WPA2-Enterprise", 4),
    WPA3_SAE("WPA3-Personal", 4),
    WPA3_EAP("WPA3-Enterprise", 5),
}

/** Authentication family — a change of family for the same SSID is suspicious. */
enum class AuthFamily { NONE, PSK, SAE, EAP, OWE }

/**
 * Everything the analysis needs about one AP sighting, derived from the scan result + raw IEs.
 * Built by [from] (pure) so rules and tests don't depend on Android classes.
 */
data class ApProfile(
    val bssid: String,
    val ssid: String,
    val capabilities: String,
    val freqMhz: Int,
    val rssi: Int?,
    val ms: Long,
    val security: SecurityClass,
    val auth: AuthFamily,
    val rsn: RsnInfo?,
    val wpa: WpaInfo?,
    val wps: WpsInfo?,
    val hidden: Boolean,
    val oweTransition: Boolean,
    val country: String?,
) {
    /** TKIP allowed for unicast traffic (legacy, broken). */
    val tkipPairwise: Boolean
        get() = (rsn?.pairwise.orEmpty() + wpa?.pairwise.orEmpty()).contains(Cipher.TKIP)

    val pmfCapable: Boolean get() = rsn?.mfpCapable == true
    val pmfRequired: Boolean get() = rsn?.mfpRequired == true

    /** Signature used by the evil-twin baseline, e.g. "WPA2_EAP/EAP". */
    val signature: String get() = "${security.name}/${auth.name}"

    /** First 3 octets of the BSSID, upper-case, e.g. "34:5D:A8". */
    val oui: String get() = bssid.uppercase().take(8)

    /** Locally administered bit set → randomized/virtual MAC (phone hotspots, many enterprise VAPs). */
    val bssidLocallyAdministered: Boolean
        get() = bssid.take(2).toIntOrNull(16)?.let { it and 0x02 != 0 } ?: false

    companion object {
        fun from(
            bssid: String, ssid: String?, capabilities: String, freqMhz: Int, rssi: Int?, ms: Long, ies: List<RawIe>,
        ): ApProfile {
            val rsn = Ies.rsn(ies)
            val wpa = Ies.wpa(ies)
            val (sec, auth) = classify(rsn, wpa, capabilities)
            val cleanSsid = ssid.orEmpty().removeSurrounding("\"").let { if (it == "<unknown ssid>") "" else it }
            return ApProfile(
                bssid = bssid.lowercase(), ssid = cleanSsid, capabilities = capabilities,
                freqMhz = freqMhz, rssi = rssi, ms = ms,
                security = sec, auth = auth, rsn = rsn, wpa = wpa, wps = Ies.wps(ies),
                hidden = cleanSsid.isEmpty() || (ies.isNotEmpty() && Ies.ssidHidden(ies)),
                oweTransition = Ies.hasOweTransition(ies),
                country = Ies.country(ies),
            )
        }

        fun classify(rsn: RsnInfo?, wpa: WpaInfo?, caps: String): Pair<SecurityClass, AuthFamily> {
            if (rsn != null) {
                val a = rsn.akms
                val sae = a.any { it.isSae }
                val psk = a.any { it.isPsk }
                val eap = a.any { it.isEap }
                return when {
                    a.contains(Akm.OWE) -> SecurityClass.OWE to AuthFamily.OWE
                    sae && psk -> SecurityClass.WPA3_TRANSITION to AuthFamily.SAE
                    sae -> SecurityClass.WPA3_SAE to AuthFamily.SAE
                    a.any { it == Akm.SUITE_B || it == Akm.SUITE_B_192 } ||
                        (eap && rsn.mfpRequired && a.contains(Akm.EAP_SHA256)) -> SecurityClass.WPA3_EAP to AuthFamily.EAP
                    eap -> SecurityClass.WPA2_EAP to AuthFamily.EAP
                    psk -> SecurityClass.WPA2_PSK to AuthFamily.PSK
                    else -> fromCaps(caps)
                }
            }
            if (wpa != null) {
                return SecurityClass.WPA1 to if (wpa.akms.any { it.isEap }) AuthFamily.EAP else AuthFamily.PSK
            }
            return fromCaps(caps)
        }

        /** Fallback when IEs are unavailable: parse Android's capability string. */
        fun fromCaps(caps: String): Pair<SecurityClass, AuthFamily> = when {
            "OWE" in caps && "OWE_TRANSITION" !in caps -> SecurityClass.OWE to AuthFamily.OWE
            "SAE" in caps && "PSK" in caps -> SecurityClass.WPA3_TRANSITION to AuthFamily.SAE
            "SAE" in caps -> SecurityClass.WPA3_SAE to AuthFamily.SAE
            "EAP_SUITE_B" in caps -> SecurityClass.WPA3_EAP to AuthFamily.EAP
            ("RSN" in caps || "WPA2" in caps) && "EAP" in caps -> SecurityClass.WPA2_EAP to AuthFamily.EAP
            "RSN" in caps || "WPA2" in caps -> SecurityClass.WPA2_PSK to AuthFamily.PSK
            "WPA-" in caps -> SecurityClass.WPA1 to if ("EAP" in caps) AuthFamily.EAP else AuthFamily.PSK
            "WEP" in caps -> SecurityClass.WEP to AuthFamily.NONE
            else -> SecurityClass.OPEN to AuthFamily.NONE
        }
    }
}
